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

    public data class Connected(val protocol: XrayProtocol, val withoutRouting: Boolean = false) : TunnelState

    public data class Failed(val failure: TunnelFailure) : TunnelState

    public data object Stopping : TunnelState
}

public data class TunnelFailure(val kind: Kind, val message: String) {
    public enum class Kind { StartFailed, Fatal }
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
}

/**
 * Platform side of a tunnel: Android drives `XrayVpnService`, iOS drives the Network Extension
 * built on `XrayPacketTunnelProvider`. [start] on a running engine replaces its session.
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
    private var retryJob: Job? = null
    private var lastGeneration = 0L

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
            is Input.Start -> {
                retryJob?.cancel()
                launchAttempt(Session(input.profile), attempt = 1, withoutRouting = false)
            }
            Input.Stop -> stopSession()
            Input.NetworkChanged -> restartIfPolicyAllows()
            is Input.RetryDue -> if (input.session === session) {
                launchAttempt(input.session, input.attempt, input.session.withoutRouting)
            }
            is Input.Engine -> onEngineEvent(input.event)
        }
    }

    private fun onEngineEvent(event: XrayEngineEvent) {
        val current = session?.takeIf { it.generation == event.generation }
        when (event) {
            is XrayEngineEvent.Started -> if (current != null) {
                current.attempt = 1
                state.value = TunnelState.Connected(current.profile.protocol, current.withoutRouting)
            }
            is XrayEngineEvent.Failed -> if (current != null) onFailure(current, event.failure)
            // A Stopped of a replaced session must not end the newer one.
            is XrayEngineEvent.Stopped -> if (current != null || (session == null && state.value is TunnelState.Stopping)) {
                retryJob?.cancel()
                session = null
                state.value = TunnelState.Idle
            }
        }
    }

    private fun onFailure(current: Session, failure: TunnelFailure) {
        when (val decision = policy.onFailure(current.attempt, current.withoutRouting)) {
            is FailureDecision.Retry -> {
                state.value = TunnelState.Connecting(decision.attempt, current.withoutRouting)
                retryJob = scope.launch {
                    delay(decision.delay)
                    inputs.send(Input.RetryDue(current, decision.attempt))
                }
            }
            FailureDecision.FallbackWithoutRouting ->
                launchAttempt(current, attempt = policy.maxStartAttempts, withoutRouting = true)
            FailureDecision.GiveUp -> {
                session = null
                state.value = TunnelState.Failed(failure)
                engine.stop()
            }
        }
    }

    private fun restartIfPolicyAllows() {
        val current = session ?: return
        if (state.value !is TunnelState.Connected) return
        when (policy.onNetworkChange(rebinds = engine.rebindsOnNetworkChange(current.profile.protocol))) {
            NetworkChangeDecision.Restart -> launchAttempt(current, attempt = 1, withoutRouting = current.withoutRouting)
            NetworkChangeDecision.LeaveToWrapper, NetworkChangeDecision.Ignore -> Unit
        }
    }

    private fun stopSession() {
        retryJob?.cancel()
        if (session == null && state.value !is TunnelState.Connecting) return
        session = null
        state.value = TunnelState.Stopping
        engine.stop()
    }

    private fun launchAttempt(target: Session, attempt: Int, withoutRouting: Boolean) {
        session = target
        target.attempt = attempt
        target.withoutRouting = withoutRouting
        target.generation = ++lastGeneration
        state.value = TunnelState.Connecting(attempt, withoutRouting)
        val json = target.profile.configJson
        val config = if (withoutRouting) configWithoutRouting(json) else json
        try {
            engine.start(config, target.generation)
        } catch (error: RuntimeException) {
            onFailure(target, TunnelFailure(TunnelFailure.Kind.StartFailed, error.safeDescription()))
        }
    }

    private class Session(val profile: XrayProfile) {
        var attempt = 1
        var withoutRouting = false
        var generation = 0L
    }

    private sealed interface Input {
        data class Start(val profile: XrayProfile) : Input

        data object Stop : Input

        data object NetworkChanged : Input

        data class RetryDue(val session: Session, val attempt: Int) : Input

        data class Engine(val event: XrayEngineEvent) : Input
    }
}

/** Drops the `routing` section: every connection then goes through the first outbound. */
internal fun configWithoutRouting(configJson: String): String =
    JsonObject(Json.parseToJsonElement(configJson).jsonObject - "routing").toString()

internal fun Throwable.safeDescription(): String = this::class.simpleName ?: "error"
