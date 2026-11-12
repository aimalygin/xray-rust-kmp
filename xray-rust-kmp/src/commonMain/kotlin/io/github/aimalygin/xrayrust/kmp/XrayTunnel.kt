package io.github.aimalygin.xrayrust.kmp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

public sealed interface TunnelState {
    public data object Idle : TunnelState

    public data class Connecting(val attempt: Int, val withoutRouting: Boolean = false) : TunnelState

    /** [protocol] is `null` for a tunnel found already running (see [XrayEngineEvent.EXTERNAL_SESSION]). */
    public data class Connected(val protocol: XrayProtocol?, val withoutRouting: Boolean = false) : TunnelState

    public data class Failed(val failure: TunnelFailure) : TunnelState

    public data object Stopping : TunnelState
}

/**
 * [message] is safe to show and log: an error type or a system error domain, never core or config
 * text. [code] is the native status (`XrayStatus` on Android, `NSError.code` on iOS) when there is one.
 */
public data class TunnelFailure(val kind: Kind, val message: String, val code: Int? = null) {
    public enum class Kind {
        StartFailed,

        /** The engine did not report within [TunnelPolicy.startTimeout]. */
        StartTimeout,

        /** The core refused the config, so the same config is not retried. */
        ConfigRejected,

        /** The system VPN permission is missing: ask the user, then start again. Never retried. */
        PermissionRequired,

        /** A running tunnel went down on its own. */
        Fatal,
    }
}

/**
 * Engine report about the session started by `XrayTunnelEngine.start(configJson, generation)`.
 * [generation] lets the controller drop late events of a session it has already replaced.
 */
public sealed interface XrayEngineEvent {
    public val generation: Long

    public data class Started(override val generation: Long) : XrayEngineEvent

    public data class Failed(override val generation: Long, val failure: TunnelFailure) : XrayEngineEvent

    /** The platform tunnel is down: stopped on request, revoked by the system or destroyed. */
    public data class Stopped(override val generation: Long) : XrayEngineEvent

    public companion object {
        /**
         * Generation of a session the engine did not start itself: the tunnel was already up when
         * the app launched, or the user switched it on in the system settings. The engine reports
         * it as [Started] with this generation and the controller adopts it, so it can be stopped.
         */
        public const val EXTERNAL_SESSION: Long = 0
    }
}

/**
 * Platform side of a tunnel: Android drives `XrayVpnService`, iOS drives the Network Extension
 * built on `XrayPacketTunnelProvider`. [start] on a running engine replaces its session.
 * The controller calls an engine only from its own scope; the bundled engines expect the main thread.
 */
public interface XrayTunnelEngine {
    public val events: Flow<XrayEngineEvent>

    /** Starts [configJson]; every event about this session carries [generation]. */
    public fun start(configJson: String, generation: Long)

    /** Stops the current session and reports one [XrayEngineEvent.Stopped], also when nothing runs. */
    public fun stop()

    /** Whether this platform's wrapper rebinds [protocol] on a network change without a core restart. */
    public fun rebindsOnNetworkChange(protocol: XrayProtocol): Boolean
}

/**
 * Tunnel controller: one state machine for every platform. Commands are queued and handled in
 * order on [scope]; the policy decides retries, restart and fallback.
 */
public class XrayTunnel(
    private val engine: XrayTunnelEngine,
    private val scope: CoroutineScope,
    public val policy: TunnelPolicy = TunnelPolicy(),
) {
    public val state: StateFlow<TunnelState>
        field = MutableStateFlow<TunnelState>(TunnelState.Idle)

    private val inputs = Channel<Input>(Channel.UNLIMITED)
    private var session: Session? = null

    // Either the wait before a retry or the start timeout of the attempt in flight, never both.
    private var timer: Job? = null
    private var lastGeneration = XrayEngineEvent.EXTERNAL_SESSION

    init {
        engine.events.onEach { inputs.send(Input.Engine(it)) }.launchIn(scope)
        scope.launch { for (input in inputs) handle(input) }
    }

    public fun start(profile: XrayProfile) {
        inputs.trySend(Input.Start(profile))
    }

    public fun stop() {
        inputs.trySend(Input.Stop)
    }

    /**
     * Report an underlying network change. Protocols with rebind are left to the platform
     * wrapper; others restart only when [TunnelPolicy.restartOnNetworkChange] allows it.
     */
    public fun onNetworkChanged() {
        inputs.trySend(Input.NetworkChanged)
    }

    private fun handle(input: Input) {
        when (input) {
            is Input.Start -> launchAttempt(Session(input.profile), attempt = 1, withoutRouting = false)
            Input.Stop -> stopSession()
            Input.NetworkChanged -> restartIfPolicyAllows()
            is Input.RetryDue -> if (input.session === session) {
                launchAttempt(input.session, input.attempt, input.session.withoutRouting)
            }
            is Input.StartTimedOut -> onStartTimeout(input.generation)
            is Input.Engine -> onEngineEvent(input.event)
        }
    }

    private fun onEngineEvent(event: XrayEngineEvent) {
        val current = session?.takeIf { it.generation == event.generation }
        when (event) {
            is XrayEngineEvent.Started -> when {
                current != null -> onStarted(current)
                isExternalStart(event) -> onStarted(Session(profile = null).apply { generation = event.generation })
            }
            is XrayEngineEvent.Failed -> if (current != null) onFailure(current, event.failure)
            // A Stopped of a replaced session must not end the newer one.
            is XrayEngineEvent.Stopped -> if (current != null || (session == null && state.value is TunnelState.Stopping)) {
                timer?.cancel()
                session = null
                state.value = TunnelState.Idle
            }
        }
    }

    private fun isExternalStart(event: XrayEngineEvent.Started): Boolean =
        event.generation == XrayEngineEvent.EXTERNAL_SESSION && session == null && state.value !is TunnelState.Stopping

    private fun onStarted(current: Session) {
        timer?.cancel()
        session = current
        current.attempt = 1
        state.value = TunnelState.Connected(current.profile?.protocol, current.withoutRouting)
    }

    private fun onFailure(current: Session, failure: TunnelFailure) {
        timer?.cancel()
        // Late reports of the failed attempt must not touch whatever follows it.
        current.generation = NO_GENERATION
        // An adopted session has no profile to start again.
        val decision = when (current.profile) {
            null -> FailureDecision.GiveUp
            else -> policy.onFailure(current.attempt, current.withoutRouting, failure.kind)
        }
        when (decision) {
            is FailureDecision.Retry -> {
                state.value = TunnelState.Connecting(decision.attempt, current.withoutRouting)
                timer = scope.launch {
                    delay(decision.delay)
                    inputs.send(Input.RetryDue(current, decision.attempt))
                }
            }
            FailureDecision.FallbackWithoutRouting ->
                launchAttempt(current, attempt = policy.maxStartAttempts, withoutRouting = true)
            FailureDecision.GiveUp -> {
                session = null
                state.value = TunnelState.Failed(failure)
                stopEngine()
            }
        }
    }

    private fun onStartTimeout(generation: Long) {
        val current = session?.takeIf { it.generation == generation } ?: return
        if (state.value !is TunnelState.Connecting) return
        // The silent attempt is abandoned, so the engine is free for the next one.
        stopEngine()
        onFailure(current, TunnelFailure(TunnelFailure.Kind.StartTimeout, "start timed out"))
    }

    private fun restartIfPolicyAllows() {
        val current = session ?: return
        val protocol = current.profile?.protocol ?: return
        if (state.value !is TunnelState.Connected) return
        when (policy.onNetworkChange(rebinds = engine.rebindsOnNetworkChange(protocol))) {
            NetworkChangeDecision.Restart -> launchAttempt(current, attempt = 1, withoutRouting = current.withoutRouting)
            NetworkChangeDecision.LeaveToWrapper, NetworkChangeDecision.Ignore -> Unit
        }
    }

    private fun stopSession() {
        timer?.cancel()
        if (session == null) return
        session = null
        state.value = TunnelState.Stopping
        stopEngine()
    }

    // An engine that cannot even be asked to stop has nothing left to report.
    private fun stopEngine() {
        try {
            engine.stop()
        } catch (_: RuntimeException) {
            if (state.value is TunnelState.Stopping) state.value = TunnelState.Idle
        }
    }

    private fun launchAttempt(target: Session, attempt: Int, withoutRouting: Boolean) {
        val json = target.profile?.configJson ?: return
        val generation = ++lastGeneration
        timer?.cancel()
        session = target
        target.attempt = attempt
        target.withoutRouting = withoutRouting
        target.generation = generation
        state.value = TunnelState.Connecting(attempt, withoutRouting)
        try {
            engine.start(if (withoutRouting) configWithoutRouting(json) else json, generation)
        } catch (error: RuntimeException) {
            return onFailure(target, TunnelFailure(TunnelFailure.Kind.StartFailed, error.safeDescription()))
        }
        timer = scope.launch {
            delay(policy.startTimeout)
            inputs.send(Input.StartTimedOut(generation))
        }
    }

    private class Session(val profile: XrayProfile?) {
        var attempt = 1
        var withoutRouting = false
        var generation = NO_GENERATION
    }

    private sealed interface Input {
        data class Start(val profile: XrayProfile) : Input

        data object Stop : Input

        data object NetworkChanged : Input

        data class RetryDue(val session: Session, val attempt: Int) : Input

        data class StartTimedOut(val generation: Long) : Input

        data class Engine(val event: XrayEngineEvent) : Input
    }
}

/** No session: a generation no engine event carries. */
internal const val NO_GENERATION: Long = -1

/** Drops the `routing` section: every connection then goes through the first outbound. */
internal fun configWithoutRouting(configJson: String): String =
    JsonObject(Json.parseToJsonElement(configJson).jsonObject - "routing").toString()

internal fun Throwable.safeDescription(): String = this::class.simpleName ?: "error"
