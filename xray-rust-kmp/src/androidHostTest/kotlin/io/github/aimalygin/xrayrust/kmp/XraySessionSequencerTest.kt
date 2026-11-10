package io.github.aimalygin.xrayrust.kmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XraySessionSequencerTest {
    private val calls = mutableListOf<String>()
    private val events = mutableListOf<XrayEngineEvent>()
    private val sequencer = XraySessionSequencer(
        startTunnel = { calls += "start $it" },
        stopTunnel = { calls += "stop" },
        emit = { events += it },
    )
    private val boom = TunnelFailure(TunnelFailure.Kind.Fatal, "boom")

    private fun start(generation: Long) = XrayServiceCommand.Start("config$generation", generation)

    @Test
    fun startStopStartRunsTheNewerStart() {
        sequencer.submit(start(1))
        sequencer.submit(XrayServiceCommand.Stop)
        sequencer.submit(start(2))
        assertEquals(listOf("stop", "start config1"), calls)
        assertFalse(sequencer.isIdleOrStopping)
        sequencer.onStarted()
        assertEquals(listOf("stop", "start config1", "stop", "start config2"), calls)
        assertEquals(emptyList(), events)
        sequencer.onStarted()
        assertEquals(listOf<XrayEngineEvent>(XrayEngineEvent.Started(2)), events)
    }

    @Test
    fun stopDuringStartWaitsForItThenStops() {
        sequencer.submit(start(1))
        sequencer.submit(XrayServiceCommand.Stop)
        assertTrue(sequencer.isIdleOrStopping)
        assertEquals(emptyList(), events)
        sequencer.onStarted()
        assertEquals("stop", calls.last())
        assertEquals(listOf<XrayEngineEvent>(XrayEngineEvent.Stopped(1)), events)
    }

    @Test
    fun newerStartReplacesTheQueuedOne() {
        sequencer.submit(start(1))
        sequencer.submit(start(2))
        sequencer.submit(start(3))
        sequencer.onStartFailed(boom)
        assertEquals("start config3", calls.last())
        sequencer.onStarted()
        assertEquals(listOf<XrayEngineEvent>(XrayEngineEvent.Started(3)), events)
    }

    @Test
    fun stopOfRunningSessionReportsStopped() {
        sequencer.submit(start(1))
        sequencer.onStarted()
        assertFalse(sequencer.isIdleOrStopping)
        sequencer.submit(XrayServiceCommand.Stop)
        assertEquals(listOf(XrayEngineEvent.Started(1), XrayEngineEvent.Stopped(1)), events)
        assertTrue(sequencer.isIdleOrStopping)
    }

    @Test
    fun stopWithNothingRunningStillReports() {
        sequencer.submit(XrayServiceCommand.Stop)
        assertEquals(listOf<XrayEngineEvent>(XrayEngineEvent.Stopped(NO_GENERATION)), events)
    }

    @Test
    fun fatalErrorOfRunningSessionIsReportedOnce() {
        sequencer.submit(start(1))
        sequencer.onStarted()
        sequencer.onFatalError(boom)
        sequencer.onFatalError(boom)
        assertEquals(listOf(XrayEngineEvent.Started(1), XrayEngineEvent.Failed(1, boom)), events)
        assertTrue(sequencer.isIdleOrStopping)
    }

    @Test
    fun fatalErrorBeforeStartedCallbackSettlesTheStart() {
        sequencer.submit(start(1))
        sequencer.onFatalError(boom)
        sequencer.onStarted()
        assertEquals(listOf<XrayEngineEvent>(XrayEngineEvent.Failed(1, boom)), events)
    }

    @Test
    fun destroyReportsStartInFlightAndRunningSession() {
        sequencer.submit(start(1))
        sequencer.onDestroyed()
        sequencer.submit(start(2))
        sequencer.onStarted()
        sequencer.onDestroyed()
        sequencer.onDestroyed()
        val expected = listOf(XrayEngineEvent.Stopped(1), XrayEngineEvent.Started(2), XrayEngineEvent.Stopped(2))
        assertEquals(expected, events)
    }

    @Test
    fun startThatThrowsIsReportedAndFreesTheQueue() {
        val refusing = XraySessionSequencer(
            startTunnel = { error("refused") },
            stopTunnel = {},
            emit = { events += it },
        )
        refusing.submit(start(1))
        val failure = TunnelFailure(TunnelFailure.Kind.StartFailed, "IllegalStateException")
        assertEquals(listOf<XrayEngineEvent>(XrayEngineEvent.Failed(1, failure)), events)
        assertTrue(refusing.isIdleOrStopping)
    }
}
