package io.github.geniusdevam.vpnapp.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/** C9: "directory not loaded", "no relay in that country" and "relays exist but your settings exclude them" are different truths. */
class PoolSelectionTest {
    private fun TestScope.clock() = Clock { testScheduler.currentTime }
    private val blocked = object : Reputation by NoReputation { override fun isBlocked(serverId: String) = serverId.startsWith("bad") }

    @Test fun emptyDirectoryIsUnavailableNotNoServers() {
        val r = PoolSelection.select(emptyList(), Scope.Country("JP"), EncryptionMode.COMPATIBLE, NoReputation)
        assertEquals(FailReason.DIRECTORY_UNAVAILABLE, assertIs<PoolResult.Empty>(r).reason)
    }

    @Test fun loadedDirectoryWithoutThatCountryIsNoServersInScope() {
        val r = PoolSelection.select(listOf(srv("a", "JP")), Scope.Country("DE"), EncryptionMode.COMPATIBLE, NoReputation)
        assertEquals(FailReason.NO_SERVERS_IN_SCOPE, assertIs<PoolResult.Empty>(r).reason)
    }

    @Test fun serversExcludedByStrongModeAreReportedAsSettingsNotAbsence() {
        val servers = listOf(srv("a", "JP", cipher = "AES-128-CBC"), srv("b", "JP", cipher = "AES-128-CBC"))
        val r = assertIs<PoolResult.Empty>(PoolSelection.select(servers, Scope.Country("JP"), EncryptionMode.STRONG, NoReputation))
        assertEquals(FailReason.NO_SERVERS_MATCH_SETTINGS, r.reason)
        assertEquals(2, r.inScope); assertEquals(2, r.excludedBySettings)
    }

    @Test fun temporarilyAvoidedServersAreCountedSeparately() {
        val r = assertIs<PoolResult.Empty>(PoolSelection.select(listOf(srv("bad1", "JP"), srv("bad2", "JP")), Scope.Country("JP"), EncryptionMode.COMPATIBLE, blocked))
        assertEquals(FailReason.NO_SERVERS_MATCH_SETTINGS, r.reason)
        assertEquals(2, r.avoided); assertEquals(0, r.excludedBySettings)
    }

    @Test fun readyPoolNeverContainsOtherCountries() {
        val servers = listOf(srv("a", "JP"), srv("b", "KR"), srv("bad", "KR"))
        val r = assertIs<PoolResult.Ready>(PoolSelection.select(servers, Scope.Country("KR"), EncryptionMode.COMPATIBLE, blocked))
        assertEquals(listOf("b"), r.pool.map { it.id })
    }

    // ---- through the real engines ----
    @Test fun quickReportsDirectoryUnavailableAndNeverConnects() = runTest {
        val tunnel = FakeTunnel()
        val out = QuickConnectEngine(FakeCatalog(emptyList()), FakeProber { 10.0 }, tunnel, clock = clock()).run(Scope.Country("JP"), RunContext(previous = srv("prev")))
        assertEquals(FailReason.DIRECTORY_UNAVAILABLE, assertIs<QuickOutcome.Failed>(out).reason)
        assertTrue(tunnel.connectLog.isEmpty())
    }

    @Test fun bestReportsSettingsExclusionForStrongMode() = runTest {
        val tunnel = FakeTunnel()
        val e = BestConnectEngine(FakeCatalog(listOf(srv("a", "JP", cipher = "AES-128-CBC"))), FakeProber { 10.0 }, tunnel, FakeTester { null }, clock = clock(), cfg = BestConfig(mode = EncryptionMode.STRONG))
        assertEquals(FailReason.NO_SERVERS_MATCH_SETTINGS, assertIs<BestOutcome.Failed>(e.run(Scope.Country("JP"))).reason)
        assertTrue(tunnel.connectLog.isEmpty())
    }

    @Test fun bestWithEmptyDirectoryIsUnavailable() = runTest {
        val e = BestConnectEngine(FakeCatalog(emptyList()), FakeProber { 10.0 }, FakeTunnel(), FakeTester { null }, clock = clock())
        assertEquals(FailReason.DIRECTORY_UNAVAILABLE, assertIs<BestOutcome.Failed>(e.run(Scope.Any)).reason)
    }
}

class DirectoryStateTest {
    private val none = DirectoryDiagnostics()
    @Test fun beforeAnyAttemptIsLoadingNotEmpty() = assertEquals(DirectoryState.LOADING, none.state(0))
    @Test fun failedFirstDownloadIsUnavailableNotEmpty() = assertEquals(DirectoryState.UNAVAILABLE, none.copy(lastError = "network error").state(0))
    @Test fun successfulListWithNothingUsableIsEmpty() = assertEquals(DirectoryState.EMPTY, none.copy(lastSuccessfulRefreshAt = 1L).state(0))
    @Test fun cachedServersAfterFailedRefreshAreStale() =
        assertEquals(DirectoryState.STALE, none.copy(lastSuccessfulRefreshAt = 1L, lastError = "x", servingStaleCache = true).state(50))
    @Test fun healthyListIsFresh() = assertEquals(DirectoryState.FRESH, none.copy(lastSuccessfulRefreshAt = 1L).state(50))
}
