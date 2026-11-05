package io.github.geniusdevam.vpnapp.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class SelectionHonestyTest {
    private fun TestScope.clock() = Clock { testScheduler.currentTime }

    @Test fun bestConnectNoLongerSilentlyDropsRelaysBeyondTheOldFourHundredCap() = runTest {
        val servers = (0 until 700).map { srv("s$it", listOf("JP", "KR", "US", "DE")[it % 4]) }
        val e = BestConnectEngine(
            FakeCatalog(servers), FakeProber { 20.0 + it.id.drop(1).toInt() % 90 }, FakeTunnel(), FakeTester { tp(40.0 + it.id.drop(1).toInt() % 20) },
            clock = clock(), random = Random(3),
        )
        val out = e.run(Scope.Any)
        assertIs<BestOutcome.Connected>(out)
        assertEquals(700, out.report.scannedCount); assertEquals(700, out.report.poolSize)
        assertTrue(out.report.respondedCount > 0)
    }

    @Test fun bestConnectReportsPartialCoverageWhenTheSafetyCapBites() = runTest {
        val servers = (0 until 120).map { srv("s$it") }
        val e = BestConnectEngine(
            FakeCatalog(servers), FakeProber { 30.0 }, FakeTunnel(), FakeTester { tp(50.0) },
            clock = clock(), cfg = BestConfig(scanMax = 50), random = Random(3),
        )
        val out = e.run(Scope.Any) as BestOutcome.Connected
        assertEquals(BestClaim.PARTIAL_COVERAGE, out.report.claim)
        assertEquals(120, out.report.poolSize); assertEquals(50, out.report.scannedCount)
    }

    @Test fun bestConnectNeverLeavesAPolicyViolatingTunnelUp() = runTest {
        val servers = (0 until 6).map { srv("s$it", cipher = "AES-256-GCM") }
        // s0 is fastest but negotiates CBC; Strong mode must refuse it and not stay connected to it.
        val tunnel = FakeTunnel { if (it.id == "s0") Behavior(cipher = "AES-128-CBC") else Behavior(cipher = "AES-256-GCM") }
        val e = BestConnectEngine(
            FakeCatalog(servers), FakeProber { if (it.id == "s0") 5.0 else 40.0 + it.id.drop(1).toInt() }, tunnel,
            FakeTester { tp(if (it.id == "s0") 900.0 else 100.0) }, clock = clock(), cfg = BestConfig(mode = EncryptionMode.STRONG), random = Random(1),
        )
        val out = e.run(Scope.Any) as BestOutcome.Connected
        assertNotEquals("s0", out.report.winner.id)
        assertNotEquals("s0", tunnel.currentServerId)
        assertTrue(tunnel.disconnects >= 1, "violating tunnel was torn down")
        assertTrue(out.cipherVerified)
    }

    @Test fun quickConnectReportsHowManyWereConsideredAndThatItIsApproximate() = runTest {
        val servers = (0 until 200).map { srv("s$it") }
        val e = QuickConnectEngine(FakeCatalog(servers), FakeProber { 20.0 + it.id.drop(1).toInt() % 50 }, FakeTunnel(), clock = clock(), random = Random(2))
        val out = e.run(Scope.Any) as QuickOutcome.Connected
        assertEquals(200, out.selection.poolSize)
        assertTrue(out.selection.probed in 1..64, "probed ${out.selection.probed}")
        assertTrue(out.selection.responded > 0 && out.selection.approximate)
    }

    @Test fun weakReachabilityProbesAreRankedBelowRealVpnProbes() {
        val s = srv("a", speedBps = 200_000_000, sessions = 0)
        val real = Scoring.quickScore(s, LatencyStats(40.0, 1.0, 0.0, 3, ProbeMethod.OPENVPN_UDP_RESET))
        val host = Scoring.quickScore(s, LatencyStats(40.0, 1.0, 0.0, 3, ProbeMethod.TCP_FALLBACK))
        assertTrue(host - real >= 60.0, "fallback penalty ${host - real}")
        assertEquals(ProbeConfidence.LOW, Ranked(s, LatencyStats(40.0, 1.0, 0.0, 3, ProbeMethod.TCP_FALLBACK), host).confidence)
        assertEquals(ProbeConfidence.HIGH, ProbeMethod.OPENVPN_UDP_RESET.confidence)
        assertEquals(ProbeConfidence.MEDIUM, ProbeMethod.TCP_CONNECT.confidence)
    }

    @Test fun anOldMeasurementStillHelpsButWithLessWeightThanAFreshOne() {
        var now = 0L
        val l = ServerLedger(WallClock { now })
        l.recordLatency("a", "fp", LatencyStats(300.0, 0.0, 0.0, 3, ProbeMethod.TCP_CONNECT))
        val ctx = RunContext(userCountry = "JP", fingerprint = "fp")
        val s = srv("a", "KR")
        val fresh = Prioritizer.estimate(s, ctx, l)
        now += 30L * 24 * 3_600_000
        val old = Prioritizer.estimate(s, ctx, l)
        val geo = Prioritizer.estimate(srv("zzz", "KR"), ctx, l)
        assertTrue(fresh > old && old > geo, "fresh=$fresh old=$old geo=$geo")
    }
}
