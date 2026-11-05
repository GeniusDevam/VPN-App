package io.github.geniusdevam.vpnapp.engine

import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

class GlobalSearchEngineTest {
    private fun TestScope.clock() = Clock { testScheduler.currentTime }
    private val isos = listOf("JP", "KR", "US", "DE", "FR", "GB", "BR", "IN", "TH", "VN", "RU", "TW", "NL", "CA", "AU", "SG", "TR", "PL", "IT", "ES")
    private fun world(n: Int) = (0 until n).map { srv("s$it", isos[it % isos.size]) }

    private class CountingProber(private val inner: (Server) -> Double?) : LatencyProber {
        val inflight = AtomicInteger(); var max = 0; val probed = HashSet<String>()
        override suspend fun probe(server: Server, timeoutMs: Long): ProbeSample {
            max = maxOf(max, inflight.incrementAndGet()); probed.add(server.id)
            try { return FakeProber(inner).probe(server, timeoutMs) } finally { inflight.decrementAndGet() }
        }
    }

    private fun rtt(s: Server): Double? = 20.0 + (s.id.drop(1).toInt() % 97) * 3.0
    private fun mkEngine(sc: TestScope, servers: List<Server>, prober: LatencyProber = CountingProber(::rtt), tunnel: FakeTunnel = FakeTunnel(),
                         tester: FakeTester = FakeTester { tp(100.0 - (it.id.drop(1).toInt() % 50)) }, cfg: GlobalSearchConfig = GlobalSearchConfig(),
                         history: History = NoHistory) =
        GlobalSearchEngine(FakeCatalog(servers), prober, tunnel, tester, history, clock = sc.clock(), cfg = cfg, random = Random(1))

    @Test fun probesInBoundedBatchesCoversEveryCountryAndReportsPartialCoverage() = runTest {
        val servers = world(1500)
        val prober = CountingProber(::rtt); val tunnel = FakeTunnel(); val tester = FakeTester { tp(50.0 + it.id.drop(1).toInt() % 30) }
        val cfg = GlobalSearchConfig(maxProbed = 300, probeBatch = 25, maxSpeedTests = 12, minProbedForStop = 10_000)
        val out = mkEngine(this, servers, prober, tunnel, tester, cfg).run(RunContext(userCountry = "JP"))
        assertIs<SearchOutcome.Finished>(out)
        assertTrue(prober.max <= 25, "concurrent probes ${prober.max}")
        assertTrue(prober.probed.size <= 300)
        assertEquals(isos.toSet(), prober.probed.map { id -> servers.first { it.id == id }.countryCode }.toSet(), "every country sampled")
        assertTrue(tester.runs.size <= 12 + 3 * 2, "speed tests ${tester.runs.size}")
        val r = assertNotNull(out.report)
        assertEquals(1500, r.poolSize); assertEquals(BestClaim.PARTIAL_COVERAGE, r.claim)
        assertTrue(r.why().contains("of 1500"), r.why())
        assertNotNull(out.connected)
    }

    private fun BestReport.why() = reason

    @Test fun smallPoolFullyProbedCanEarnConfirmedClaim() = runTest {
        val servers = world(30)
        val tester = FakeTester { tp(if (it.id == "s7") 400.0 else 50.0) }
        val out = mkEngine(this, servers, tester = tester, cfg = GlobalSearchConfig(maxSpeedTests = 30, minTestedForStop = 30, runCapBytes = 2L shl 30)).run(RunContext())
        val r = assertNotNull((out as SearchOutcome.Finished).report)
        assertEquals("s7", r.winner.id)
        assertEquals(BestClaim.BEST_OF_TESTED_CONFIRMED, r.claim)
        assertEquals(30, r.poolSize); assertEquals(30, r.scannedCount)
    }

    @Test fun pauseHaltsAllWorkUntilResumed() = runTest {
        val servers = world(200)
        val control = SearchControl()
        val prober = CountingProber(::rtt)
        val e = mkEngine(this, servers, prober, cfg = GlobalSearchConfig(maxProbed = 200, probeBatch = 20))
        var result: SearchOutcome? = null
        val job = launch { result = e.run(RunContext(), control) }
        advanceTimeBy(1_000)
        control.pause()
        advanceTimeBy(3_000) // let the in-flight batch land
        val probedWhenPaused = prober.probed.size
        advanceTimeBy(10 * 60_000)
        assertEquals(probedWhenPaused, prober.probed.size, "no new probes while paused")
        assertNull(result)
        control.resume()
        advanceTimeBy(60 * 60_000)
        job.join()
        assertNotNull(result)
        assertTrue(prober.probed.size > probedWhenPaused)
    }

    @Test fun finishNowConnectsToBestSoFarAndSaysSo() = runTest {
        val servers = world(120)
        val control = SearchControl()
        val tester = FakeTester { tp(10.0 + it.id.drop(1).toInt()) }
        val tunnel = FakeTunnel()
        val e = mkEngine(this, servers, tunnel = tunnel, tester = tester, cfg = GlobalSearchConfig(minTestedForStop = 100))
        var result: SearchOutcome? = null
        val job = launch { result = e.run(RunContext(), control, emit = { ev -> if (ev is EngineEvent.Search && ev.progress.speedTested >= 3) control.finishNow() }) }
        advanceTimeBy(60 * 60_000); job.join()
        val out = result as SearchOutcome.Finished
        assertEquals(StopReason.USER_FINISHED, out.stop)
        assertEquals(BestClaim.EARLY_FINISH, out.report!!.claim)
        assertNotNull(out.connected); assertEquals(out.connected!!.server.id, tunnel.currentServerId)
        assertTrue(tester.runs.size in 3..5, "stopped promptly: ${tester.runs.size}")
    }

    @Test fun cancellationAbortsTheTunnel() = runTest {
        val tunnel = FakeTunnel()
        val e = mkEngine(this, world(100), tunnel = tunnel)
        val job = launch { e.run(RunContext()) }
        advanceTimeBy(4_000)
        job.cancel(); advanceTimeBy(1_000)
        assertEquals(1, tunnel.aborted)
    }

    @Test fun dataBudgetIsNeverExceededByMoreThanOneTest() = runTest {
        val tester = FakeTester { tp(80.0, bytes = 10_000_000) }
        val cfg = GlobalSearchConfig(runCapBytes = 35_000_000, perCandidateBytes = 10_000_000, maxSpeedTests = 30, minTestedForStop = 100)
        val out = mkEngine(this, world(80), tester = tester, cfg = cfg).run(RunContext()) as SearchOutcome.Finished
        assertTrue(out.report!!.bytesUsed <= 40_000_000, "used ${out.report!!.bytesUsed}")
        assertEquals(StopReason.DATA_BUDGET, out.stop)
    }

    @Test fun timeBudgetStopsTheSearch() = runTest {
        val cfg = GlobalSearchConfig(maxDurationMs = 60_000, maxProbed = 1500, probeBatch = 5, minProbedForStop = 100_000)
        val out = mkEngine(this, world(1500), cfg = cfg).run(RunContext()) as SearchOutcome.Finished
        assertEquals(StopReason.TIME_BUDGET, out.stop)
        assertTrue(testScheduler.currentTime < 60_000 + 120_000, "ran ${testScheduler.currentTime}")
    }

    @Test fun strongModeNeverConnectsToANonCompliantCipher() = runTest {
        val servers = world(40).map { it.copy(declaredCipher = "AES-256-GCM") }
        val tunnel = FakeTunnel { Behavior(cipher = if (it.id.drop(1).toInt() % 2 == 0) "AES-128-CBC" else "AES-256-GCM") }
        val out = mkEngine(this, servers, tunnel = tunnel, cfg = GlobalSearchConfig(mode = EncryptionMode.STRONG)).run(RunContext()) as SearchOutcome.Finished
        assertEquals(1, out.connected!!.server.id.drop(1).toInt() % 2)
        assertEquals("AES-256-GCM", out.connected!!.cipher)
    }

    @Test fun explorationGivesNonLeadersASpeedTest() = runTest {
        val tester = FakeTester { tp(60.0) }
        val cfg = GlobalSearchConfig(maxSpeedTests = 20, explorationShare = 0.25, minTestedForStop = 100)
        val probe = CountingProber(::rtt)
        mkEngine(this, world(300), probe, tester = tester, cfg = cfg).run(RunContext())
        val latencyTop = world(300).sortedBy { rtt(it)!! }.take(15).map { it.id }.toSet()
        assertTrue(tester.runs.any { it !in latencyTop }, "no exploration candidate was speed-tested: ${tester.runs}")
    }

    @Test fun seedsFromAnInterruptedSearchAreNotRemeasured() = runTest {
        val servers = world(40)
        val seeds = servers.take(10).associate { it.id to tp(500.0) }
        val tester = FakeTester { tp(50.0) }
        val out = mkEngine(this, servers, tester = tester, cfg = GlobalSearchConfig(maxSpeedTests = 40, minTestedForStop = 100, confirmTop = 0))
            .run(RunContext(), seeds = seeds) as SearchOutcome.Finished
        assertTrue(tester.runs.none { it in seeds.keys }, "re-tested seeded servers: ${tester.runs}")
        assertTrue(out.report!!.winner.id in seeds.keys, "seeded winner should still be able to win")
    }

    @Test fun emptyOrBlockedPoolFailsHonestly() = runTest {
        val out = mkEngine(this, emptyList()).run(RunContext(previous = srv("prev"))) as SearchOutcome.Finished
        assertEquals(StopReason.NO_SERVERS, out.stop); assertNull(out.report); assertEquals("prev", out.restore?.id)
    }

    @Test fun notConnectingAtEndLeavesNothingConnected() = runTest {
        val tunnel = FakeTunnel()
        val out = mkEngine(this, world(40), tunnel = tunnel, cfg = GlobalSearchConfig(connectAtEnd = false)).run(RunContext()) as SearchOutcome.Finished
        assertNull(out.connected); assertNull(tunnel.currentServerId); assertNotNull(out.report)
    }
}
