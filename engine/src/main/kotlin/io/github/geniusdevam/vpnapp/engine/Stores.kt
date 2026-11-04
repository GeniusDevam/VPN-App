package io.github.geniusdevam.vpnapp.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/** Wall-clock source so state survives app restarts. */
fun interface WallClock { fun nowMs(): Long }

class InMemoryReputation(private val wall: WallClock = WallClock { System.currentTimeMillis() }) : Reputation {
    data class Entry(val consecutiveFailures: Int, val blockedUntilMs: Long, val failures: Int)

    private val map = HashMap<String, Entry>()

    @Synchronized override fun isBlocked(serverId: String): Boolean =
        (map[serverId]?.blockedUntilMs ?: 0L) > wall.nowMs()

    @Synchronized override fun penaltyMs(serverId: String): Double =
        min(60.0, (map[serverId]?.failures ?: 0) * 10.0)

    @Synchronized override fun recordFailure(serverId: String, hard: Boolean) {
        val e = map[serverId] ?: Entry(0, 0, 0)
        val consecutive = e.consecutiveFailures + 1
        val now = wall.nowMs()
        val until = when {
            hard -> now + HARD_BLOCK_MS
            consecutive >= 2 -> now + SOFT_BLOCK_MS
            else -> e.blockedUntilMs
        }
        map[serverId] = Entry(consecutive, until, e.failures + 1)
    }

    @Synchronized override fun recordSuccess(serverId: String) {
        val e = map[serverId] ?: return
        map[serverId] = e.copy(consecutiveFailures = 0, failures = maxOf(0, e.failures - 1))
    }

    /** User tapped "Report & avoid". */
    @Synchronized fun blockLong(serverId: String) = recordFailure(serverId, hard = true)

    @Synchronized fun unblock(serverId: String) { map.remove(serverId) }

    @Synchronized fun export(): Map<String, Entry> = HashMap(map)

    @Synchronized fun restore(entries: Map<String, Entry>) {
        map.clear(); map.putAll(entries)
    }

    companion object {
        const val SOFT_BLOCK_MS = 30L * 60_000
        const val HARD_BLOCK_MS = 7L * 24 * 3_600_000
    }
}

class InMemoryHistory(private val wall: WallClock = WallClock { System.currentTimeMillis() }) : History {
    data class Lat(val medianMs: Double, val atMs: Long)
    data class Thr(val downMbps: Double, val atMs: Long)

    private val lat = HashMap<String, Lat>()
    private val thr = HashMap<String, Thr>()
    private fun key(id: String, fp: String) = "$id|$fp"

    @Synchronized override fun latencyPrior(serverId: String, fingerprint: String): Double? {
        val e = lat[key(serverId, fingerprint)] ?: return null
        return if (wall.nowMs() - e.atMs <= MAX_AGE_MS) e.medianMs else null
    }

    @Synchronized override fun throughputBonusMs(serverId: String, fingerprint: String): Double {
        val e = thr[key(serverId, fingerprint)] ?: return 0.0
        val ageH = (wall.nowMs() - e.atMs) / 3_600_000.0
        val decay = exp(-ageH / 24.0 * ln(2.0))
        return min(15.0, e.downMbps / 100.0 * 15.0) * decay
    }

    @Synchronized override fun recordLatency(serverId: String, fingerprint: String, medianMs: Double) {
        val k = key(serverId, fingerprint)
        val old = lat[k]
        val v = if (old == null) medianMs else 0.6 * medianMs + 0.4 * old.medianMs
        lat[k] = Lat(v, wall.nowMs())
    }

    @Synchronized override fun recordThroughput(serverId: String, fingerprint: String, downMbps: Double) {
        val k = key(serverId, fingerprint)
        val old = thr[k]
        val v = if (old == null) downMbps else 0.6 * downMbps + 0.4 * old.downMbps
        thr[k] = Thr(v, wall.nowMs())
    }

    @Synchronized fun export(): Pair<Map<String, Lat>, Map<String, Thr>> = HashMap(lat) to HashMap(thr)

    @Synchronized fun restore(l: Map<String, Lat>, t: Map<String, Thr>) {
        lat.clear(); lat.putAll(l); thr.clear(); thr.putAll(t)
    }

    companion object { const val MAX_AGE_MS = 7L * 24 * 3_600_000 }
}
