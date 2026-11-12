package io.github.aimalygin.xrayrust.kmp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class XrayTunnelTest {
    private class FakeEngine(private val rebound: Set<XrayProtocol> = emptySet()) : XrayTunnelEngine {
        private val eventFlow = MutableSharedFlow<XrayEngineEvent>(extraBufferCapacity = 8)
        override val events: Flow<XrayEngineEvent> = eventFlow
        val started: List<String>
            field = mutableListOf()
        var generation = 0L
            private set
        var stops = 0
            private set
        var stopFails = false

        fun emit(event: XrayEngineEvent) = eventFlow.tryEmit(event)

        override fun start(configJson: String, generation: Long) {
            started += configJson
            this.generation = generation
        }

        override fun stop() {
            stops++
            check(!stopFails) { "stop refused" }
        }

        override fun rebindsOnNetworkChange(protocol: XrayProtocol): Boolean = protocol in rebound
    }

    private val boom = TunnelFailure(TunnelFailure.Kind.StartFailed, "boom")

    private fun FakeEngine.started() = XrayEngineEvent.Started(generation)

    private fun FakeEngine.stopped() = XrayEngineEvent.Stopped(generation)

    private fun FakeEngine.failed() = XrayEngineEvent.Failed(generation, boom)

    private fun TestScope.tunnel(engine: FakeEngine, policy: TunnelPolicy = TunnelPolicy()): XrayTunnel =
        XrayTunnel(engine, backgroundScope, policy).also { runCurrent() }

    private fun TestScope.send(engine: FakeEngine, event: XrayEngineEvent) {
        engine.emit(event)
        runCurrent()
    }

    @Test
    fun startConnectsAndStopReturnsToIdle() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        val tunnel = tunnel(engine)
        tunnel.start(profile(XrayProtocol.Trojan))
        runCurrent()
        assertEquals(TunnelState.Connecting(attempt = 1), tunnel.state.value)
        send(engine, engine.started())
        assertEquals(TunnelState.Connected(XrayProtocol.Trojan), tunnel.state.value)
        tunnel.stop()
        runCurrent()
        assertEquals(TunnelState.Stopping, tunnel.state.value)
        send(engine, engine.stopped())
        assertEquals(TunnelState.Idle, tunnel.state.value)
        assertEquals(1, engine.stops)
    }

    @Test
    fun retriesWithBackoffThenFails() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        val tunnel = tunnel(engine, TunnelPolicy(maxStartAttempts = 2))
        tunnel.start(profile(XrayProtocol.Vmess))
        runCurrent()
        send(engine, engine.failed())
        assertEquals(TunnelState.Connecting(attempt = 2), tunnel.state.value)
        assertEquals(1, engine.started.size)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(2, engine.started.size)
        send(engine, engine.failed())
        assertEquals(TunnelState.Failed(boom), tunnel.state.value)
        assertEquals(1, engine.stops)
    }

    @Test
    fun fallbackStartsWithoutRoutingOnlyWhenOptedIn() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        val tunnel = tunnel(engine, TunnelPolicy(maxStartAttempts = 1, fallbackWithoutRouting = true))
        tunnel.start(profile(XrayProtocol.Vless))
        runCurrent()
        send(engine, engine.failed())
        assertEquals(TunnelState.Connecting(attempt = 1, withoutRouting = true), tunnel.state.value)
        assertEquals("{}", engine.started.last())
        send(engine, engine.started())
        assertEquals(TunnelState.Connected(XrayProtocol.Vless, withoutRouting = true), tunnel.state.value)
    }

    @Test
    fun networkChangeRestartsOnlyWhenPolicyAllows() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine(rebound = setOf(XrayProtocol.Hysteria2))
        val tunnel = tunnel(engine, TunnelPolicy(restartOnNetworkChange = true))
        tunnel.start(profile(XrayProtocol.Hysteria2))
        runCurrent()
        send(engine, engine.started())
        tunnel.onNetworkChanged()
        runCurrent()
        assertEquals(1, engine.started.size)
        tunnel.start(profile(XrayProtocol.Trojan))
        runCurrent()
        send(engine, engine.started())
        tunnel.onNetworkChanged()
        runCurrent()
        assertEquals(3, engine.started.size)
    }

    @Test
    fun networkChangeRestartsUnreboundHysteria2WhenOptedIn() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        val tunnel = tunnel(engine, TunnelPolicy(restartOnNetworkChange = true))
        tunnel.start(profile(XrayProtocol.Hysteria2))
        runCurrent()
        send(engine, engine.started())
        tunnel.onNetworkChanged()
        runCurrent()
        assertEquals(2, engine.started.size)
    }

    @Test
    fun stoppedOfReplacedSessionDoesNotEndNewOne() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        val tunnel = tunnel(engine)
        tunnel.start(profile(XrayProtocol.Trojan))
        runCurrent()
        send(engine, engine.started())
        val old = engine.stopped()
        tunnel.stop()
        tunnel.start(profile(XrayProtocol.Trojan))
        runCurrent()
        send(engine, old)
        assertEquals(TunnelState.Connecting(attempt = 1), tunnel.state.value)
        send(engine, engine.started())
        assertEquals(TunnelState.Connected(XrayProtocol.Trojan), tunnel.state.value)
    }

    @Test
    fun lateFailureOfEarlierAttemptIsIgnored() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        val tunnel = tunnel(engine)
        tunnel.start(profile(XrayProtocol.Vmess))
        runCurrent()
        val stale = engine.failed()
        tunnel.start(profile(XrayProtocol.Vmess))
        runCurrent()
        send(engine, stale)
        assertEquals(TunnelState.Connecting(attempt = 1), tunnel.state.value)
    }

    @Test
    fun silentStartTimesOutAndIsRetried() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        val tunnel = tunnel(engine, TunnelPolicy(maxStartAttempts = 2, startTimeout = 5.seconds))
        tunnel.start(profile(XrayProtocol.Trojan))
        runCurrent()
        val abandoned = engine.started()
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(TunnelState.Connecting(attempt = 2), tunnel.state.value)
        assertEquals(1, engine.stops)
        send(engine, abandoned)
        assertEquals(TunnelState.Connecting(attempt = 2), tunnel.state.value)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(2, engine.started.size)
        advanceTimeBy(5.seconds)
        runCurrent()
        val timeout = TunnelFailure(TunnelFailure.Kind.StartTimeout, "start timed out")
        assertEquals(TunnelState.Failed(timeout), tunnel.state.value)
    }

    @Test
    fun startedTunnelIsNotTimedOut() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        val tunnel = tunnel(engine, TunnelPolicy(startTimeout = 5.seconds))
        tunnel.start(profile(XrayProtocol.Trojan))
        runCurrent()
        send(engine, engine.started())
        advanceTimeBy(6.seconds)
        runCurrent()
        assertEquals(TunnelState.Connected(XrayProtocol.Trojan), tunnel.state.value)
        assertEquals(0, engine.stops)
    }

    @Test
    fun externalSessionIsAdoptedAndCanBeStopped() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        val tunnel = tunnel(engine, TunnelPolicy(restartOnNetworkChange = true))
        send(engine, XrayEngineEvent.Started(XrayEngineEvent.EXTERNAL_SESSION))
        assertEquals(TunnelState.Connected(protocol = null), tunnel.state.value)
        tunnel.onNetworkChanged()
        runCurrent()
        assertEquals(0, engine.started.size)
        tunnel.stop()
        runCurrent()
        assertEquals(TunnelState.Stopping, tunnel.state.value)
        assertEquals(1, engine.stops)
        send(engine, XrayEngineEvent.Stopped(XrayEngineEvent.EXTERNAL_SESSION))
        assertEquals(TunnelState.Idle, tunnel.state.value)
    }

    @Test
    fun externalSessionThatFailsIsNotRestarted() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        val tunnel = tunnel(engine)
        send(engine, XrayEngineEvent.Started(XrayEngineEvent.EXTERNAL_SESSION))
        val fatal = TunnelFailure(TunnelFailure.Kind.Fatal, "boom")
        send(engine, XrayEngineEvent.Failed(XrayEngineEvent.EXTERNAL_SESSION, fatal))
        assertEquals(TunnelState.Failed(fatal), tunnel.state.value)
        assertEquals(0, engine.started.size)
    }

    @Test
    fun externalStartDoesNotReplaceOwnAttempt() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        val tunnel = tunnel(engine)
        tunnel.start(profile(XrayProtocol.Trojan))
        runCurrent()
        send(engine, XrayEngineEvent.Started(XrayEngineEvent.EXTERNAL_SESSION))
        assertEquals(TunnelState.Connecting(attempt = 1), tunnel.state.value)
    }

    @Test
    fun missingPermissionIsNotRetried() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        val tunnel = tunnel(engine)
        tunnel.start(profile(XrayProtocol.Trojan))
        runCurrent()
        val denied = TunnelFailure(TunnelFailure.Kind.PermissionRequired, "no consent")
        send(engine, XrayEngineEvent.Failed(engine.generation, denied))
        assertEquals(TunnelState.Failed(denied), tunnel.state.value)
        assertEquals(1, engine.started.size)
    }

    @Test
    fun rejectedConfigSkipsRetriesAndGoesToFallback() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        val tunnel = tunnel(engine, TunnelPolicy(fallbackWithoutRouting = true))
        tunnel.start(profile(XrayProtocol.Trojan))
        runCurrent()
        val rejected = TunnelFailure(TunnelFailure.Kind.ConfigRejected, "XrayCoreException", code = 3)
        send(engine, XrayEngineEvent.Failed(engine.generation, rejected))
        assertEquals(TunnelState.Connecting(attempt = 3, withoutRouting = true), tunnel.state.value)
        send(engine, XrayEngineEvent.Failed(engine.generation, rejected))
        assertEquals(TunnelState.Failed(rejected), tunnel.state.value)
    }

    @Test
    fun engineThatCannotStopLeavesTunnelIdle() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        val tunnel = tunnel(engine)
        tunnel.start(profile(XrayProtocol.Trojan))
        runCurrent()
        send(engine, engine.started())
        engine.stopFails = true
        tunnel.stop()
        runCurrent()
        assertEquals(TunnelState.Idle, tunnel.state.value)
        tunnel.start(profile(XrayProtocol.Trojan))
        runCurrent()
        assertEquals(TunnelState.Connecting(attempt = 1), tunnel.state.value)
    }

    @Test
    fun unparsableConfigFailsWithoutBreakingTheController() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        val tunnel = tunnel(engine, TunnelPolicy(maxStartAttempts = 1, fallbackWithoutRouting = true))
        tunnel.start(XrayProfile("broken", "vpn.example.com", XrayProtocol.Trojan, configJson = "[]"))
        runCurrent()
        send(engine, engine.failed())
        assertIs<TunnelState.Failed>(tunnel.state.value)
        tunnel.start(profile(XrayProtocol.Trojan))
        runCurrent()
        assertEquals(TunnelState.Connecting(attempt = 1), tunnel.state.value)
    }

    @Test
    fun revokedTunnelBecomesIdle() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        val tunnel = tunnel(engine)
        tunnel.start(profile(XrayProtocol.Socks))
        runCurrent()
        send(engine, engine.started())
        send(engine, engine.stopped())
        assertEquals(TunnelState.Idle, tunnel.state.value)
    }
}
