package io.github.geniusdevam.vpnapp.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class QuickConnectEngineTest {
    private fun TestScope.clock() = Clock { testScheduler.currentTime }

    @Test fun picksLowestLatencyServerAndFinishesFast() = runTest {
        val servers = (1..20).map { srv("s$it", "JP") }
        val rtts = servers.associateWith { 20.0 + it.id.drop(1).toInt() * 7 } // s1 fastest
        val tunnel = FakeTunnel()
        val e = QuickConnectEngine(FakeCatalog(servers), FakeProber { rtts[it] }, tunnel, clock = clock())
        val out = e.run(Scope.Any)
        assertIs<QuickOutcome.Connected>(out)
        assertEquals("s1", out.server.id)
        assertTrue(testScheduler.currentTime < 12_000, "took ${testScheduler.currentTime} ms")
    }

    @Test fun neverConnectsOutsideSelectedCountry() = runTest {
        val servers = (1..30).map { srv("s$it", if (it % 2 == 0) "JP" else "KR") } + srv("fast-us", "US")
        val tunnel = FakeTunnel()
        val prober = FakeProber { if (it.id == "fast-us") 1.0 else 50.0 }
        val e = QuickConnectEngine(FakeCatalog(servers), prober, tunnel, clock = clock())
        val out = e.run(Scope.Country("KR"))
        assertIs<QuickOutcome.Connected>(out)
        assertEquals("KR", out.server.countryCode)
        assertTrue(tunnel.connectLog.all { id -> servers.first { it.id == id }.countryCode == "KR" })
    }

    @Test fun emptyCountryReportsNoServersWithoutConnecting() = runTest {
        val tunnel = FakeTunnel()
        val e = QuickConnectEngine(FakeCatalog(listOf(srv("a", "JP"))), FakeProber { 10.0 }, tunnel, clock = clock())
        val out = e.run(Scope.Country("DE"), RunContext(previous = srv("prev", "JP")))
        assertIs<QuickOutcome.Failed>(out)
        assertEquals(FailReason.NO_SERVERS_IN_SCOPE, out.reason)
        assertEquals("prev", out.restore?.id)
        assertTrue(tunnel.connectLog.isEmpty())
    }

    @Test fun failsOverToNextServerWhenFirstCannotConnect() = runTest {
        val servers = listOf(srv("a"), srv("b"), srv("c"))
        val rtts = mapOf("a" to 10.0, "b" to 20.0, "c" to 30.0)
        val tunnel = FakeTunnel { if (it.id == "a") Behavior(success = false, takesMs = 2_000) else Behavior() }
        val e = QuickConnectEngine(FakeCatalog(servers), FakeProber { rtts[it.id] }, tunnel, clock = clock())
        val out = e.run(Scope.Any)
        assertIs<QuickOutcome.Connected>(out)
        assertEquals("b", out.server.id)
    }

    @Test fun skipsBlockedServers() = runTest {
        val rep = ServerLedger(WallClock { testScheduler.currentTime })
        rep.recordFailure("a", hard = true)
        val servers = listOf(srv("a"), srv("b"))
        val e = QuickConnectEngine(FakeCatalog(servers), FakeProber { 10.0 }, FakeTunnel(), reputation = rep, clock = clock())
        val out = e.run(Scope.Any)
        assertIs<QuickOutcome.Connected>(out)
        assertEquals("b", out.server.id)
    }

    @Test fun strongModeRejectsServerThatNegotiatesWeakerCipher() = runTest {
        val servers = listOf(srv("a", cipher = "AES-256-GCM"), srv("b", cipher = "AES-256-GCM"))
        val tunnel = FakeTunnel { if (it.id == "a") Behavior(cipher = "AES-128-CBC") else Behavior(cipher = "AES-256-GCM") }
        val e = QuickConnectEngine(
            FakeCatalog(servers), FakeProber { if (it.id == "a") 10.0 else 20.0 }, tunnel,
            clock = clock(), cfg = QuickConfig(mode = EncryptionMode.STRONG),
        )
        val out = e.run(Scope.Any)
        assertIs<QuickOutcome.Connected>(out)
        assertEquals("b", out.server.id)
    }

    @Test fun stuckEngineStillEndsWithin35Seconds() = runTest {
        val tunnel = FakeTunnel { Behavior(success = true, takesMs = 120_000, ignoresTimeout = true) }
        val e = QuickConnectEngine(FakeCatalog(listOf(srv("a"))), FakeProber { 10.0 }, tunnel, clock = clock())
        val out = e.run(Scope.Any, RunContext(previous = srv("prev")))
        assertIs<QuickOutcome.Failed>(out)
        assertEquals(FailReason.TIMEOUT, out.reason)
        assertEquals("prev", out.restore?.id)
        assertEquals(35_000, testScheduler.currentTime)
        assertEquals(1, tunnel.aborted)
    }

    @Test fun hangingCatalogEndsWithin35Seconds() = runTest {
        val e = QuickConnectEngine(FakeCatalog(listOf(srv("a")), delayMs = 10_000_000), FakeProber { 10.0 }, FakeTunnel(), clock = clock())
        val out = e.run(Scope.Any)
        assertIs<QuickOutcome.Failed>(out)
        assertTrue(testScheduler.currentTime <= 35_000)
    }

    /** 1,000 random worlds: whatever happens, Quick Connect returns a verdict within 35 s and respects scope. */
    @Test fun neverExceeds35SecondsInRandomScenarios() {
        repeat(1000) { seed ->
            val rnd = Random(seed)
            runTest {
                val isos = listOf("JP", "KR", "US", "DE", "TH")
                val n = rnd.nextInt(0, 150)
                val servers = (0 until n).map {
                    srv("s$it", isos[rnd.nextInt(isos.size)], if (rnd.nextInt(4) == 0) Transport.TCP else Transport.UDP,
                        speedBps = if (rnd.nextBoolean()) rnd.nextLong(1_000_000, 500_000_000) else null)
                }
                val dead = rnd.nextDouble() // fraction of servers that never answer probes
                val rtts = servers.associateWith { if (rnd.nextDouble() < dead) null else rnd.nextDouble(2.0, 900.0) }
                val behaviors = servers.associateWith {
                    when (rnd.nextInt(6)) {
                        0 -> Behavior(success = false, takesMs = rnd.nextLong(100, 30_000))
                        1 -> Behavior(success = true, takesMs = rnd.nextLong(1_000, 120_000), ignoresTimeout = rnd.nextBoolean())
                        else -> Behavior(success = true, takesMs = rnd.nextLong(500, 9_000))
                    }
                }
                val scope = if (rnd.nextBoolean()) Scope.Any else Scope.Country(isos[rnd.nextInt(isos.size)])
                val tunnel = FakeTunnel { behaviors.getValue(it) }
                val catalogDelay = if (rnd.nextInt(5) == 0) rnd.nextLong(0, 200_000) else 0
                val e = QuickConnectEngine(
                    FakeCatalog(servers, catalogDelay), FakeProber { rtts[it] }, tunnel,
                    clock = clock(), random = Random(seed),
                )
                val out = e.run(scope, RunContext(userCountry = "JP"))
                assertTrue(testScheduler.currentTime <= 35_000, "seed $seed took ${testScheduler.currentTime} ms")
                if (out is QuickOutcome.Connected) assertTrue(scope.matches(out.server), "seed $seed scope violated")
                assertTrue(tunnel.connectLog.all { id -> scope.matches(servers.first { it.id == id }) }, "seed $seed")
            }
        }
    }
}
