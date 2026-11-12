package io.github.aimalygin.xrayrust.kmp

/** What [AndroidTunnelEngine] asks the service to do, in the order it asked. */
internal sealed interface XrayServiceCommand {
    class Start(val configJson: String, val generation: Long) : XrayServiceCommand {
        override fun toString(): String = "Start(generation=$generation, <redacted>)"
    }

    data object Stop : XrayServiceCommand
}

/**
 * Orders starts and stops for `XrayVpnService` 0.9.0, which ignores a start issued while an
 * earlier one is still starting and cancels a start in flight without any callback. So a start in
 * flight is never cancelled here: a newer start or a stop waits until it reports, then replaces
 * or stops it. Not thread-safe: the service calls it on the main thread only.
 */
internal class XraySessionSequencer(
    private val startTunnel: (configJson: String) -> Unit,
    private val stopTunnel: () -> Unit,
    private val emit: (XrayEngineEvent) -> Unit,
) {
    private var inFlight: XrayServiceCommand.Start? = null
    private var queued: XrayServiceCommand.Start? = null
    private var stopWhenSettled = false
    private var runningGeneration: Long? = null
    private var lastGeneration = NO_GENERATION

    /** Nothing runs and nothing is wanted, so the service may stop itself. */
    val isIdleOrStopping: Boolean
        get() = queued == null && runningGeneration == null && (inFlight == null || stopWhenSettled)

    fun submit(command: XrayServiceCommand) = when (command) {
        is XrayServiceCommand.Start -> requestStart(command)
        XrayServiceCommand.Stop -> requestStop()
    }

    fun onStarted() = settleStart(failure = null)

    fun onStartFailed(failure: TunnelFailure) = settleStart(failure)

    // A session can fail between publication and the started callback; then this settles the start.
    fun onFatalError(failure: TunnelFailure) {
        if (inFlight != null) return settleStart(failure)
        val generation = runningGeneration ?: return
        runningGeneration = null
        emit(XrayEngineEvent.Failed(generation, failure))
    }

    /** The service is gone, and with it a start in flight or a running session. */
    fun onDestroyed() {
        val unreported = inFlight?.generation ?: runningGeneration
        queued = null
        stopWhenSettled = false
        inFlight = null
        runningGeneration = null
        unreported?.let(::reportStopped)
    }

    private fun requestStart(request: XrayServiceCommand.Start) {
        lastGeneration = request.generation
        stopWhenSettled = false
        if (inFlight == null) launch(request) else queued = request
    }

    private fun launch(request: XrayServiceCommand.Start) {
        // Nothing is starting, so this stops a running session synchronously and the start below is accepted.
        stopTunnel()
        runningGeneration = null
        inFlight = request
        try {
            startTunnel(request.configJson)
        } catch (error: RuntimeException) {
            inFlight = null
            emit(XrayEngineEvent.Failed(request.generation, error.toTunnelFailure(TunnelFailure.Kind.StartFailed)))
        }
    }

    private fun requestStop() {
        queued = null
        if (inFlight != null) {
            stopWhenSettled = true
            return
        }
        stopTunnel()
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
                stopTunnel()
                runningGeneration = null
                reportStopped(request.generation)
            }
            next != null -> launch(next)
            failure == null -> emit(XrayEngineEvent.Started(request.generation))
            else -> emit(XrayEngineEvent.Failed(request.generation, failure))
        }
    }

    private fun reportStopped(generation: Long) = emit(XrayEngineEvent.Stopped(generation))
}
