@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package io.github.aimalygin.xrayrust.kmp

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSUUID
import platform.NetworkExtension.NEVPNConnection
import platform.NetworkExtension.NEVPNStatus
import platform.NetworkExtension.NEVPNStatusConnected
import platform.NetworkExtension.NEVPNStatusConnecting
import platform.NetworkExtension.NEVPNStatusDidChangeNotification
import platform.NetworkExtension.NEVPNStatusDisconnected
import platform.NetworkExtension.NEVPNStatusDisconnecting
import platform.NetworkExtension.NEVPNStatusReasserting
import platform.NetworkExtension.NETunnelProviderManager
import platform.NetworkExtension.NETunnelProviderProtocol
import platform.darwin.NSObjectProtocol
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

/**
 * Keychain write the Network Extension can read back. The sample implements it with
 * `XrayKeychainConfigStore` from the SwiftPM product `XrayAppleShared`, the store
 * `XrayPacketTunnelProvider` reads; app and extension share a keychain access group.
 */
public fun interface XrayConfigStore {
    @Throws(Exception::class)
    public fun store(configJson: String, reference: String)
}

public enum class XrayVpnStatus { Invalid, Disconnected, Connecting, Connected, Reasserting, Disconnecting }

/**
 * App-process engine: drives `NETunnelProviderManager` for a Network Extension that subclasses
 * `XrayPacketTunnelProvider`. The config goes to the keychain under a reference; the extension
 * gets only the reference (provider configuration and start option), never the JSON itself.
 * No Kotlin/Native code runs in the extension.
 */
public class XrayIosTunnelEngine(
    private val providerBundleIdentifier: String,
    private val configStore: XrayConfigStore,
    private val localizedDescription: String,
    private val scope: CoroutineScope,
) : XrayTunnelEngine {
    public constructor(providerBundleIdentifier: String, configStore: XrayConfigStore, localizedDescription: String) :
        this(providerBundleIdentifier, configStore, localizedDescription, MainScope())

    private val engineEvents = MutableSharedFlow<XrayEngineEvent>(extraBufferCapacity = EVENT_BUFFER)
    override val events: Flow<XrayEngineEvent> get() = engineEvents

    /** `NEVPNStatus` of the managed connection, updated from `NEVPNStatusDidChangeNotification`. */
    public val status: StateFlow<XrayVpnStatus>
        field = MutableStateFlow(XrayVpnStatus.Invalid)

    private var manager: NETunnelProviderManager? = null
    private var statusObserver: NSObjectProtocol? = null
    private var phase = Phase.Idle
    private var startJob: Job? = null

    override fun start(configJson: String) {
        startJob?.cancel()
        phase = Phase.Preparing
        startJob = scope.launch {
            try {
                launchTunnel(configJson)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                phase = Phase.Idle
                engineEvents.tryEmit(XrayEngineEvent.Failed(TunnelFailure(TunnelFailure.Kind.StartFailed, error.failureText())))
            }
        }
    }

    override fun stop() {
        startJob?.cancel()
        val connection = manager?.connection
        when (phase) {
            Phase.Launched, Phase.Running -> if (connection != null) {
                phase = Phase.Stopping
                connection.stopVPNTunnel()
            }
            Phase.Idle, Phase.Preparing -> {
                phase = Phase.Idle
                engineEvents.tryEmit(XrayEngineEvent.Stopped)
            }
            Phase.Stopping -> Unit
        }
    }

    private suspend fun launchTunnel(configJson: String) {
        val target = loadManagers().firstOrNull { it.providerBundleId == providerBundleIdentifier }
            ?: NETunnelProviderManager()
        manager = target
        observeStatus()
        stopActiveSession(target.connection)
        val reference = target.configReference ?: "tunnel.${NSUUID().UUIDString.lowercase()}"
        configStore.store(configJson, reference)
        target.protocolConfiguration = NETunnelProviderProtocol().apply {
            providerBundleIdentifier = this@XrayIosTunnelEngine.providerBundleIdentifier
            serverAddress = localizedDescription
            providerConfiguration = mapOf<Any?, Any>(PROVIDER_CONFIG_REFERENCE_KEY to reference)
        }
        target.localizedDescription = localizedDescription
        target.enabled = true
        target.awaitSave()
        target.awaitLoad()
        target.connection.startTunnel(mapOf<Any?, Any>(OPTION_CONFIG_REFERENCE_KEY to reference))
        phase = Phase.Launched
    }

    private suspend fun stopActiveSession(connection: NEVPNConnection) {
        if (connection.status.toXrayVpnStatus() in inactiveStatuses) return
        connection.stopVPNTunnel()
        withTimeout(STOP_TIMEOUT) { status.first { it in inactiveStatuses } }
    }

    private fun observeStatus() {
        if (statusObserver != null) return
        statusObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = NEVPNStatusDidChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> manager?.connection?.let(::onStatusChanged) }
        manager?.connection?.let { status.value = it.status.toXrayVpnStatus() }
    }

    private fun onStatusChanged(connection: NEVPNConnection) {
        val current = connection.status.toXrayVpnStatus()
        status.value = current
        when (current) {
            XrayVpnStatus.Connected -> if (phase == Phase.Launched) {
                phase = Phase.Running
                engineEvents.tryEmit(XrayEngineEvent.Started)
            }
            XrayVpnStatus.Disconnected, XrayVpnStatus.Invalid -> onDisconnected(connection)
            XrayVpnStatus.Connecting, XrayVpnStatus.Reasserting, XrayVpnStatus.Disconnecting -> Unit
        }
    }

    private fun onDisconnected(connection: NEVPNConnection) {
        val previous = phase
        when (previous) {
            Phase.Idle, Phase.Preparing -> return
            Phase.Stopping -> engineEvents.tryEmit(XrayEngineEvent.Stopped)
            Phase.Launched, Phase.Running -> {
                val kind = if (previous == Phase.Running) TunnelFailure.Kind.Fatal else TunnelFailure.Kind.StartFailed
                connection.lastDisconnectError { error ->
                    val event = error?.let { XrayEngineEvent.Failed(TunnelFailure(kind, it.failureText())) }
                    engineEvents.tryEmit(event ?: XrayEngineEvent.Stopped)
                }
            }
        }
        phase = Phase.Idle
    }

    private enum class Phase { Idle, Preparing, Launched, Running, Stopping }

    private companion object {
        // XrayTunnelProviderMessage.providerConfigReferenceKey / configReferenceOptionKey at xray-rust-mobile 0.9.0.
        const val PROVIDER_CONFIG_REFERENCE_KEY = "configReference"
        const val OPTION_CONFIG_REFERENCE_KEY = "xrayConfigReference"
        const val EVENT_BUFFER = 16
        val STOP_TIMEOUT = 10.seconds
        val inactiveStatuses = setOf(XrayVpnStatus.Disconnected, XrayVpnStatus.Invalid)
    }
}

private val NETunnelProviderManager.tunnelProtocol: NETunnelProviderProtocol?
    get() = protocolConfiguration as? NETunnelProviderProtocol

private val NETunnelProviderManager.providerBundleId: String?
    get() = tunnelProtocol?.providerBundleIdentifier

private val NETunnelProviderManager.configReference: String?
    get() = tunnelProtocol?.providerConfiguration?.get("configReference") as? String

private fun NEVPNStatus.toXrayVpnStatus(): XrayVpnStatus = when (this) {
    NEVPNStatusDisconnected -> XrayVpnStatus.Disconnected
    NEVPNStatusConnecting -> XrayVpnStatus.Connecting
    NEVPNStatusConnected -> XrayVpnStatus.Connected
    NEVPNStatusReasserting -> XrayVpnStatus.Reasserting
    NEVPNStatusDisconnecting -> XrayVpnStatus.Disconnecting
    else -> XrayVpnStatus.Invalid
}

private class NetworkExtensionException(val error: NSError) : Exception("${error.domain}#${error.code}")

// Domain and code only: localized NetworkExtension texts carry no extra signal for the policy.
private fun Throwable.failureText(): String = (this as? NetworkExtensionException)?.message ?: safeDescription()

private fun NSError.failureText(): String = "$domain#$code"

private suspend fun loadManagers(): List<NETunnelProviderManager> = suspendCancellableCoroutine { continuation ->
    NETunnelProviderManager.loadAllFromPreferencesWithCompletionHandler { managers, error ->
        if (error != null) {
            continuation.resumeWithException(NetworkExtensionException(error))
        } else {
            continuation.resume(managers.orEmpty().filterIsInstance<NETunnelProviderManager>())
        }
    }
}

private suspend fun NETunnelProviderManager.awaitSave() = suspendCancellableCoroutine { continuation ->
    saveToPreferencesWithCompletionHandler { error -> continuation.resumeWith(error.toUnitResult()) }
}

private suspend fun NETunnelProviderManager.awaitLoad() = suspendCancellableCoroutine { continuation ->
    loadFromPreferencesWithCompletionHandler { error -> continuation.resumeWith(error.toUnitResult()) }
}

private fun NSError?.toUnitResult(): Result<Unit> =
    if (this == null) Result.success(Unit) else Result.failure(NetworkExtensionException(this))

private fun NEVPNConnection.startTunnel(options: Map<Any?, *>) = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    if (!startVPNTunnelWithOptions(options, error.ptr)) {
        throw error.value?.let(::NetworkExtensionException) ?: IllegalStateException("startVPNTunnel failed")
    }
}

// fetchLastDisconnectError is iOS 16+; older systems report a plain stop.
private fun NEVPNConnection.lastDisconnectError(onResult: (NSError?) -> Unit) {
    if (respondsToSelector(NSSelectorFromString("fetchLastDisconnectErrorWithCompletionHandler:"))) {
        fetchLastDisconnectErrorWithCompletionHandler(onResult)
    } else {
        onResult(null)
    }
}
