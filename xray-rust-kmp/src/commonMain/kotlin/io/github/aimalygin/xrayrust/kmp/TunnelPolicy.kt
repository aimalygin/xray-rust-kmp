package io.github.aimalygin.xrayrust.kmp

import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * App policy for retries, restart and fallback. The library only applies it; the defaults are
 * conservative: a few spaced start attempts, no restart on network change, no fallback.
 */
public data class TunnelPolicy(
    /** Start attempts per [XrayTunnel.start] or per failure of a running tunnel, including the first. */
    val maxStartAttempts: Int = 3,
    /**
     * How long one start attempt may stay silent. After that it counts as a failed attempt
     * ([TunnelFailure.Kind.StartTimeout]) instead of leaving the tunnel in `Connecting` forever.
     */
    val startTimeout: Duration = 60.seconds,
    val initialBackoff: Duration = 1.seconds,
    val backoffMultiplier: Double = 2.0,
    val maxBackoff: Duration = 30.seconds,
    /**
     * Restart the core on a network change when the platform wrapper cannot rebind the protocol
     * (see [XrayTunnelEngine.rebindsOnNetworkChange]). Restarting drops every flow.
     */
    val restartOnNetworkChange: Boolean = false,
    /**
     * After all attempts fail, try once more with the `routing` section removed. This changes where
     * traffic goes (everything through the first outbound), so it is an explicit opt-in.
     */
    val fallbackWithoutRouting: Boolean = false,
) {
    init {
        require(maxStartAttempts >= 1) { "maxStartAttempts must be at least 1" }
        require(startTimeout.isPositive()) { "startTimeout must be positive" }
        require(backoffMultiplier >= 1.0) { "backoffMultiplier must be at least 1" }
        require(initialBackoff.isPositive() && maxBackoff >= initialBackoff) { "backoff bounds are invalid" }
    }

    /** Delay before start attempt [attempt] (2 is the first retry). */
    internal fun backoffBefore(attempt: Int): Duration {
        require(attempt >= 2) { "attempt $attempt has no backoff" }
        return minOf(initialBackoff * backoffMultiplier.pow(attempt - 2), maxBackoff)
    }

    /** A missing permission ends the start; a rejected config skips the retries of that same config. */
    internal fun onFailure(attempt: Int, withoutRouting: Boolean, kind: TunnelFailure.Kind): FailureDecision = when {
        kind == TunnelFailure.Kind.PermissionRequired -> FailureDecision.GiveUp
        kind != TunnelFailure.Kind.ConfigRejected && attempt < maxStartAttempts ->
            FailureDecision.Retry(attempt + 1, backoffBefore(attempt + 1))
        fallbackWithoutRouting && !withoutRouting -> FailureDecision.FallbackWithoutRouting
        else -> FailureDecision.GiveUp
    }

    internal fun onNetworkChange(rebinds: Boolean): NetworkChangeDecision = when {
        rebinds -> NetworkChangeDecision.LeaveToWrapper
        restartOnNetworkChange -> NetworkChangeDecision.Restart
        else -> NetworkChangeDecision.Ignore
    }
}

internal sealed interface FailureDecision {
    data class Retry(val attempt: Int, val delay: Duration) : FailureDecision

    data object FallbackWithoutRouting : FailureDecision

    data object GiveUp : FailureDecision
}

internal enum class NetworkChangeDecision { LeaveToWrapper, Restart, Ignore }
