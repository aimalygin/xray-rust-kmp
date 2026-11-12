package io.github.aimalygin.xrayrust.kmp

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.xrayrust.mobile.XrayCoreException
import org.xrayrust.mobile.XrayVpnService
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * `XrayVpnService` driven by [XrayTunnel]. Declare it (or a subclass) in the app manifest with
 * `android.permission.BIND_VPN_SERVICE` and the `android.net.VpnService` intent filter.
 *
 * The config is handed over in process memory, never through Intent extras, so credentials
 * do not pass through system_server. The service must run in the process that owns
 * [AndroidTunnelEngine]: declared with another `android:process`, it never sees a command.
 *
 * `XrayVpnService` 0.9.0 does not call `startForeground`. An app that wants the tunnel to
 * outlive its UI promotes the service itself, in a subclass.
 */
public open class XrayKmpVpnService : XrayVpnService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sequencer = XraySessionSequencer(
        startTunnel = { startXrayTunnel(it) },
        stopTunnel = ::stopXrayTunnel,
        emit = XrayServiceBridge::emit,
    )

    override fun onCreate() {
        super.onCreate()
        XrayServiceBridge.attach(this)
    }

    // The intent only wakes the service: commands come from the bridge, in the order they were issued.
    // With a newer start already on its way stopSelf(startId) is a no-op, so that start is not destroyed.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        drainCommands()
        if (sequencer.isIdleOrStopping) stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onXrayTunnelStarted() {
        mainHandler.post { sequencer.onStarted() }
    }

    override fun onXrayTunnelStartFailed(error: Throwable) {
        val failure = error.toTunnelFailure(TunnelFailure.Kind.StartFailed)
        mainHandler.post { sequencer.onStartFailed(failure) }
    }

    override fun onXrayTunnelFatalError(error: Throwable) {
        val failure = error.toTunnelFailure(TunnelFailure.Kind.Fatal)
        mainHandler.post { sequencer.onFatalError(failure) }
    }

    override fun onRevoke() {
        sequencer.submit(XrayServiceCommand.Stop)
        super.onRevoke()
    }

    override fun onDestroy() {
        super.onDestroy()
        XrayServiceBridge.detach(this)
        mainHandler.removeCallbacksAndMessages(null)
        sequencer.onDestroyed()
    }

    /** For a stop the system refused to deliver as an intent (background start restrictions). */
    internal fun drainCommandsWithoutIntent() {
        mainHandler.post {
            drainCommands()
            if (sequencer.isIdleOrStopping) stopSelf()
        }
    }

    private fun drainCommands() = generateSequence(XrayServiceBridge::poll).forEach(sequencer::submit)
}

// XRAY_STATUS_CONFIG_ERROR in xray_ffi.h.
private const val STATUS_CONFIG_ERROR = 3

// Type and native status only, as on iOS and in imports: core and config errors may quote parts of the config.
internal fun Throwable.toTunnelFailure(kind: TunnelFailure.Kind): TunnelFailure {
    val code = (this as? XrayCoreException)?.code
    val rejected = kind == TunnelFailure.Kind.StartFailed && code == STATUS_CONFIG_ERROR
    return TunnelFailure(if (rejected) TunnelFailure.Kind.ConfigRejected else kind, safeDescription(), code)
}

/** Process-local hand-off between [AndroidTunnelEngine] and the service. */
internal object XrayServiceBridge {
    private const val TAG = "XrayKmpVpnService"
    private const val EVENT_BUFFER = 64

    private val commands = ConcurrentLinkedQueue<XrayServiceCommand>()
    private val service = AtomicReference<XrayKmpVpnService?>(null)
    private val engineCreated = AtomicBoolean(false)
    private val eventFlow = MutableSharedFlow<XrayEngineEvent>(extraBufferCapacity = EVENT_BUFFER)

    val events: Flow<XrayEngineEvent> = eventFlow.asSharedFlow()

    fun onEngineCreated() = engineCreated.set(true)

    fun offer(command: XrayServiceCommand) {
        commands.add(command)
    }

    fun withdraw(command: XrayServiceCommand) {
        commands.remove(command)
    }

    fun poll(): XrayServiceCommand? = commands.poll()

    fun attach(created: XrayKmpVpnService) {
        service.set(created)
        if (!engineCreated.get()) {
            Log.w(TAG, "no AndroidTunnelEngine in this process yet; a service declared with android:process gets no commands")
        }
    }

    fun detach(destroyed: XrayKmpVpnService) {
        service.compareAndSet(destroyed, null)
    }

    /** The wake-up intent was refused: a live service drains anyway, and without one nothing runs. */
    fun drainWithoutIntent() {
        val live = service.get()
        if (live != null) return live.drainCommandsWithoutIntent()
        commands.clear()
        emit(XrayEngineEvent.Stopped(NO_GENERATION))
    }

    fun emit(event: XrayEngineEvent) {
        eventFlow.tryEmit(event)
    }
}

/**
 * Starts and stops [serviceClass]. The app asks for VPN consent (`VpnService.prepare`) before
 * [start]; without it the start fails at once with [TunnelFailure.Kind.PermissionRequired].
 */
public class AndroidTunnelEngine(
    context: Context,
    private val serviceClass: Class<out XrayKmpVpnService> = XrayKmpVpnService::class.java,
) : XrayTunnelEngine {
    private val context = context.applicationContext

    override val events: Flow<XrayEngineEvent> = XrayServiceBridge.events

    init {
        XrayServiceBridge.onEngineCreated()
    }

    override fun start(configJson: String, generation: Long) {
        if (VpnService.prepare(context) != null) return XrayServiceBridge.emit(permissionRequired(generation))
        val command = XrayServiceCommand.Start(configJson, generation)
        XrayServiceBridge.offer(command)
        try {
            wakeService()
        } catch (error: IllegalStateException) {
            XrayServiceBridge.withdraw(command)
            throw error
        }
    }

    // startService is refused for a background app with no tunnel up; the stop must still land.
    override fun stop() {
        XrayServiceBridge.offer(XrayServiceCommand.Stop)
        try {
            wakeService()
        } catch (_: IllegalStateException) {
            XrayServiceBridge.drainWithoutIntent()
        }
    }

    // XrayVpnService 0.9.0 neither runs XrayNetworkChangeCoordinator nor exposes its core, so no
    // protocol is rebound here; TunnelPolicy.restartOnNetworkChange decides for all of them.
    override fun rebindsOnNetworkChange(protocol: XrayProtocol): Boolean = false

    private fun wakeService() {
        context.startService(Intent(context, serviceClass))
    }

    private fun permissionRequired(generation: Long): XrayEngineEvent = XrayEngineEvent.Failed(
        generation,
        TunnelFailure(TunnelFailure.Kind.PermissionRequired, "VPN permission is not granted"),
    )
}
