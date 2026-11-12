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
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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
 *
 * A tunnel this engine did not start (already up when the app launched, or switched on in the
 * system settings) is reported as `Started(XrayEngineEvent.EXTERNAL_SESSION)`, so the controller
 * shows it and can stop it. Main thread only: [scope] must dispatch on the main queue.
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

    // The first subscriber is the controller: only then can a tunnel that is already up be reported.
    override val events: Flow<XrayEngineEvent> = engineEvents.onSubscription { attachOnce() }

    /** `NEVPNStatus` of the managed connection, updated from `NEVPNStatusDidChangeNotification`. */
    public val status: StateFlow<XrayVpnStatus>
        field = MutableStateFlow(XrayVpnStatus.Invalid)

    private var manager: NETunnelProviderManager? = null
    private var statusObserver: NSObjectProtocol? = null
    private var phase = Phase.Idle
    private var startJob: Job? = null
    private var generation = NO_GENERATION
    private var attachRequested = false

    override fun start(configJson: String, generation: Long) {
        startJob?.cancel()
        this.generation = generation
        phase = Phase.Preparing
        startJob = scope.launch {
            try {
                launchTunnel(configJson)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                phase = Phase.Idle
                emit(XrayEngineEvent.Failed(generation, TunnelFailure(TunnelFailure.Kind.StartFailed, error.failureText())))
            }
        }
    }

    /** The wrapper's network observer rebinds these without restarting the core. */
    override fun rebindsOnNetworkChange(protocol: XrayProtocol): Boolean = protocol in reboundProtocols

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
                emit(XrayEngineEvent.Stopped(generation))
            }
            Phase.Stopping -> Unit
        }
    }

    private fun attachOnce() {
        if (attachRequested) return
        attachRequested = true
        scope.launch { attachToSavedManager() }
    }

    // Best effort: without readable preferences there is nothing to adopt.
    private suspend fun attachToSavedManager() {
        val saved = try {
            loadManagers().firstOrNull { it.providerBundleId == providerBundleIdentifier }
        } catch (_: NetworkExtensionException) {
            null
        }
        // A start issued meanwhile owns the manager.
        if (saved == null || manager != null) return
        manager = saved
        observeStatus()
        onStatusChanged(saved.connection)
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
        // withTimeout would end start() as a silent cancellation; this becomes a reported failure.
        withTimeoutOrNull(STOP_TIMEOUT) { status.first { it in inactiveStatuses } }
            ?: throw PreviousSessionStopTimeoutException()
    }

    // Every call re-reads the status: each start loads a fresh manager, and the last
    // notification may be about the previous one.
    private fun observeStatus() {
        if (statusObserver == null) {
            statusObserver = NSNotificationCenter.defaultCenter.addObserverForName(
                name = NEVPNStatusDidChangeNotification,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) { _ -> manager?.connection?.let(::onStatusChanged) }
        }
        manager?.connection?.let { status.value = it.status.toXrayVpnStatus() }
    }

    private fun onStatusChanged(connection: NEVPNConnection) {
        val current = connection.status.toXrayVpnStatus()
        status.value = current
        when (current) {
            XrayVpnStatus.Connected -> when (phase) {
                Phase.Launched -> reportRunning(generation)
                // Up without a start from this engine: before the app launched or from the system settings.
                Phase.Idle -> reportRunning(XrayEngineEvent.EXTERNAL_SESSION)
                Phase.Preparing, Phase.Running, Phase.Stopping -> Unit
            }
            XrayVpnStatus.Disconnected, XrayVpnStatus.Invalid -> onDisconnected(connection)
            XrayVpnStatus.Connecting, XrayVpnStatus.Reasserting, XrayVpnStatus.Disconnecting -> Unit
        }
    }

    private fun reportRunning(session: Long) {
        generation = session
        phase = Phase.Running
        emit(XrayEngineEvent.Started(session))
    }

    private fun onDisconnected(connection: NEVPNConnection) {
        when (val previous = phase) {
            Phase.Idle, Phase.Preparing -> return
            Phase.Stopping -> emit(XrayEngineEvent.Stopped(generation))
            Phase.Launched, Phase.Running -> {
                val session = generation
                val wasRunning = previous == Phase.Running
                connection.lastDisconnectError { error -> emit(disconnectEvent(session, wasRunning, error)) }
            }
        }
        phase = Phase.Idle
    }

    // Without an error a running tunnel was stopped from outside (system settings, another VPN).
    // A tunnel that never connected failed to start, whether or not the system kept an error.
    private fun disconnectEvent(session: Long, wasRunning: Boolean, error: NSError?): XrayEngineEvent = when {
        error != null -> {
            val kind = if (wasRunning) TunnelFailure.Kind.Fatal else TunnelFailure.Kind.StartFailed
            XrayEngineEvent.Failed(session, TunnelFailure(kind, error.failureText(), error.code.toInt()))
        }
        wasRunning -> XrayEngineEvent.Stopped(session)
        else -> XrayEngineEvent.Failed(
            session,
            TunnelFailure(TunnelFailure.Kind.StartFailed, "disconnected before connecting"),
        )
    }

    private fun emit(event: XrayEngineEvent) {
        engineEvents.tryEmit(event)
    }

    private enum class Phase { Idle, Preparing, Launched, Running, Stopping }

    private companion object {
        const val EVENT_BUFFER = 16
        val STOP_TIMEOUT = 10.seconds
        val inactiveStatuses = setOf(XrayVpnStatus.Disconnected, XrayVpnStatus.Invalid)
        val reboundProtocols = setOf(XrayProtocol.Hysteria2, XrayProtocol.Wireguard)
    }
}

// XrayTunnelProviderMessage.providerConfigReferenceKey / configReferenceOptionKey at xray-rust-mobile 0.9.0.
private const val PROVIDER_CONFIG_REFERENCE_KEY = "configReference"
private const val OPTION_CONFIG_REFERENCE_KEY = "xrayConfigReference"

private val NETunnelProviderManager.tunnelProtocol: NETunnelProviderProtocol?
    get() = protocolConfiguration as? NETunnelProviderProtocol

private val NETunnelProviderManager.providerBundleId: String?
    get() = tunnelProtocol?.providerBundleIdentifier

private val NETunnelProviderManager.configReference: String?
    get() = tunnelProtocol?.providerConfiguration?.get(PROVIDER_CONFIG_REFERENCE_KEY) as? String

private fun NEVPNStatus.toXrayVpnStatus(): XrayVpnStatus = when (this) {
    NEVPNStatusDisconnected -> XrayVpnStatus.Disconnected
    NEVPNStatusConnecting -> XrayVpnStatus.Connecting
    NEVPNStatusConnected -> XrayVpnStatus.Connected
    NEVPNStatusReasserting -> XrayVpnStatus.Reasserting
    NEVPNStatusDisconnecting -> XrayVpnStatus.Disconnecting
    else -> XrayVpnStatus.Invalid
}

private class PreviousSessionStopTimeoutException : IllegalStateException()

private class NetworkExtensionException(val error: NSError) : Exception("${error.domain}#${error.code}")

// Domain and code only: localized NetworkExtension texts carry no extra signal for the policy.
private fun Throwable.failureText(): String = (this as? NetworkExtensionException)?.message ?: safeDescription()

private fun NSError.failureText(): String = "$domain#$code"

private suspend fun loadManagers(): List<NETunnelProviderManager> = suspendCancellableCoroutine { continuation ->
    NETunnelProviderManager.loadAllFromPreferencesWithCompletionHandler { managers, error ->
        continuation.resumeWith(error.toResult { managers.orEmpty().filterIsInstance<NETunnelProviderManager>() })
    }
}

private suspend fun NETunnelProviderManager.awaitSave() = suspendCancellableCoroutine { continuation ->
    saveToPreferencesWithCompletionHandler { error -> continuation.resumeWith(error.toResult { Unit }) }
}

private suspend fun NETunnelProviderManager.awaitLoad() = suspendCancellableCoroutine { continuation ->
    loadFromPreferencesWithCompletionHandler { error -> continuation.resumeWith(error.toResult { Unit }) }
}

private inline fun <T> NSError?.toResult(value: () -> T): Result<T> =
    if (this == null) Result.success(value()) else Result.failure(NetworkExtensionException(this))

private fun NEVPNConnection.startTunnel(options: Map<Any?, *>) = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    if (!startVPNTunnelWithOptions(options, error.ptr)) {
        throw error.value?.let(::NetworkExtensionException) ?: IllegalStateException("startVPNTunnel failed")
    }
}

// fetchLastDisconnectError is iOS 16+; older systems report a plain stop.
private fun NEVPNConnection.lastDisconnectError(onResult: (NSError?) -> Unit) =
    if (respondsToSelector(NSSelectorFromString("fetchLastDisconnectErrorWithCompletionHandler:"))) {
        fetchLastDisconnectErrorWithCompletionHandler(onResult)
    } else {
        onResult(null)
    }
