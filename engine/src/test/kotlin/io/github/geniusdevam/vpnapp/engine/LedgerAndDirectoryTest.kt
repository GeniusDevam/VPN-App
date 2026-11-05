package io.github.geniusdevam.vpnapp.engine

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject

class LedgerAndDirectoryTest {
    private val H = 3_600_000L
    private val D = 24 * H
    private var now = 1_000_000_000_000L
    private fun ledger() = ServerLedger(WallClock { now })
    private fun ep(ip: String = "203.0.114.1", cc: String = "JP") = EndpointInfo(ip, 1194, Transport.UDP, cc)
    private fun listing(vararg ids: String) = ids.associateWith { ep() }

    // ---------------- A4: seven-day absence rule ----------------

    @Test fun relayAbsentForSevenObservedDaysIsDeletedAndNotBefore() {
        val l = ledger()
        l.observeDirectory(listing("a", "b"), true)
        l.recordFailure("a", false); l.connectSucceeded("a", "fp", 1000, "AES-128-CBC", true)
        // refresh every 12 h, "a" never listed again
        var removedAt = -1L
        for (i in 1..20) {
            now += 12 * H
            val rep = l.observeDirectory(listing("b"), true)
            if ("a" in rep.removed) { removedAt = i * 12 * H; break }
        }
        assertEquals(7 * D, removedAt, "removed at exactly 7 observed days")
        assertNull(l.record("a")); assertNotNull(l.record("b"))
    }

    @Test fun failedAndUnusableDownloadsNeverAgeAnything() {
        val l = ledger()
        l.observeDirectory(listing("a"), true)
        repeat(100) { now += D; l.observeDirectory(emptyMap(), usable = false) } // 100 days of failures
        val rep = l.observeDirectory(listing("a"), true)
        assertTrue(rep.applied); assertNotNull(l.record("a")); assertEquals(0, l.record("a")!!.absentObservedMs)
    }

    @Test fun offlineGapCountsAtMostThirtySixHours() {
        val l = ledger()
        l.observeDirectory(listing("a", "b"), true)
        now += 30 * D // phone off / no connectivity for a month, then one good refresh without "a"
        l.observeDirectory(listing("b"), true)
        assertEquals(ServerLedger.OBSERVATION_CAP_MS, l.record("a")!!.absentObservedMs)
        assertNotNull(l.record("a"))
    }

    @Test fun reappearingResetsTheClockAndKeepsHistory() {
        val l = ledger()
        l.observeDirectory(listing("a", "b"), true)
        l.connectSucceeded("a", "fp", 800, "AES-256-GCM", true)
        repeat(10) { now += 12 * H; l.observeDirectory(listing("b"), true) } // 5 days absent
        assertTrue(l.record("a")!!.absentObservedMs > 4 * D)
        now += 12 * H; l.observeDirectory(listing("a", "b"), true)
        val r = l.record("a")!!
        assertEquals(0, r.absentObservedMs); assertEquals(1, r.successCount); assertEquals(now, r.lastSeenInDirectoryAt)
        repeat(13) { now += 12 * H; l.observeDirectory(listing("b"), true) } // 6.5 days: still there
        assertNotNull(l.record("a"))
    }

    @Test fun oldMeasurementsAreNeverDeletedForAgeAndOnlyLoseWeight() {
        val l = ledger()
        l.observeDirectory(listing("a"), true)
        l.recordLatency("a", "fp", LatencyStats(80.0, 3.0, 0.0, 5, ProbeMethod.OPENVPN_UDP_RESET))
        assertEquals(1.0, l.priorWeight("a", "fp"), 1e-9)
        repeat(60) { now += 12 * H; l.observeDirectory(listing("a"), true) } // 30 days, relay stays listed
        assertEquals(80.0, l.latencyPrior("a", "fp"))
        val w = l.priorWeight("a", "fp")
        assertTrue(w < 0.01 + ServerLedger.MIN_PRIOR_WEIGHT && w >= ServerLedger.MIN_PRIOR_WEIGHT, "weight=$w")
    }

    @Test fun successFailureAndSessionAccounting() {
        val l = ledger()
        l.connectSucceeded("a", "fp", 1200, "AES-128-CBC", true)
        l.connectSucceeded("a", "fp", 800, null, false)
        l.connectFailed("a", "fp", FailureKind.TIMEOUT, 9000)
        l.connectFailed("a", "fp", FailureKind.CANCELLED, 10) // our own cancel is not the relay's fault
        l.connectFailed("a", "fp", FailureKind.CERTIFICATE, 500)
        l.sessionEnded("a", "fp", 600_000, unexpected = true)
        l.sessionEnded("a", "fp", 1_200_000, unexpected = false)
        val r = l.record("a")!!
        assertEquals(2, r.successCount); assertEquals(2, r.failureCount)
        assertEquals(mapOf(FailureKind.TIMEOUT to 1, FailureKind.CERTIFICATE to 1), r.failuresByKind)
        assertEquals(FailureKind.CERTIFICATE, r.lastFailure)
        assertEquals(2, r.sessionCount); assertEquals(1, r.unexpectedDisconnects); assertEquals(1_800_000, r.totalSessionMs)
        assertEquals(0.5, r.dropRate); assertEquals(0.5, r.successRate)
        assertFalse(r.lastCipherVerified); assertNull(r.lastCipher, "an unobserved cipher is stored as unknown, never as the declared one")
        assertTrue(r.connectMsAvg!! in 800.0..1200.0)
    }

    @Test fun addressChangeKeepsCountersButDropsStaleMeasurements() {
        val l = ledger()
        l.observeDirectory(mapOf("a" to ep("203.0.114.1")), true)
        l.connectSucceeded("a", "fp", 500, null, false)
        l.recordLatency("a", "fp", LatencyStats(40.0, 1.0, 0.0, 3, ProbeMethod.TCP_CONNECT))
        now += H
        val rep = l.observeDirectory(mapOf("a" to ep("203.0.114.9")), true)
        assertEquals(listOf("a"), rep.endpointChanged)
        assertEquals(1, l.record("a")!!.successCount); assertNull(l.latencyPrior("a", "fp"))
    }

    @Test fun networkContextsAreCapped() {
        val l = ledger()
        repeat(20) { now += 1000; l.recordLatency("a", "net$it", LatencyStats(10.0, 0.0, 0.0, 1, ProbeMethod.TCP_CONNECT)) }
        assertEquals(ServerLedger.MAX_NETWORKS, l.record("a")!!.networks.size)
        assertNotNull(l.latencyPrior("a", "net19")); assertNull(l.latencyPrior("a", "net0"))
    }

    @Test fun reputationBehaviourIsPreserved() {
        val l = ledger()
        l.recordFailure("a", false); assertFalse(l.isBlocked("a"))
        l.recordFailure("a", false); assertTrue(l.isBlocked("a"))
        now += ServerLedger.SOFT_BLOCK_MS + 1; assertFalse(l.isBlocked("a"))
        l.recordFailure("b", true); now += ServerLedger.SOFT_BLOCK_MS * 10; assertTrue(l.isBlocked("b"))
        l.unblock("b"); assertFalse(l.isBlocked("b"))
    }

    @Test fun clearMeasurementsKeepsRetentionBookkeeping() {
        val l = ledger()
        l.observeDirectory(listing("a"), true); l.connectSucceeded("a", "fp", 5, null, false)
        l.clearMeasurements()
        val r = l.record("a")!!; assertEquals(0, r.successCount); assertEquals("203.0.114.1:1194/UDP", r.endpoint)
    }

    @Test fun codecRoundTripsEverything() {
        val l = ledger()
        l.observeDirectory(listing("a", "b"), true)
        l.connectSucceeded("a", "fp", 900, "AES-256-GCM", true); l.connectFailed("a", "fp", FailureKind.AUTH, 5)
        l.recordLatency("a", "fp", LatencyStats(33.0, 2.0, 0.1, 5, ProbeMethod.OPENVPN_UDP_RESET))
        l.recordThroughput("a", "fp", ThroughputStats(50.0, 10.0, 20.0, 40.0, 1000))
        l.sessionEnded("a", "fp", 1000, true); l.recordFailure("b", true)
        val snap = l.snapshot()
        val back = Codec.ledgerFromJson(JSONObject(Codec.ledgerToJson(snap).toString()))
        assertEquals(snap.lastObservationAt, back.lastObservationAt)
        assertEquals(snap.records.sortedBy { it.id }, back.records.sortedBy { it.id })
    }

    @Test fun legacyStateIsImportedSoUpdatesLoseNothing() {
        val rep = JSONObject("""{"old|id":[1,${now + 5000},3]}""")
        val lat = JSONObject("""{"old|id|abcd1234":[55.5,${now - 1000}]}""")
        val thr = JSONObject("""{"old|id|abcd1234":[80.0,${now - 500}]}""")
        val snap = Codec.importLegacy(rep, lat, thr, now)
        val l = ledger(); l.restore(snap)
        assertEquals(55.5, l.latencyPrior("old|id", "abcd1234")); assertTrue(l.throughputBonusMs("old|id", "abcd1234") > 0)
        assertTrue(l.isBlocked("old|id"))
    }

    // ---------------- A1: directory ----------------

    private fun cfgFor(ip: String, ca: String = Pki.ROOT) =
        "client\ndev tun\nproto tcp\nremote $ip 443\ncipher AES-128-CBC\nauth SHA1\nauth-user-pass\n<ca>\n$ca\n</ca>\n"
    private fun row(id: String, ip: String, iso: String = "JP", cfg: String = cfgFor(ip)) =
        "$id,$ip,100,5,50000000,Japan,$iso,3,1000,10,10,2weeks,op,msg,${Base64.getEncoder().encodeToString(cfg.toByteArray())}"
    private val auth = ServerAuthPolicy.of(setOf(Pki.ROOT_FP))

    @Test fun processorReportsHonestNumbersAndReasons() {
        val rows = ArrayList<String>()
        repeat(12) { rows.add(row("ok$it", "203.0.114.${it + 1}", if (it % 2 == 0) "JP" else "KR")) }
        rows.add(row("rogue", "203.0.114.100", cfg = cfgFor("203.0.114.100", Pki.ROGUE)))   // valid profile, identity policy fails
        rows.add(row("lan", "10.0.0.5"))                                                     // bad address
        rows.add(row("zz", "203.0.114.101", "ZZ"))                                           // bad country
        rows.add(row("dup", "203.0.114.1"))                                                  // same endpoint as ok0
        rows.add("junk")
        val r = DirectoryProcessor.process(VpnGateParser.parseDetailed(rows.joinToString("\n")), auth)
        assertEquals(17, r.stats.rowsReceived)
        assertEquals(13, r.stats.validProfiles)
        assertEquals(12, r.stats.usableServers)
        assertEquals(2, r.stats.countries)
        assertEquals(1, r.stats.rejected["policy_identity"]); assertEquals(1, r.stats.rejected["bad_address"])
        assertEquals(1, r.stats.rejected["bad_country"]); assertEquals(1, r.stats.rejected["duplicate_endpoint"])
        assertEquals(1, r.stats.rejected["malformed_row"])
        assertTrue(r.listingUsable)
        assertTrue("rogue" in r.parsedIds && "rogue" !in r.usable.map { it.id })
    }

    @Test fun truncatedOrCollapsedListingsCannotAgeHistory() {
        val few = DirectoryProcessor.process(VpnGateParser.parseDetailed((1..3).joinToString("\n") { row("s$it", "203.0.114.$it") }), auth)
        assertFalse(few.listingUsable)
        val rows = (1..40).joinToString("\n") { row("s$it", "203.0.114.$it") }
        val small = (1..12).joinToString("\n") { row("s$it", "203.0.114.$it") }
        assertTrue(DirectoryProcessor.process(VpnGateParser.parseDetailed(small), auth, previousValidProfiles = 100).let { !it.listingUsable })
        assertTrue(DirectoryProcessor.process(VpnGateParser.parseDetailed(rows), auth, previousValidProfiles = 100).listingUsable)
        assertFalse(DirectoryProcessor.process(ParseReport(emptyList(), 0, emptyMap(), oversize = true), auth).listingUsable)
    }

    @Test fun poolUnionsListingsDedupesEndpointsAndPrunes() {
        fun s(id: String, ip: String) = srv(id).copy(ip = ip, port = 443)
        val pool = DirectoryPool(retentionMs = 72 * H)
        val t0 = 0L
        pool.merge(listOf(s("a", "1.1.1.1"), s("b", "1.1.1.2")), t0)
        val m = pool.merge(listOf(s("b", "1.1.1.2"), s("c", "1.1.1.3")), t0 + 6 * H)
        assertEquals(1, m.added); assertEquals(1, m.updated)
        assertEquals(setOf("a", "b", "c"), pool.servers(0, t0 + 6 * H).map { it.id }.toSet(), "union of two partial listings")
        assertEquals(setOf("b", "c"), pool.servers(5 * H, t0 + 6 * H).map { it.id }.toSet(), "freshness filter")
        val renamed = pool.merge(listOf(s("a2", "1.1.1.1")), t0 + 7 * H)
        assertEquals(1, renamed.replacedDuplicates); assertFalse("a" in pool.servers(0, t0 + 7 * H).map { it.id })
        val pruned = pool.merge(listOf(s("d", "1.1.1.4")), t0 + 100 * H)
        assertTrue(pruned.dropped >= 3); assertEquals(listOf("d"), pool.servers(0, t0 + 100 * H).map { it.id })
    }

    @Test fun poolCodecReadsLegacyCacheAndSkipsDamagedEntries() {
        val legacy = JSONObject().put("at", 5L).put("servers", org.json.JSONArray().put(Codec.serverToJson(srv("x"))).put(JSONObject().put("id", "broken")))
        val (entries, at) = Codec.poolFromJson(legacy)
        assertEquals(1, entries.size); assertEquals(5L, at); assertEquals(5L, entries[0].lastSeenAt)
        val round = Codec.poolFromJson(JSONObject(Codec.poolToJson(entries, at).toString()))
        assertEquals(entries.map { it.server }, round.first.map { it.server })
    }

    @Test fun refreshPolicyRateLimitsAndBacksOff() {
        val p = RefreshPolicy()
        assertTrue(p.mayRefresh(0))
        p.onAttempt(0); p.onSuccess(0)
        assertFalse(p.mayRefresh(5 * 60_000)); assertTrue(p.mayRefresh(10 * 60_000))
        assertFalse(p.mayRefresh(30_000, forced = true)); assertTrue(p.mayRefresh(61_000, forced = true))
        var t = 10 * 60_000L
        p.onAttempt(t); p.onFailure(t)
        assertFalse(p.mayRefresh(t + 30_000, forced = true), "back-off applies even to a forced refresh")
        assertTrue(p.mayRefresh(t + 11 * 60_000))
        repeat(10) { t += 3_600_000; p.onAttempt(t); p.onFailure(t) }
        assertTrue(p.nextAllowedAt(t) - t <= 3_600_000 + 1)
        p.onAttempt(t); p.onFailure(t, retryAfterMs = 2 * 3_600_000L)
        assertFalse(p.mayRefresh(t + 3_600_000)); assertTrue(p.mayRefresh(t + 2 * 3_600_000 + 11 * 60_000))
        p.onSuccess(t + 3 * 3_600_000); assertEquals(0, p.consecutiveFailures())
    }
}
