package io.github.geniusdevam.vpnapp.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

class BestConnectEngineTest {
    private fun TestScope.clock() = Clock { testScheduler.currentTime }

    private val rtt = { s: Server -> 20.0 + s.id.drop(1).toInt() }

    @Test fun picksHighestThroughputNotLowestLatency() = runTest {
        val servers = (1..8).map { srv("s$it", "JP") }
        // s1 has the lowest latency but s5 is by far the fastest.
        val down = mapOf("s1" to 40.0, "s2" to 60.0, "s3" to 55.0, "s4" to 70.0, "s5" to 300.0, "s6" to 80.0, "s7" to 50.0, "s8" to 45.0)
        val tunnel = FakeTunnel()
        val tester = FakeTester { tp(down.getValue(it.id)) }
        val e = BestConnectEngine(FakeCatalog(servers), FakeProber(rtt), tunnel, tester, clock = clock())
        val out = e.run(Scope.Any)
        assertIs<BestOutcome.Connected>(out)
        assertEquals("s5", out.report.winner.id)
        assertEquals("s5", tunnel.currentServerId)
    }

    @Test fun stopsEarlyWhenLaterCandidatesKeepLosing() = runTest {
        val servers = (1..12).map { srv("s$it", "JP") }
        val down = servers.associate { it.id to if (it.id == "s1") 500.0 else 50.0 }
        val tester = FakeTester { tp(down.getValue(it.id)) }
        val e = BestConnectEngine(FakeCatalog(servers), FakeProber(rtt), FakeTunnel(), tester, clock = clock())
        val out = e.run(Scope.Any)
        assertIs<BestOutcome.Connected>(out)
        assertEquals("s1", out.report.winner.id)
        assertTrue(out.report.testedCount < 10, "tested ${out.report.testedCount}")
    }

    @Test fun honoursCountryScope() = runTest {
        val servers = (1..6).map { srv("s$it", if (it <= 3) "JP" else "KR") }
        val tunnel = FakeTunnel()
        val tester = FakeTester { tp(if (it.countryCode == "JP") 1000.0 else 100.0) }
        val e = BestConnectEngine(FakeCatalog(servers), FakeProber(rtt), tunnel, tester, clock = clock())
        val out = e.run(Scope.Country("KR"))
        assertIs<BestOutcome.Connected>(out)
        assertEquals("KR", out.report.winner.countryCode)
        assertTrue(tunnel.connectLog.all { it in setOf("s4", "s5", "s6") })
    }

    @Test fun respectsRunDataCap() = runTest {
        val servers = (1..10).map { srv("s$it") }
        val tester = FakeTester { tp(100.0 + it.id.drop(1).toInt(), bytes = 40_000_000) }
        val cfg = BestConfig(runCapBytes = 100_000_000, perCandidateBytes = 50_000_000)
        val e = BestConnectEngine(FakeCatalog(servers), FakeProber(rtt), FakeTunnel(), tester, clock = clock(), cfg = cfg)
        val out = e.run(Scope.Any)
        assertIs<BestOutcome.Connected>(out)
        assertTrue(out.report.bytesUsed <= 120_000_000, "used ${out.report.bytesUsed}")
        assertTrue(tester.runs.size <= 4)
    }

    @Test fun finishNowConnectsToCurrentLeader() = runTest {
        val servers = (1..10).map { srv("s$it") }
        val tester = FakeTester { tp(if (it.id == "s2") 400.0 else 100.0) }
        val control = BestControl()
        val tunnel = FakeTunnel()
        val e = BestConnectEngine(FakeCatalog(servers), FakeProber(rtt), tunnel, tester, clock = clock())
        var result: BestOutcome? = null
        val events = ArrayList<EngineEvent>()
        val job = launch {
            result = e.run(Scope.Any, control = control) {
                events.add(it)
                if (it is EngineEvent.Leaderboard && it.tested >= 2) control.finishNow()
            }
        }
        job.join()
        val out = result
        assertIs<BestOutcome.Connected>(out)
        assertTrue(out.report.testedCount <= 3)
        assertNotNull(tunnel.currentServerId)
    }

    @Test fun cancelStopsAndAbortsTunnelAttempt() = runTest {
        val servers = (1..10).map { srv("s$it") }
        val tunnel = FakeTunnel()
        val e = BestConnectEngine(FakeCatalog(servers), FakeProber(rtt), tunnel, FakeTester { tp(100.0) }, clock = clock())
        val job = launch { e.run(Scope.Any) }
        advanceTimeBy(10_000)
        job.cancel(CancellationException("user cancelled"))
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, tunnel.aborted)
    }

    @Test fun usesLowestLatencyIfNoSpeedTestCanRun() = runTest {
        val servers = (1..5).map { srv("s$it") }
        val e = BestConnectEngine(FakeCatalog(servers), FakeProber(rtt), FakeTunnel(), FakeTester { null }, clock = clock())
        val out = e.run(Scope.Any)
        assertIs<BestOutcome.Connected>(out)
        assertEquals("s1", out.report.winner.id)
    }

    @Test fun scoringProfilesWeightLatencyDifferently() {
        val a = Composite.Sample(srv("a"), LatencyStats(20.0, 1.0, 0.0, 5, ProbeMethod.NONE), listOf(tp(200.0, loaded = 300.0, idle = 30.0)), null)
        val b = Composite.Sample(srv("b"), LatencyStats(20.0, 1.0, 0.0, 5, ProbeMethod.NONE), listOf(tp(120.0, loaded = 40.0, idle = 25.0)), null)
        assertEquals("a", Composite.score(listOf(a, b), OptimizeFor.DOWNLOAD).first().server.id)
        assertEquals("b", Composite.score(listOf(a, b), OptimizeFor.LOW_LATENCY).first().server.id)
    }
}
