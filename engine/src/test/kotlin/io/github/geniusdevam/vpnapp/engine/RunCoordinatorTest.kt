package io.github.geniusdevam.vpnapp.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/** Mirrors Session: a run connects, and on cancellation restores the previous server only if the coordinator allows. */
class RunCoordinatorTest {
    private class Recorder : TunnelController {
        override var currentServerId: String? = null
        val events = ArrayList<String>()
        private var now: () -> Long = { 0 }
        fun clock(f: () -> Long) { now = f }
        override suspend fun connect(server: Server, timeoutMs: Long): ConnectResult {
            events.add("connect:${server.id}@${now()}")
            delay(2_000)
            currentServerId = server.id
            events.add("connected:${server.id}@${now()}")
            return ConnectResult.connected("AES-256-GCM")
        }
        override suspend fun abort() { events.add("abort") }
        override suspend fun disconnect() { events.add("disconnect@${now()}"); currentServerId = null }
    }

    private fun TestScope.setup(): Triple<RunCoordinator, Recorder, CoroutineScope> {
        val rec = Recorder().also { r -> r.clock { testScheduler.currentTime } }
        val sc = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        return Triple(RunCoordinator(sc, rec), rec, sc)
    }

    private val prev = srv("prev")
    private val next = srv("next")

    /** The Session run body: connect `next`, restore `prev` on cancellation only if allowed. */
    private fun body(rec: Recorder, previous: Server?): suspend (RunCoordinator.Handle) -> Unit = { h ->
        try {
            rec.connect(next, 10_000)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                rec.abort()
                if (h.mayRestore() && previous != null) rec.connect(previous, 10_000)
            }
            throw e
        }
    }

    @Test fun cancelRestoresPreviousConnection() = runTest {
        val (c, rec, _) = setup()
        rec.currentServerId = "prev"
        c.start(body(rec, prev)); advanceTimeBy(500)
        c.cancel(); advanceTimeBy(10_000)
        assertEquals("prev", rec.currentServerId)
        assertTrue(rec.events.any { it.startsWith("connect:prev") })
    }

    @Test fun explicitDisconnectCannotBeUndoneByTheCancelledRunsCleanup() = runTest {
        val (c, rec, _) = setup()
        rec.currentServerId = "prev"
        c.start(body(rec, prev)); advanceTimeBy(500)
        c.disconnect(); advanceTimeBy(30_000)
        assertNull(rec.currentServerId, "events=${rec.events}")
        assertFalse(rec.events.any { it.startsWith("connect:prev") }, "previous was restored after disconnect: ${rec.events}")
        assertEquals("disconnect", rec.events.last().substringBefore('@'))
    }

    @Test fun disconnectDuringTheRestoreItselfStillWins() = runTest {
        val (c, rec, _) = setup()
        rec.currentServerId = "prev"
        c.start(body(rec, prev)); advanceTimeBy(500)
        c.cancel(); advanceTimeBy(1_000) // cleanup is now connecting `prev`
        c.disconnect(); advanceTimeBy(30_000)
        assertNull(rec.currentServerId, "events=${rec.events}")
        assertEquals("disconnect", rec.events.last().substringBefore('@'))
    }

    @Test fun repeatedDisconnectsQueueAndEndDisconnected() = runTest {
        val (c, rec, _) = setup()
        c.start(body(rec, prev)); advanceTimeBy(100)
        c.disconnect(); c.disconnect(); c.disconnect(); advanceTimeBy(30_000)
        assertNull(rec.currentServerId)
        assertFalse(c.isRunning)
    }

    @Test fun startAfterDisconnectRunsAfterItAndSeesRealState() = runTest {
        val (c, rec, _) = setup()
        rec.currentServerId = "prev"
        var sawAtStart: String? = "unset"
        c.disconnect()
        c.start { sawAtStart = rec.currentServerId; rec.connect(next, 1000) }
        advanceTimeBy(30_000)
        assertNull(sawAtStart, "run body must observe the completed disconnect")
        assertEquals("next", rec.currentServerId)
    }

    @Test fun onlyOneRunAtATime() = runTest {
        val (c, rec, _) = setup()
        assertTrue(c.start(body(rec, null)))
        assertFalse(c.start(body(rec, null)))
        advanceTimeBy(10_000)
        assertTrue(c.start(body(rec, null)))
    }

    /** Random command streams: after the last disconnect completes, nothing issued earlier may reconnect anything. */
    @Test fun randomInterleavingsNeverReconnectAfterADisconnect() {
        repeat(500) { seed ->
            val rnd = Random(seed)
            runTest {
                val (c, rec, _) = setup()
                if (rnd.nextBoolean()) rec.currentServerId = "prev"
                var lastDisconnectIssuedAt = -1L
                var runsStartedAfter = 0
                repeat(rnd.nextInt(1, 8)) {
                    when (rnd.nextInt(4)) {
                        0, 1 -> {
                            val t = testScheduler.currentTime
                            val started = c.start { h ->
                                if (t > lastDisconnectIssuedAt && lastDisconnectIssuedAt >= 0) runsStartedAfter++
                                body(rec, if (rnd.nextBoolean()) prev else null)(h)
                            }
                            if (started && t > lastDisconnectIssuedAt && lastDisconnectIssuedAt >= 0) Unit
                        }
                        2 -> c.cancel()
                        else -> { lastDisconnectIssuedAt = testScheduler.currentTime; c.disconnect() }
                    }
                    advanceTimeBy(rnd.nextLong(0, 3_000))
                }
                if (lastDisconnectIssuedAt < 0) { c.disconnect(); lastDisconnectIssuedAt = testScheduler.currentTime }
                val finalDisconnect = c.disconnect()
                advanceTimeBy(60_000)
                assertTrue(finalDisconnect.isCompleted, "seed $seed")
                assertNull(rec.currentServerId, "seed $seed: ${rec.events}")
                // Nothing connected after the final disconnect completed.
                val lastDisc = rec.events.indexOfLast { it.startsWith("disconnect") }
                assertTrue(rec.events.drop(lastDisc + 1).none { it.startsWith("connect") }, "seed $seed: ${rec.events}")
            }
        }
    }
}
