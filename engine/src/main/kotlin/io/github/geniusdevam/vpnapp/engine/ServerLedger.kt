package io.github.geniusdevam.vpnapp.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

/** Wall-clock source so state survives app restarts. */
fun interface WallClock { fun nowMs(): Long }

/** What a relay looked like in the last usable directory listing. */
data class EndpointInfo(val ip: String, val port: Int, val transport: Transport, val country: String) {
    val key: String get() = "$ip:$port/${transport.name}"
}

/** Measurements of one relay as seen from one network (a salted hash, see Networks.fingerprint). */
data class NetStats(
    val latencyMs: Double? = null,
    val jitterMs: Double? = null,
    val loss: Double? = null,
    val probeMethod: ProbeMethod = ProbeMethod.NONE,
    val latencyAt: Long = 0,
    val downMbps: Double? = null,
    val upMbps: Double? = null,
    val loadedRttMs: Double? = null,
    val throughputAt: Long = 0,
    val lastUsedAt: Long = 0,
) {
    val newest: Long get() = maxOf(latencyAt, throughputAt, lastUsedAt)
}

/** Everything saved about one relay. It is deleted only by the seven-day absence rule or by "clear history". */
data class ServerRecord(
    val id: String,
    val firstSeenAt: Long,
    /** Last time this relay appeared in a successfully obtained, usable directory listing. */
    val lastSeenInDirectoryAt: Long,
    /** Observed time (see [ServerLedger.observeDirectory]) during which listings omitted this relay. */
    val absentObservedMs: Long = 0,
    val endpoint: String? = null,
    val country: String? = null,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val lastSuccessAt: Long? = null,
    val lastFailureAt: Long? = null,
    val lastFailure: FailureKind? = null,
    val failuresByKind: Map<FailureKind, Int> = emptyMap(),
    /** Exponentially weighted connect (handshake) time in ms, from attempts that reached "connected". */
    val connectMsAvg: Double? = null,
    val lastCipher: String? = null,
    val lastCipherVerified: Boolean = false,
    val lastTransport: Transport? = null,
    val sessionCount: Int = 0,
    val totalSessionMs: Long = 0,
    val unexpectedDisconnects: Int = 0,
    val lastSessionMs: Long? = null,
    // reputation (blocking) state
    val consecutiveFailures: Int = 0,
    val blockedUntilMs: Long = 0,
    val penaltyFailures: Int = 0,
    val networks: Map<String, NetStats> = emptyMap(),
) {
    val successRate: Double? get() = (successCount + failureCount).takeIf { it > 0 }?.let { successCount.toDouble() / it }
    /** Share of sessions that did not end because the user disconnected. */
    val dropRate: Double? get() = sessionCount.takeIf { it > 0 }?.let { unexpectedDisconnects.toDouble() / it }
}

/** Result of applying one directory observation. */
data class RetentionReport(
    val applied: Boolean,
    val seen: Int,
    val added: Int,
    val removed: List<String>,
    val endpointChanged: List<String>,
)

data class LedgerSnapshot(
    val records: List<ServerRecord>,
    /** Wall time of the previous USABLE directory observation; the absence clock is measured between these. */
    val lastObservationAt: Long?,
)

/**
 * The permanent local record of how every relay has performed for this user.
 *
 * Retention (the owner's rule): a relay's saved data is removed once it has been ABSENT from successfully
 * obtained, usable directory listings for seven days of observed time. Precisely, in [observeDirectory]:
 *  - nothing happens for a listing that is not usable (download failed, offline, invalid, or suspiciously small),
 *    so source failures can never age anything;
 *  - for a usable listing, the observed interval is `now - previous usable observation`, capped at
 *    [OBSERVATION_CAP_MS], so a long stretch with no successful refresh (phone off, no network) is not counted
 *    as "absence" either;
 *  - every saved relay missing from the listing accumulates that interval; at [ABSENCE_LIMIT_MS] it is deleted;
 *  - any relay that appears has its absence reset to zero and its last-seen time refreshed.
 * VPN Gate publishes only part of its list and rotates it, so absence is an observation about the PUBLIC LIST,
 * not proof that a relay is gone; that is exactly why measurements are never deleted merely for being old.
 * Age only reduces how much an old measurement influences ranking ([priorWeight]).
 *
 * Thread-safe. Privacy: nothing here leaves the device; network contexts are salted hashes and at most
 * [MAX_NETWORKS] are kept per relay.
 */
class ServerLedger(private val wall: WallClock = WallClock { System.currentTimeMillis() }) : History, Reputation, AttemptRecorder {
    private val records = HashMap<String, ServerRecord>()
    private var lastObservationAt: Long? = null

    // ------------------------------------------------------------------ directory / retention

    @Synchronized
    fun observeDirectory(
        listing: Map<String, EndpointInfo>,
        usable: Boolean,
        at: Long = wall.nowMs(),
    ): RetentionReport {
        if (!usable) return RetentionReport(false, 0, 0, emptyList(), emptyList())
        val prev = lastObservationAt
        val elapsed = if (prev == null) 0L else (at - prev).coerceIn(0L, OBSERVATION_CAP_MS)
        lastObservationAt = at

        var added = 0
        val changed = ArrayList<String>()
        for ((id, ep) in listing) {
            val old = records[id]
            if (old == null) {
                records[id] = ServerRecord(id, firstSeenAt = at, lastSeenInDirectoryAt = at, endpoint = ep.key, country = ep.country)
                added++
            } else {
                var r = old.copy(lastSeenInDirectoryAt = at, absentObservedMs = 0, country = ep.country)
                if (old.endpoint != null && old.endpoint != ep.key) {
                    // Same relay name, different address: the old latency/throughput describe another network position.
                    r = r.copy(networks = emptyMap())
                    changed.add(id)
                }
                records[id] = r.copy(endpoint = ep.key)
            }
        }
        val removed = ArrayList<String>()
        val iter = records.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.key in listing) continue
            val absent = entry.value.absentObservedMs + elapsed
            if (absent >= ABSENCE_LIMIT_MS) { iter.remove(); removed.add(entry.key) }
            else entry.setValue(entry.value.copy(absentObservedMs = absent))
        }
        return RetentionReport(true, listing.size, added, removed, changed)
    }

    // ------------------------------------------------------------------ queries / persistence

    @Synchronized fun record(id: String): ServerRecord? = records[id]
    @Synchronized fun all(): List<ServerRecord> = records.values.toList()
    @Synchronized fun size(): Int = records.size
    @Synchronized fun lastObservation(): Long? = lastObservationAt

    @Synchronized fun snapshot() = LedgerSnapshot(records.values.toList(), lastObservationAt)

    @Synchronized fun restore(s: LedgerSnapshot) {
        records.clear(); s.records.forEach { records[it.id] = it }; lastObservationAt = s.lastObservationAt
    }

    /** "Clear history": removes measurements and counters but keeps the absence bookkeeping so retention keeps working. */
    @Synchronized fun clearMeasurements() {
        for (e in records.entries) {
            val r = e.value
            e.setValue(ServerRecord(r.id, r.firstSeenAt, r.lastSeenInDirectoryAt, r.absentObservedMs, r.endpoint, r.country))
        }
    }

    private fun upd(id: String, f: (ServerRecord) -> ServerRecord) {
        val now = wall.nowMs()
        records[id] = f(records[id] ?: ServerRecord(id, firstSeenAt = now, lastSeenInDirectoryAt = now))
    }

    private fun touchNet(r: ServerRecord, fp: String, now: Long, f: (NetStats) -> NetStats): ServerRecord {
        val nets = HashMap(r.networks)
        nets[fp] = f(nets[fp] ?: NetStats()).copy(lastUsedAt = now)
        if (nets.size > MAX_NETWORKS) {
            nets.entries.minByOrNull { it.value.newest }?.let { nets.remove(it.key) }
        }
        return r.copy(networks = nets)
    }

    // ------------------------------------------------------------------ History (prediction inputs)

    @Synchronized override fun latencyPrior(serverId: String, fingerprint: String): Double? =
        records[serverId]?.networks?.get(fingerprint)?.latencyMs

    /** 1.0 for a fresh measurement, halving every [PRIOR_HALF_LIFE_MS]; never below [MIN_PRIOR_WEIGHT]. Old data is down-weighted, not discarded. */
    @Synchronized override fun priorWeight(serverId: String, fingerprint: String): Double {
        val n = records[serverId]?.networks?.get(fingerprint) ?: return 0.0
        if (n.latencyMs == null) return 0.0
        val age = (wall.nowMs() - n.latencyAt).coerceAtLeast(0)
        return (0.5).pow(age.toDouble() / PRIOR_HALF_LIFE_MS).coerceAtLeast(MIN_PRIOR_WEIGHT)
    }

    @Synchronized override fun throughputBonusMs(serverId: String, fingerprint: String): Double {
        val n = records[serverId]?.networks?.get(fingerprint) ?: return 0.0
        val down = n.downMbps ?: return 0.0
        val ageH = (wall.nowMs() - n.throughputAt) / 3_600_000.0
        return min(15.0, down / 100.0 * 15.0) * exp(-ageH / 24.0 * ln(2.0))
    }

    @Synchronized override fun reliabilityBonusMs(serverId: String): Double {
        val r = records[serverId] ?: return 0.0
        val rate = r.successRate ?: return 0.0
        val n = r.successCount + r.failureCount
        return if (n >= 3 && rate >= 0.8 && (r.dropRate ?: 0.0) < 0.3) 10.0 * min(1.0, n / 10.0) else 0.0
    }

    @Synchronized override fun recordLatency(serverId: String, fingerprint: String, stats: LatencyStats) {
        val now = wall.nowMs()
        upd(serverId) { r ->
            touchNet(r, fingerprint, now) { n ->
                fun blend(old: Double?, v: Double) = if (old == null) v else 0.6 * v + 0.4 * old
                n.copy(
                    latencyMs = blend(n.latencyMs, stats.medianMs), jitterMs = blend(n.jitterMs, stats.jitterMs),
                    loss = blend(n.loss, stats.loss), probeMethod = stats.method, latencyAt = now,
                )
            }
        }
    }

    @Synchronized override fun recordThroughput(serverId: String, fingerprint: String, stats: ThroughputStats) {
        val now = wall.nowMs()
        upd(serverId) { r ->
            touchNet(r, fingerprint, now) { n ->
                fun blend(old: Double?, v: Double) = if (old == null) v else 0.6 * v + 0.4 * old
                n.copy(
                    downMbps = blend(n.downMbps, stats.downMbps), upMbps = blend(n.upMbps, stats.upMbps),
                    loadedRttMs = blend(n.loadedRttMs, stats.loadedRttMs), throughputAt = now,
                )
            }
        }
    }

    // ------------------------------------------------------------------ AttemptRecorder (history of outcomes)

    @Synchronized override fun connectSucceeded(serverId: String, fingerprint: String, connectMs: Long, cipher: String?, cipherVerified: Boolean) {
        val now = wall.nowMs()
        upd(serverId) { r ->
            touchNet(r, fingerprint, now) { it }.copy(
                successCount = r.successCount + 1, lastSuccessAt = now,
                connectMsAvg = r.connectMsAvg?.let { 0.7 * it + 0.3 * connectMs } ?: connectMs.toDouble(),
                lastCipher = cipher, lastCipherVerified = cipherVerified,
            )
        }
    }

    @Synchronized override fun connectFailed(serverId: String, fingerprint: String, kind: FailureKind, connectMs: Long) {
        if (kind == FailureKind.CANCELLED) return
        val now = wall.nowMs()
        upd(serverId) { r ->
            r.copy(
                failureCount = r.failureCount + 1, lastFailureAt = now, lastFailure = kind,
                failuresByKind = r.failuresByKind + (kind to ((r.failuresByKind[kind] ?: 0) + 1)),
            )
        }
    }

    @Synchronized override fun sessionEnded(serverId: String, fingerprint: String, durationMs: Long, unexpected: Boolean) {
        if (durationMs < 0) return
        upd(serverId) { r ->
            r.copy(
                sessionCount = r.sessionCount + 1, totalSessionMs = r.totalSessionMs + durationMs, lastSessionMs = durationMs,
                unexpectedDisconnects = r.unexpectedDisconnects + if (unexpected) 1 else 0,
            )
        }
    }

    @Synchronized fun noteTransport(serverId: String, transport: Transport) = upd(serverId) { it.copy(lastTransport = transport) }

    // ------------------------------------------------------------------ Reputation (temporary / long blocks)

    @Synchronized override fun isBlocked(serverId: String): Boolean = (records[serverId]?.blockedUntilMs ?: 0L) > wall.nowMs()

    @Synchronized override fun penaltyMs(serverId: String): Double = min(60.0, (records[serverId]?.penaltyFailures ?: 0) * 10.0)

    @Synchronized override fun recordFailure(serverId: String, hard: Boolean) {
        val now = wall.nowMs()
        upd(serverId) { e ->
            val consecutive = e.consecutiveFailures + 1
            val until = when {
                hard -> now + HARD_BLOCK_MS
                consecutive >= 2 -> now + SOFT_BLOCK_MS
                else -> e.blockedUntilMs
            }
            e.copy(consecutiveFailures = consecutive, blockedUntilMs = until, penaltyFailures = e.penaltyFailures + 1)
        }
    }

    @Synchronized override fun recordSuccess(serverId: String) {
        val e = records[serverId] ?: return
        records[serverId] = e.copy(consecutiveFailures = 0, penaltyFailures = maxOf(0, e.penaltyFailures - 1))
    }

    /** User tapped "Report & avoid". */
    @Synchronized fun blockLong(serverId: String) = recordFailure(serverId, hard = true)

    @Synchronized fun unblock(serverId: String) {
        val e = records[serverId] ?: return
        records[serverId] = e.copy(consecutiveFailures = 0, blockedUntilMs = 0, penaltyFailures = 0)
    }

    companion object {
        const val ABSENCE_LIMIT_MS = 7L * 24 * 3_600_000
        const val OBSERVATION_CAP_MS = 36L * 3_600_000
        const val SOFT_BLOCK_MS = 30L * 60_000
        const val HARD_BLOCK_MS = 7L * 24 * 3_600_000
        const val MAX_NETWORKS = 8
        const val PRIOR_HALF_LIFE_MS = 3L * 24 * 3_600_000
        const val MIN_PRIOR_WEIGHT = 0.05
    }
}
