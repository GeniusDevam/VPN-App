package io.github.geniusdevam.vpnapp.engine

import kotlin.math.min

/** Numbers about ONE directory download, from raw rows down to relays this app will actually connect to. */
data class ListingStats(
    val rowsReceived: Int = 0,
    /** Rows that parsed into a well-formed OpenVPN profile (TCP or UDP). */
    val validProfiles: Int = 0,
    /** Valid profiles that also passed address and server-identity policy. */
    val usableServers: Int = 0,
    val countries: Int = 0,
    /** Why rows were dropped, by label (row problems first, then policy rejections). */
    val rejected: Map<String, Int> = emptyMap(),
)

class DirectoryResult(
    val usable: List<Server>,
    /** Every row that parsed, usable or not: this is what "appeared in the directory" means for retention. */
    val parsedIds: Set<String>,
    val stats: ListingStats,
    /** False if the listing must not be used to age or delete anything (empty, truncated, oversize). */
    val listingUsable: Boolean,
    val unusableReason: String?,
)

object DirectoryProcessor {
    /** A listing smaller than this is treated as a failed download, not as "everything vanished". */
    const val MIN_VALID_PROFILES = 10
    /** ... and so is a listing that shrank to under this share of the previous one. */
    const val MIN_SHARE_OF_PREVIOUS = 0.25

    fun process(report: ParseReport, auth: ServerAuthPolicy, previousValidProfiles: Int = 0): DirectoryResult {
        val rejected = java.util.TreeMap<String, Int>()
        report.rejected.forEach { (k, v) -> rejected[k.name.lowercase()] = v }
        if (report.oversize) {
            return DirectoryResult(emptyList(), emptySet(), ListingStats(rejected = rejected + ("oversize" to 1)), false, "response too large")
        }
        val usable = ArrayList<Server>()
        for (s in report.servers) {
            when (val r = ConfigSanitizer.sanitize(s.config, EncryptionMode.COMPATIBLE, s.ip, auth)) {
                is SanitizeResult.Ok -> usable.add(s)
                is SanitizeResult.Rejected -> "policy_${r.code.name.lowercase()}".let { k -> rejected[k] = (rejected[k] ?: 0) + 1 }
            }
        }
        val stats = ListingStats(
            rowsReceived = report.rowsSeen,
            validProfiles = report.servers.size,
            usableServers = usable.size,
            countries = usable.map { it.countryCode }.toSet().size,
            rejected = rejected,
        )
        val tooSmall = report.servers.size < MIN_VALID_PROFILES
        val collapsed = previousValidProfiles > 0 && report.servers.size < previousValidProfiles * MIN_SHARE_OF_PREVIOUS
        val reason = when {
            tooSmall -> "only ${report.servers.size} valid profiles (need $MIN_VALID_PROFILES)"
            collapsed -> "list shrank from $previousValidProfiles to ${report.servers.size} profiles"
            else -> null
        }
        return DirectoryResult(usable, report.servers.map { it.id }.toSet(), stats, reason == null, reason)
    }
}

/**
 * Relays seen over successive refreshes. VPN Gate publishes only part of its list and rotates it, so keeping what
 * recent listings contained gives a larger pool than any one download, using nothing but what the service hands
 * every client at normal refresh rates. Entries older than [retentionMs] are dropped from this CACHE (the
 * permanent history in [ServerLedger] follows its own seven-day absence rule).
 */
class DirectoryPool(private val retentionMs: Long = RETENTION_MS) {
    data class Entry(val server: Server, val firstSeenAt: Long, val lastSeenAt: Long)
    data class MergeStats(val added: Int, val updated: Int, val endpointChanged: List<String>, val dropped: Int, val replacedDuplicates: Int)

    private val byId = LinkedHashMap<String, Entry>()

    @Synchronized fun merge(servers: List<Server>, at: Long): MergeStats {
        var added = 0; var updated = 0; var dup = 0
        val changed = ArrayList<String>()
        for (s in servers) {
            val old = byId[s.id]
            if (old == null) added++ else {
                updated++
                if (endpoint(old.server) != endpoint(s)) changed.add(s.id)
            }
            // The same address under a different name is one relay: the newest listing wins.
            val ep = endpoint(s)
            val clash = byId.values.firstOrNull { it.server.id != s.id && endpoint(it.server) == ep }
            if (clash != null) { byId.remove(clash.server.id); dup++ }
            byId[s.id] = Entry(s, old?.firstSeenAt ?: at, at)
        }
        val before = byId.size
        byId.values.removeAll { at - it.lastSeenAt > retentionMs }
        return MergeStats(added, updated, changed, before - byId.size, dup)
    }

    /** Relays seen within [maxAgeMs]. With maxAgeMs <= 0 everything cached is returned (offline fallback). */
    @Synchronized fun servers(maxAgeMs: Long, at: Long): List<Server> =
        byId.values.filter { maxAgeMs <= 0 || at - it.lastSeenAt <= maxAgeMs }.map { it.server }

    @Synchronized fun entries(): List<Entry> = byId.values.toList()
    @Synchronized fun size(): Int = byId.size
    @Synchronized fun restore(entries: List<Entry>) { byId.clear(); entries.forEach { byId[it.server.id] = it } }
    @Synchronized fun clear() = byId.clear()

    private fun endpoint(s: Server) = "${s.ip}:${s.port}/${s.transport}"

    companion object { const val RETENTION_MS = 72L * 3_600_000 }
}

/** Decides when the app may contact VPN Gate again: a minimum interval, and exponential back-off after failures. */
class RefreshPolicy(
    private val minIntervalMs: Long = 10L * 60_000,
    private val minForcedIntervalMs: Long = 60_000,
    private val backoffBaseMs: Long = 60_000,
    private val backoffMaxMs: Long = 60L * 60_000,
) {
    private var lastAttemptAt: Long? = null
    private var lastSuccessAt: Long? = null
    private var failures = 0
    private var notBefore = 0L

    @Synchronized fun mayRefresh(now: Long, forced: Boolean = false): Boolean {
        if (now < notBefore) return false // honours Retry-After and back-off even for a forced refresh
        val last = lastAttemptAt ?: return true
        return now - last >= if (forced) minForcedIntervalMs else minIntervalMs
    }

    @Synchronized fun nextAllowedAt(now: Long, forced: Boolean = false): Long {
        val last = lastAttemptAt
        val byInterval = if (last == null) now else last + if (forced) minForcedIntervalMs else minIntervalMs
        return maxOf(byInterval, notBefore)
    }

    @Synchronized fun onAttempt(now: Long) { lastAttemptAt = now }

    @Synchronized fun onSuccess(now: Long) { lastSuccessAt = now; failures = 0; notBefore = 0 }

    /** [retryAfterMs] comes from a Retry-After header if the server sent one. */
    @Synchronized fun onFailure(now: Long, retryAfterMs: Long? = null) {
        failures++
        val backoff = min(backoffMaxMs, backoffBaseMs shl min(failures - 1, 16))
        notBefore = now + maxOf(backoff, retryAfterMs ?: 0L).coerceAtMost(24L * 3_600_000)
    }

    @Synchronized fun lastSuccess(): Long? = lastSuccessAt
    @Synchronized fun consecutiveFailures(): Int = failures
}

/** What the Servers/Settings screen shows. Honest by construction: "usable" counts only relays this app can use. */
data class DirectoryDiagnostics(
    val source: String = "",
    val lastSuccessfulRefreshAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val lastError: String? = null,
    val listing: ListingStats = ListingStats(),
    /** Relays currently selectable: the union of recent listings, not just the last download. */
    val pooledUsable: Int = 0,
    val pooledCountries: Int = 0,
    val newInLastRefresh: Int = 0,
    /** True when the shown list comes from the cache because the latest refresh did not succeed. */
    val servingStaleCache: Boolean = false,
    /** VPN Gate states that its public list is only part of its network; no complete source is known. */
    val knownPartial: Boolean = true,
    val savedServerRecords: Int = 0,
)

/** What the user may truthfully be told about the server list (finding C9). */
enum class DirectoryState {
    /** Nothing has been attempted or completed yet: "still loading" is the only honest statement. */
    LOADING,
    /** No list was ever obtained and the last attempt failed: nothing is known about any country. */
    UNAVAILABLE,
    /** A list was obtained and contains no usable relay at all. */
    EMPTY,
    /** Servers shown come from the cache because the latest refresh failed. */
    STALE,
    FRESH,
}

fun DirectoryDiagnostics.state(serversShown: Int): DirectoryState = when {
    serversShown == 0 && lastSuccessfulRefreshAt == null && lastError == null -> DirectoryState.LOADING
    serversShown == 0 && lastSuccessfulRefreshAt == null -> DirectoryState.UNAVAILABLE
    serversShown == 0 -> DirectoryState.EMPTY
    servingStaleCache -> DirectoryState.STALE
    else -> DirectoryState.FRESH
}
