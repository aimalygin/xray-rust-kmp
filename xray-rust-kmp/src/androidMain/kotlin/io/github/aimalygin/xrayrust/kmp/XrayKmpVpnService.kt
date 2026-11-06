package io.github.aimalygin.xrayrust.kmp

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.xrayrust.mobile.XrayVpnService
import java.util.concurrent.atomic.AtomicReference

/**
 * `XrayVpnService` driven by [XrayTunnel]. Declare it (or a subclass) in the app manifest with
 * `android.permission.BIND_VPN_SERVICE` and the `android.net.VpnService` intent filter.
 *
 * The config is handed over in process memory, never through Intent extras, so credentials
 * do not pass through system_server. The service must run in the app's main process.
 *
 * In xray-rust-mobile 0.9.0 a start issued while an earlier one is still starting is ignored, and
 * a stop during startup cancels it without any callback. So a start in flight is never cancelled
 * here: a newer start or a stop waits until it reports, then replaces or stops it. Destroying the
 * service still cancels it at once.
 */
public open class XrayKmpVpnService : XrayVpnService() {
    private val mainHandler = Handler(Looper.getMainLooper())

    // Main thread only.
    private var inFlight: XrayStartRequest? = null
    private var queued: XrayStartRequest? = null
    private var stopWhenSettled = false
    private var runningGeneration: Long? = null
    private var lastGeneration = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> XrayServiceBridge.takeStart()?.let(::requestStart)
            ACTION_STOP -> {
                requestStop()
                // With a newer start already queued this is a no-op, so that start is not destroyed.
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onXrayTunnelStarted() {
        mainHandler.post { settleStart(failure = null) }
    }

    override fun onXrayTunnelStartFailed(error: Throwable) {
        val failure = error.toTunnelFailure(TunnelFailure.Kind.StartFailed)
        mainHandler.post { settleStart(failure) }
    }

    override fun onXrayTunnelFatalError(error: Throwable) {
        val failure = error.toTunnelFailure(TunnelFailure.Kind.Fatal)
        mainHandler.post { onFatalError(failure) }
    }

    override fun onRevoke() {
        requestStop()
        super.onRevoke()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        queued = null
        stopWhenSettled = false
        val unreported = inFlight?.generation ?: runningGeneration
        inFlight = null
        runningGeneration = null
        unreported?.let(::reportStopped)
    }

    private fun requestStart(request: XrayStartRequest) {
        lastGeneration = request.generation
        stopWhenSettled = false
        if (inFlight == null) launch(request) else queued = request
    }

    private fun launch(request: XrayStartRequest) {
        // Nothing is starting, so this stops a running session synchronously and the start below is accepted.
        stopXrayTunnel()
        runningGeneration = null
        inFlight = request
        try {
            startXrayTunnel(request.configJson)
        } catch (error: RuntimeException) {
            inFlight = null
            XrayServiceBridge.emit(XrayEngineEvent.Failed(request.generation, error.toTunnelFailure(TunnelFailure.Kind.StartFailed)))
        }
    }

    private fun requestStop() {
        queued = null
        if (inFlight != null) {
            stopWhenSettled = true
            return
        }
        stopXrayTunnel()
        runningGeneration = null
        reportStopped(lastGeneration)
    }

    private fun settleStart(failure: TunnelFailure?) {
        val request = inFlight ?: return
        inFlight = null
        if (failure == null) runningGeneration = request.generation
        val next = queued
        queued = null
        when {
            stopWhenSettled -> {
                stopWhenSettled = false
                stopXrayTunnel()
                runningGeneration = null
                reportStopped(request.generation)
            }
            next != null -> launch(next)
            failure == null -> XrayServiceBridge.emit(XrayEngineEvent.Started(request.generation))
            else -> XrayServiceBridge.emit(XrayEngineEvent.Failed(request.generation, failure))
        }
    }

    // A session can fail between publication and the started callback; then this settles the start.
    private fun onFatalError(failure: TunnelFailure) {
        if (inFlight != null) return settleStart(failure)
        val generation = runningGeneration ?: return
        runningGeneration = null
        XrayServiceBridge.emit(XrayEngineEvent.Failed(generation, failure))
    }

    private fun reportStopped(generation: Long) = XrayServiceBridge.emit(XrayEngineEvent.Stopped(generation))

    internal companion object {
        const val ACTION_START = "io.github.aimalygin.xrayrust.kmp.START"
        const val ACTION_STOP = "io.github.aimalygin.xrayrust.kmp.STOP"
    }
}

// Type only, as on iOS and in imports: core and config errors may quote parts of the config.
private fun Throwable.toTunnelFailure(kind: TunnelFailure.Kind): TunnelFailure = TunnelFailure(kind, safeDescription())

internal class XrayStartRequest(val configJson: String, val generation: Long) {
    override fun toString(): String = "XrayStartRequest(generation=$generation, <redacted>)"
}

/** Process-local hand-off between [AndroidTunnelEngine] and the service. */
internal object XrayServiceBridge {
    private val pendingStart = AtomicReference<XrayStartRequest?>(null)
    private val eventFlow = MutableSharedFlow<XrayEngineEvent>(extraBufferCapacity = 16)

    val events: Flow<XrayEngineEvent> = eventFlow.asSharedFlow()

    fun offerStart(request: XrayStartRequest) = pendingStart.set(request)

    fun takeStart(): XrayStartRequest? = pendingStart.getAndSet(null)

    fun emit(event: XrayEngineEvent) {
        eventFlow.tryEmit(event)
    }
}

/** Starts and stops [serviceClass]; VPN consent (`VpnService.prepare`) stays with the app. */
public class AndroidTunnelEngine(
    context: Context,
    private val serviceClass: Class<out XrayKmpVpnService> = XrayKmpVpnService::class.java,
) : XrayTunnelEngine {
    private val context = context.applicationContext

    override val events: Flow<XrayEngineEvent> = XrayServiceBridge.events

    override fun start(configJson: String, generation: Long) {
        XrayServiceBridge.offerStart(XrayStartRequest(configJson, generation))
        context.startService(serviceIntent(XrayKmpVpnService.ACTION_START))
    }

    override fun stop() {
        context.startService(serviceIntent(XrayKmpVpnService.ACTION_STOP))
    }

    // XrayVpnService 0.9.0 neither runs XrayNetworkChangeCoordinator nor exposes its core, so no
    // protocol is rebound here; TunnelPolicy.restartOnNetworkChange decides for all of them.
    override fun rebindsOnNetworkChange(protocol: XrayProtocol): Boolean = false

    private fun serviceIntent(action: String): Intent = Intent(context, serviceClass).setAction(action)
}
