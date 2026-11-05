package io.github.geniusdevam.vpnapp.engine

import kotlin.random.Random
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

enum class SearchPhase { LOADING, PROBING, REFINING, SPEED_TESTING, CONFIRMING, CONNECTING, DONE }

enum class StopReason { COMPLETED, DIMINISHING_RETURNS, USER_FINISHED, DATA_BUDGET, TIME_BUDGET, NO_SERVERS }

/** Everything the UI needs for an honest progress screen and "best so far" leaderboard. */
data class SearchProgress(
    val phase: SearchPhase,
    /** Relays in the whole network view that passed policy. */
    val poolSize: Int,
    val countriesTotal: Int,
    val probed: Int,
    val responded: Int,
    val countriesSampled: Int,
    val speedTested: Int,
    val speedTestsPlanned: Int,
    val bytesUsed: Long,
    val elapsedMs: Long,
    val paused: Boolean,
    val leaderboard: List<BestEntry>,
)

data class GlobalSearchConfig(
    /** Relays probed at once, and per batch. The only source of probe concurrency. */
    val probeBatch: Int = 32,
    /** Upper bound on relays probed. The result says how much of the pool this covered. */
    val maxProbed: Int = 400,
    /** Every country gets at least this many probes before the rest of the budget is spent, so no country is skipped by geography alone. */
    val perCountryFloor: Int = 3,
    val minProbedForStop: Int = 96,
    val stopProbeAfterIdleBatches: Int = 3,
    val probeAttempts: Int = 2,
    val probeSpacingMs: Long = 300,
    val probeTimeoutMs: Long = 1_500,
    val refineTop: Int = 40,
    val refineAttempts: Int = 3,
    val maxSpeedTests: Int = 30,
    val maxPerCountryInShortlist: Int = 4,
    /** Share of speed-test slots given to relays outside the latency top, so the shortlist cannot be a self-fulfilling prophecy. */
    val explorationShare: Double = 0.2,
    val connectTimeoutMs: Long = 15_000,
    val perCandidateBytes: Long = 30L shl 20,
    val runCapBytes: Long = 200L shl 20,
    val maxDurationMs: Long = 45L * 60_000,
    val minTestedForStop: Int = 8,
    val stopAfterNoImprovement: Int = 6,
    val improvement: Double = 0.05,
    val confirmTop: Int = 3,
    val maxConfirmExtraRounds: Int = 1,
    val connectAtEnd: Boolean = true,
    val mode: EncryptionMode = EncryptionMode.COMPATIBLE,
    val optimize: OptimizeFor = OptimizeFor.BALANCED,
    val scoring: ScoringConfig = ScoringConfig(),
)

/** Pause / finish controls. Cancellation is by cancelling the calling coroutine. */
class SearchControl {
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused
    @Volatile var finishNow: Boolean = false
        private set
    fun pause() { _paused.value = true }
    fun resume() { _paused.value = false }
    /** Stop searching and use the best candidate so far. */
    fun finishNow() { finishNow = true; _paused.value = false }
    internal suspend fun checkpoint(): Boolean { _paused.first { !it }; return finishNow }
}

sealed interface SearchOutcome {
    data class Finished(
        val report: BestReport?,
        val connected: Admitted?,
        val stop: StopReason,
        val restore: Server?,
    ) : SearchOutcome
}

/**
 * Thorough search across ALL available countries. Unlike Quick Connect's "Any country" (a fast, approximate pick),
 * this is expensive and only ever run on the user's explicit request:
 *   1. Probe a stratified sample in small batches (every country gets a floor; rest by predicted latency, using the
 *      ledger's history), stopping early when further batches stop improving the leaders.
 *   2. Re-probe the leaders for steadier medians.
 *   3. Speed-test a diverse shortlist through real tunnels (with a few exploration slots), within a data budget.
 *   4. Confirm the leaders, and (optionally) connect to the winner.
 * It is honest about coverage: the report carries pool size, probed count and a [BestClaim] that is
 * PARTIAL_COVERAGE unless every relay was probed. It can be paused, finished early ("connect to best so far") or
 * cancelled. While speed tests run, the device's traffic goes through test relays or is unprotected; the engine
 * makes no continuous-protection promise during that time.
 */
class GlobalSearchEngine(
    private val catalog: Catalog,
    prober: LatencyProber,
    private val tunnel: TunnelController,
    private val tester: ThroughputTester,
    private val history: History = NoHistory,
    private val reputation: Reputation = NoReputation,
    private val recorder: AttemptRecorder = NoRecorder,
    private val clock: Clock = MonotonicClock,
    private val cfg: GlobalSearchConfig = GlobalSearchConfig(),
    private val random: Random = Random.Default,
) {
    private val runner = ProbeRunner(prober, clock)

    private class Acc(val server: Server, val latency: LatencyStats, var admitted: Admitted?) {
        val runs = ArrayList<ThroughputStats>()
        val cipher: String? get() = admitted?.cipher
    }

    suspend fun run(
        ctx: RunContext = RunContext(),
        control: SearchControl = SearchControl(),
        /** Throughput results from an interrupted earlier search (recent enough to trust); they are not re-measured. */
        seeds: Map<String, ThroughputStats> = emptyMap(),
        emit: (EngineEvent) -> Unit = {},
    ): SearchOutcome {
        var done = false
        try {
            val out = attempt(ctx, control, seeds, emit)
            done = out.connected != null
            return out
        } finally {
            if (!done) withContext(NonCancellable) { tunnel.abort() }
        }
    }

    private suspend fun attempt(ctx: RunContext, control: SearchControl, seeds: Map<String, ThroughputStats>, emit: (EngineEvent) -> Unit): SearchOutcome.Finished {
        val start = clock.nowMs()
        fun elapsed() = clock.nowMs() - start
        var pool: List<Server> = emptyList()
        var probedCount = 0
        var respondedFn: () -> Int = { 0 }
        var countriesSampled = 0
        var tested = 0
        var planned = 0
        var bytes = 0L
        val accs = LinkedHashMap<String, Acc>()
        var leaderboard: List<BestEntry> = emptyList()

        fun progress(phase: SearchPhase) = emit(
            EngineEvent.Search(
                SearchProgress(
                    phase, pool.size, pool.map { it.countryCode }.toSet().size, probedCount, respondedFn(), countriesSampled,
                    tested, planned, bytes, elapsed(), control.paused.value, leaderboard,
                ),
            ),
        )
        fun entries(): List<BestEntry> =
            Composite.score(accs.values.filter { it.runs.isNotEmpty() }.map { Composite.Sample(it.server, it.latency, it.runs, it.cipher) }, cfg.optimize)
        fun timeUp() = elapsed() >= cfg.maxDurationMs

        progress(SearchPhase.LOADING)
        pool = catalog.servers(0, 15_000)
            .filter { !reputation.isBlocked(it.id) && CipherPolicy.acceptsServer(it, cfg.mode) }
        if (pool.isEmpty()) return SearchOutcome.Finished(null, null, StopReason.NO_SERVERS, ctx.previous)

        // ---- 1. stratified, batched probing ----
        val b = ProbeBoard(reputation, history, ctx.fingerprint, cfg.scoring)
        respondedFn = { b.responded() }
        val order = stratify(pool, ctx)
        val batches = order.chunked(cfg.probeBatch)
        val sampled = HashSet<String>()
        var idle = 0
        var bestTop = Double.MAX_VALUE
        var stop = StopReason.COMPLETED
        for (batch in batches) {
            if (control.checkpoint()) { stop = StopReason.USER_FINISHED; break }
            if (timeUp()) { stop = StopReason.TIME_BUDGET; break }
            progress(SearchPhase.PROBING)
            b.resetProgress()
            runner.run(
                batch, b,
                ProbeParams(cfg.probeAttempts, cfg.probeSpacingMs, cfg.probeTimeoutMs, concurrency = cfg.probeBatch, earlyStop = false),
                emit = {},
            ) { control.finishNow }
            probedCount += batch.size
            batch.forEach { sampled.add(it.countryCode) }
            countriesSampled = sampled.size
            val topNow = b.ranked().take(10).map { it.q }.average().takeIf { !it.isNaN() } ?: Double.MAX_VALUE
            val improved = bestTop == Double.MAX_VALUE || (bestTop - topNow) / bestTop >= cfg.improvement
            if (improved) { idle = 0; bestTop = minOf(bestTop, topNow) } else idle++
            val floorsDone = probedCount >= floorSize(pool)
            if (probedCount >= cfg.minProbedForStop && floorsDone && idle >= cfg.stopProbeAfterIdleBatches && batch !== batches.last()) {
                stop = StopReason.DIMINISHING_RETURNS; break
            }
        }
        b.ranked().forEach { history.recordLatency(it.server.id, ctx.fingerprint, it.latency) }
        progress(SearchPhase.PROBING)

        // ---- 2. refine leaders ----
        if (stop == StopReason.COMPLETED || stop == StopReason.DIMINISHING_RETURNS) {
            val leaders = b.ranked().take(cfg.refineTop).map { it.server }
            if (leaders.isNotEmpty() && !control.finishNow) {
                b.resetProgress()
                progress(SearchPhase.REFINING)
                runner.run(
                    leaders, b,
                    ProbeParams(cfg.refineAttempts, cfg.probeSpacingMs, cfg.probeTimeoutMs, concurrency = cfg.probeBatch, earlyStop = false),
                    emit = {},
                ) { control.finishNow }
            }
        }
        val ranked = b.ranked(maxLoss = 0.4)
        ranked.forEach { history.recordLatency(it.server.id, ctx.fingerprint, it.latency) }
        if (control.finishNow && stop != StopReason.TIME_BUDGET) stop = StopReason.USER_FINISHED

        // ---- 3. shortlist: diverse by country + a few exploration slots ----
        val shortlist = shortlist(ranked, seeds, ctx.fingerprint)
        planned = shortlist.size
        val gate = ConnectionGate(tunnel, cfg.mode, reputation, recorder, ctx.fingerprint, clock)
        // Seeds from an interrupted search count as tested without costing data again.
        for (r in ranked) seeds[r.server.id]?.let { t -> accs[r.server.id] = Acc(r.server, r.latency, null).also { it.runs.add(t) } }
        tested = accs.size

        suspend fun test(c: Ranked): Boolean {
            val adm = when (val g = gate.connect(c.server, cfg.connectTimeoutMs)) {
                is GateResult.Open -> g.admitted
                is GateResult.Refused -> return false
            }
            val budget = minOf(cfg.perCandidateBytes, cfg.runCapBytes - bytes)
            if (budget <= 0) return false
            val t = tester.run(c.server, TestLimits(budget)) ?: return false
            bytes += t.bytesUsed
            val acc = accs.getOrPut(c.server.id) { Acc(c.server, c.latency, adm) }
            acc.admitted = adm
            acc.runs.add(t)
            history.recordThroughput(c.server.id, ctx.fingerprint, t)
            return true
        }

        if (stop == StopReason.COMPLETED || stop == StopReason.DIMINISHING_RETURNS) {
            var sinceLeader = 0
            for (c in shortlist) {
                if (control.checkpoint()) { stop = StopReason.USER_FINISHED; break }
                if (bytes >= cfg.runCapBytes) { stop = StopReason.DATA_BUDGET; break }
                if (timeUp()) { stop = StopReason.TIME_BUDGET; break }
                progress(SearchPhase.SPEED_TESTING)
                if (!test(c)) continue
                tested = accs.size
                val es = entries(); leaderboard = es
                val newLeader = es.firstOrNull()?.server?.id == c.server.id &&
                    (es.getOrNull(1) == null || es[0].score >= es[1].score * (1 + cfg.improvement))
                if (newLeader) sinceLeader = 0 else sinceLeader++
                progress(SearchPhase.SPEED_TESTING)
                if (tested >= cfg.minTestedForStop && sinceLeader >= cfg.stopAfterNoImprovement) { stop = StopReason.DIMINISHING_RETURNS; break }
            }
        }

        // ---- 4. confirm leaders ----
        if (!control.finishNow && stop != StopReason.TIME_BUDGET && stop != StopReason.DATA_BUDGET && accs.size >= 2) {
            progress(SearchPhase.CONFIRMING)
            var round = 0
            while (true) {
                for (e in entries().take(cfg.confirmTop)) {
                    if (control.finishNow || bytes >= cfg.runCapBytes || timeUp()) break
                    if (control.checkpoint()) break
                    ranked.firstOrNull { it.server.id == e.server.id }?.let { test(it) }
                }
                round++
                val es = entries(); leaderboard = es
                progress(SearchPhase.CONFIRMING)
                val decisive = es.size < 2 || es[0].score >= es[1].score * (1 + cfg.improvement)
                if (decisive || round > cfg.maxConfirmExtraRounds || control.finishNow || bytes >= cfg.runCapBytes || timeUp()) break
            }
        }
        if (control.finishNow) stop = StopReason.USER_FINISHED
        val finalEntries = entries(); leaderboard = finalEntries

        // ---- result ----
        fun report(winner: Server): BestReport {
            val margin = if (finalEntries.size >= 2 && finalEntries[0].score > 0) (finalEntries[0].score - finalEntries[1].score) / finalEntries[0].score else null
            val runs = accs[winner.id]?.runs?.size ?: 0
            val covered = probedCount >= pool.size
            val claim = when {
                stop == StopReason.USER_FINISHED -> BestClaim.EARLY_FINISH
                finalEntries.isEmpty() -> BestClaim.LATENCY_ONLY
                !covered -> BestClaim.PARTIAL_COVERAGE
                margin != null && margin >= cfg.improvement && runs >= 2 -> BestClaim.BEST_OF_TESTED_CONFIRMED
                else -> BestClaim.BEST_OF_TESTED
            }
            val why = "Probed $probedCount of ${pool.size} servers in $countriesSampled countries; speed-tested ${accs.size}. " + when (claim) {
                BestClaim.EARLY_FINISH -> "Stopped at your request."
                BestClaim.LATENCY_ONLY -> "No speed test completed; chosen by latency."
                BestClaim.PARTIAL_COVERAGE -> "Not every server was probed, so this is the best found, not a proven overall winner."
                BestClaim.BEST_OF_TESTED_CONFIRMED -> "Clear winner among those tested."
                BestClaim.BEST_OF_TESTED -> "Margin over the runner-up is small."
            }
            return BestReport(finalEntries, winner, probedCount, accs.size, bytes, elapsed(), why, pool.size, b.responded(), claim, margin, runs)
        }

        val candidatesInOrder = (finalEntries.map { it.server } + ranked.map { it.server }).distinctBy { it.id }.take(4)
        if (candidatesInOrder.isEmpty()) {
            withContext(NonCancellable) { tunnel.disconnect() }
            return SearchOutcome.Finished(null, null, StopReason.NO_SERVERS, ctx.previous)
        }
        if (!cfg.connectAtEnd) {
            withContext(NonCancellable) { tunnel.disconnect() }
            return SearchOutcome.Finished(report(candidatesInOrder.first()), null, stop, ctx.previous)
        }
        progress(SearchPhase.CONNECTING)
        for (s in candidatesInOrder) {
            val standing = accs[s.id]?.admitted
            val adm = if (standing != null && tunnel.currentServerId == s.id) {
                if (!gate.recheck(standing)) continue else standing
            } else when (val g = gate.connect(s, cfg.connectTimeoutMs)) {
                is GateResult.Open -> g.admitted
                is GateResult.Refused -> continue
            }
            progress(SearchPhase.DONE)
            return SearchOutcome.Finished(report(s), adm, stop, ctx.previous)
        }
        return SearchOutcome.Finished(report(candidatesInOrder.first()), null, stop, ctx.previous)
    }

    private fun floorSize(pool: List<Server>): Int =
        pool.groupBy { it.countryCode }.values.sumOf { minOf(it.size, cfg.perCountryFloor) }

    /** Probe order: per-country floors first (best predicted in each), then everything else by prediction, capped at [GlobalSearchConfig.maxProbed]. */
    internal fun stratify(pool: List<Server>, ctx: RunContext): List<Server> {
        fun est(s: Server) = Prioritizer.estimate(s, ctx, history) + Scoring.slowLinePenaltyMs(s) + random.nextDouble() * 3
        val scored = pool.associateWith { est(it) }
        val floor = pool.groupBy { it.countryCode }.values.flatMap { list -> list.sortedBy { scored.getValue(it) }.take(cfg.perCountryFloor) }
        val floorSet = floor.map { it.id }.toSet()
        val rest = pool.filter { it.id !in floorSet }.sortedBy { scored.getValue(it) }
        return (floor.sortedBy { scored.getValue(it) } + rest).take(cfg.maxProbed)
    }

    private fun shortlist(ranked: List<Ranked>, seeds: Map<String, ThroughputStats>, fingerprint: String): List<Ranked> {
        val fresh = ranked.filter { it.server.id !in seeds }
        val exploreSlots = (cfg.maxSpeedTests * cfg.explorationShare).toInt()
        val topSlots = cfg.maxSpeedTests - exploreSlots
        val perCountry = HashMap<String, Int>()
        val top = ArrayList<Ranked>()
        for (r in fresh) {
            if (top.size >= topSlots) break
            val n = perCountry.getOrDefault(r.server.countryCode, 0)
            if (n >= cfg.maxPerCountryInShortlist) continue
            perCountry[r.server.countryCode] = n + 1
            top.add(r)
        }
        val chosen = top.map { it.server.id }.toSet()
        val explore = fresh.filter { it.server.id !in chosen }
            .filter { history.throughputBonusMs(it.server.id, fingerprint) == 0.0 } // no recent throughput record for this network
            .shuffled(random).take(exploreSlots)
        // Interleave so exploration is not left until the data budget is gone.
        val out = ArrayList<Ranked>()
        val ti = top.iterator(); val ei = explore.iterator()
        while (ti.hasNext() || ei.hasNext()) {
            repeat(4) { if (ti.hasNext()) out.add(ti.next()) }
            if (ei.hasNext()) out.add(ei.next())
        }
        return out
    }
}
