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
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class XrayTunnelTest {
    private class FakeEngine : XrayTunnelEngine {
        private val eventFlow = MutableSharedFlow<XrayEngineEvent>(extraBufferCapacity = 8)
        override val events: Flow<XrayEngineEvent> = eventFlow
        val started: List<String>
            field = mutableListOf()
        var stops = 0
            private set

        fun emit(event: XrayEngineEvent) = eventFlow.tryEmit(event)

        override fun start(configJson: String) {
            started += configJson
        }

        override fun stop() {
            stops++
        }
    }

    private val failure = XrayEngineEvent.Failed(TunnelFailure(TunnelFailure.Kind.StartFailed, "boom"))

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
        send(engine, XrayEngineEvent.Started)
        assertEquals(TunnelState.Connected(XrayProtocol.Trojan), tunnel.state.value)
        tunnel.stop()
        runCurrent()
        assertEquals(TunnelState.Stopping, tunnel.state.value)
        send(engine, XrayEngineEvent.Stopped)
        assertEquals(TunnelState.Idle, tunnel.state.value)
        assertEquals(1, engine.stops)
    }

    @Test
    fun retriesWithBackoffThenFails() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        val tunnel = tunnel(engine, TunnelPolicy(maxStartAttempts = 2))
        tunnel.start(profile(XrayProtocol.Vmess))
        runCurrent()
        send(engine, failure)
        assertEquals(TunnelState.Connecting(attempt = 2), tunnel.state.value)
        assertEquals(1, engine.started.size)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(2, engine.started.size)
        send(engine, failure)
        assertEquals(TunnelState.Failed(failure.failure), tunnel.state.value)
        assertEquals(1, engine.stops)
    }

    @Test
    fun fallbackStartsWithoutRoutingOnlyWhenOptedIn() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        val tunnel = tunnel(engine, TunnelPolicy(maxStartAttempts = 1, fallbackWithoutRouting = true))
        tunnel.start(profile(XrayProtocol.Vless))
        runCurrent()
        send(engine, failure)
        assertEquals(TunnelState.Connecting(attempt = 1, withoutRouting = true), tunnel.state.value)
        assertEquals("{}", engine.started.last())
        send(engine, XrayEngineEvent.Started)
        assertEquals(TunnelState.Connected(XrayProtocol.Vless, withoutRouting = true), tunnel.state.value)
    }

    @Test
    fun networkChangeRestartsOnlyWhenPolicyAllows() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        val tunnel = tunnel(engine, TunnelPolicy(restartOnNetworkChange = true))
        tunnel.start(profile(XrayProtocol.Hysteria2))
        runCurrent()
        send(engine, XrayEngineEvent.Started)
        tunnel.onNetworkChanged()
        runCurrent()
        assertEquals(1, engine.started.size)
        tunnel.start(profile(XrayProtocol.Trojan))
        runCurrent()
        send(engine, XrayEngineEvent.Started)
        tunnel.onNetworkChanged()
        runCurrent()
        assertEquals(3, engine.started.size)
    }

    @Test
    fun revokedTunnelBecomesIdle() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        val tunnel = tunnel(engine)
        tunnel.start(profile(XrayProtocol.Socks))
        runCurrent()
        send(engine, XrayEngineEvent.Started)
        send(engine, XrayEngineEvent.Stopped)
        assertEquals(TunnelState.Idle, tunnel.state.value)
    }
}
