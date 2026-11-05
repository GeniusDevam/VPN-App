package io.github.geniusdevam.vpnapp.engine

import kotlin.random.Random
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Collects probe samples per server and ranks them with the Quick score. */
internal class ProbeBoard(
    private val rep: Reputation,
    private val history: History,
    private val fingerprint: String,
    private val scoring: ScoringConfig,
) {
    private val samples = HashMap<String, MutableList<Double?>>()
    private val methods = HashMap<String, ProbeMethod>()
    private val servers = HashMap<String, Server>()
    private val done = HashSet<String>()

    @Synchronized fun record(server: Server, s: ProbeSample) {
        servers[server.id] = server
        samples.getOrPut(server.id) { ArrayList() }.add(s.rttMs)
        if (s.method != ProbeMethod.NONE) methods[server.id] = s.method
    }

    @Synchronized fun markDone(server: Server) { done.add(server.id) }
    /** Start a new pass over a subset: progress counts restart, samples are kept. */
    @Synchronized fun resetProgress() { done.clear() }
    @Synchronized fun doneCount(): Int = done.size
    @Synchronized fun responded(): Int = samples.values.count { l -> l.any { it != null } }

    @Synchronized fun ranked(maxLoss: Double = 1.0): List<Ranked> =
        samples.mapNotNull { (id, list) ->
            val server = servers.getValue(id)
            val stats = Scoring.latencyStats(list, methods[id] ?: ProbeMethod.NONE) ?: return@mapNotNull null
            if (stats.loss > maxLoss) return@mapNotNull null
            val q = Scoring.quickScore(
                server, stats, rep.penaltyMs(id), history.throughputBonusMs(id, fingerprint) + history.reliabilityBonusMs(id), scoring,
            )
            Ranked(server, stats, q)
        }.sortedBy { it.q }

    @Synchronized fun bestQ(): Double? = ranked().firstOrNull()?.q
}

internal data class ProbeParams(
    val attempts: Int,
    val spacingMs: Long,
    val timeoutMs: Long,
    val concurrency: Int,
    val earlyStop: Boolean,
    val minMs: Long = 1_500,
    val minResponders: Int = 6,
    val windowMs: Long = 750,
    val improvement: Double = 0.05,
    val hardCapMs: Long? = null,
)

internal class ProbeRunner(private val prober: LatencyProber, private val clock: Clock) {
    suspend fun run(
        servers: List<Server>,
        board: ProbeBoard,
        p: ProbeParams,
        emit: (EngineEvent) -> Unit,
        shouldStop: () -> Boolean = { false },
    ) {
        if (servers.isEmpty()) return
        coroutineScope {
            val sem = Semaphore(p.concurrency)
            val jobs = servers.map { s ->
                launch {
                    sem.withPermit {
                        for (i in 0 until p.attempts) {
                            if (i > 0) delay(p.spacingMs)
                            board.record(s, prober.probe(s, p.timeoutMs))
                        }
                        board.markDone(s)
                    }
                }
            }
            val t0 = clock.nowMs()
            val trail = ArrayList<Pair<Long, Double>>()
            var lastEmit = t0 - 1_000
            while (jobs.any { it.isActive }) {
                delay(100)
                val now = clock.nowMs()
                val elapsed = now - t0
                board.bestQ()?.let { trail.add(now to it) }
                if (now - lastEmit >= 500) {
                    emit(EngineEvent.ProbeProgress(board.doneCount(), servers.size, board.ranked().take(3)))
                    lastEmit = now
                }
                if (shouldStop()) break
                if (p.hardCapMs != null && elapsed >= p.hardCapMs) break
                if (p.earlyStop && elapsed >= p.minMs && board.responded() >= minOf(p.minResponders, servers.size)) {
                    val then = trail.lastOrNull { it.first <= now - p.windowMs }?.second
                    val nowQ = board.bestQ()
                    if (then != null && nowQ != null && (then <= 0 || (then - nowQ) / then < p.improvement)) break
                }
            }
            jobs.forEach { it.cancel() }
        }
        emit(EngineEvent.ProbeProgress(board.doneCount(), servers.size, board.ranked().take(3)))
    }
}

object Prioritizer {
    /** Chooses which servers to probe: best guesses first, a few random explorers, and the user's favorites. */
    fun pick(
        pool: List<Server>,
        ctx: RunContext,
        history: History,
        max: Int,
        explore: Int,
        random: Random,
    ): List<Server> {
        if (pool.size <= max) return pool
        val scored = pool.map { it to estimate(it, ctx, history) + Scoring.slowLinePenaltyMs(it) + random.nextDouble() * 5 }
            .sortedBy { it.second }
        val favs = pool.filter { it.id in ctx.favorites }.take(4)
        val topCount = (max - explore - favs.size).coerceAtLeast(1)
        val top = scored.take(topCount).map { it.first }
        val rest = scored.drop(topCount).map { it.first }.shuffled(random).take(explore)
        return (favs + top + rest).distinctBy { it.id }.take(max)
    }

    /**
     * Estimated RTT before any probe: the remembered measurement blended with a geographic guess according to how
     * fresh the measurement is, so an old measurement still helps a little but never dominates a stale guess.
     */
    fun estimate(s: Server, ctx: RunContext, history: History): Double {
        val geo = Countries.distanceKm(ctx.userCountry, s.countryCode)?.let { 10 + it / 70.0 } ?: 120.0
        val prior = history.latencyPrior(s.id, ctx.fingerprint) ?: return geo
        val w = history.priorWeight(s.id, ctx.fingerprint).coerceIn(0.0, 1.0)
        return w * prior + (1 - w) * geo
    }
}
