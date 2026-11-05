package io.github.geniusdevam.vpnapp.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordSummaryTest {
    private val now = 1_000_000_000_000L
    private val day = 86_400_000L
    private fun rec(f: (ServerRecord) -> ServerRecord = { it }) = f(ServerRecord("s", now - 9 * day, now - 2 * 3_600_000))

    @Test fun agoUnits() {
        assertEquals("just now", RecordSummary.ago(30_000)); assertEquals("5 min ago", RecordSummary.ago(5 * 60_000L))
        assertEquals("3 h ago", RecordSummary.ago(3 * 3_600_000L)); assertEquals("4 d ago", RecordSummary.ago(4 * day))
        assertEquals("just now", RecordSummary.ago(-5_000), "a clock that went backwards must not print a negative age")
    }

    @Test fun neverUsedServerShowsOnlyDirectoryPresence() {
        assertEquals(listOf("In the server list 2 h ago"), RecordSummary.lines(rec(), null, now))
    }

    @Test fun countsLastConnectConnectTimeAndDropsAreShown() {
        val r = rec { it.copy(successCount = 4, failureCount = 1, lastSuccessAt = now - day, connectMsAvg = 4200.0, sessionCount = 4, unexpectedDisconnects = 1, lastFailure = FailureKind.TIMEOUT) }
        val l = RecordSummary.lines(r, null, now).first()
        assertTrue("✓ 4 · ✗ 1" in l && "last connected 1 d ago" in l && "~4.2 s" in l && "25%" in l && "timeout" in l, l)
    }

    @Test fun measurementsAreShownOnlyForThisNetwork() {
        val r = rec { it.copy(networks = mapOf("net-A" to NetStats(latencyMs = 45.0, probeMethod = ProbeMethod.OPENVPN_UDP_RESET, latencyAt = now - 600_000, downMbps = 38.0, upMbps = 12.0, throughputAt = now - 3_600_000))) }
        assertTrue(RecordSummary.lines(r, "net-A", now).any { it.startsWith("On this network: 45 ms, ↓ 38 Mbps ↑ 12 Mbps") && "measured 10 min ago" in it })
        assertTrue(RecordSummary.lines(r, "net-B", now).none { it.startsWith("On this network") }, "another network's numbers must not be presented as this one's")
    }

    @Test fun weakProbesAreLabelled() {
        val r = rec { it.copy(networks = mapOf("n" to NetStats(latencyMs = 30.0, probeMethod = ProbeMethod.TCP_FALLBACK, latencyAt = now))) }
        assertTrue(RecordSummary.lines(r, "n", now).any { "host only, weak" in it })
    }

    @Test fun unverifiedCipherIsNotCalledObserved() {
        assertTrue("(not verified)" in RecordSummary.lines(rec { it.copy(lastCipher = "AES-128-CBC", lastCipherVerified = false) }, null, now).joinToString())
        assertTrue("(observed)" in RecordSummary.lines(rec { it.copy(lastCipher = "AES-256-GCM", lastCipherVerified = true) }, null, now).joinToString())
    }

    @Test fun absenceProgressTowardsSevenDayDeletionIsExplained() {
        val l = RecordSummary.lines(rec { it.copy(absentObservedMs = 3 * day + day / 2) }, null, now).last()
        assertTrue("missing from 3.5 of 7 days" in l && "deleted at 7" in l, l)
    }
}
