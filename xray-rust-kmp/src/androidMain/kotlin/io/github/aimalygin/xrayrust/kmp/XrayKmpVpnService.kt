package io.github.aimalygin.xrayrust.kmp

import android.content.Context
import android.content.Intent
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
 */
public open class XrayKmpVpnService : XrayVpnService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> XrayServiceBridge.takeConfig()?.let(::restartXrayTunnel)
            ACTION_STOP -> stopTunnelAndSelf()
        }
        return START_NOT_STICKY
    }

    override fun onXrayTunnelStarted(): Unit = XrayServiceBridge.emit(XrayEngineEvent.Started)

    override fun onXrayTunnelStartFailed(error: Throwable): Unit =
        XrayServiceBridge.emit(error.toEngineFailure(TunnelFailure.Kind.StartFailed))

    override fun onXrayTunnelFatalError(error: Throwable): Unit =
        XrayServiceBridge.emit(error.toEngineFailure(TunnelFailure.Kind.Fatal))

    override fun onRevoke() {
        stopTunnelAndSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        super.onDestroy()
        XrayServiceBridge.emit(XrayEngineEvent.Stopped)
    }

    private fun restartXrayTunnel(configJson: String) {
        stopXrayTunnel()
        try {
            startXrayTunnel(configJson)
        } catch (error: RuntimeException) {
            XrayServiceBridge.emit(error.toEngineFailure(TunnelFailure.Kind.StartFailed))
        }
    }

    private fun stopTunnelAndSelf() {
        stopXrayTunnel()
        XrayServiceBridge.emit(XrayEngineEvent.Stopped)
        stopSelf()
    }

    internal companion object {
        const val ACTION_START = "io.github.aimalygin.xrayrust.kmp.START"
        const val ACTION_STOP = "io.github.aimalygin.xrayrust.kmp.STOP"
    }
}

private fun Throwable.toEngineFailure(kind: TunnelFailure.Kind): XrayEngineEvent =
    XrayEngineEvent.Failed(TunnelFailure(kind, "${safeDescription()}: ${message.orEmpty()}"))

/** Process-local hand-off between [AndroidTunnelEngine] and the service. */
internal object XrayServiceBridge {
    private val pendingConfig = AtomicReference<String?>(null)
    private val eventFlow = MutableSharedFlow<XrayEngineEvent>(extraBufferCapacity = 16)

    val events: Flow<XrayEngineEvent> = eventFlow.asSharedFlow()

    fun offerConfig(configJson: String) = pendingConfig.set(configJson)

    fun takeConfig(): String? = pendingConfig.getAndSet(null)

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

    override fun start(configJson: String) {
        XrayServiceBridge.offerConfig(configJson)
        context.startService(serviceIntent(XrayKmpVpnService.ACTION_START))
    }

    override fun stop() {
        context.startService(serviceIntent(XrayKmpVpnService.ACTION_STOP))
    }

    private fun serviceIntent(action: String): Intent = Intent(context, serviceClass).setAction(action)
}
