package io.github.geniusdevam.vpnapp.engine

import kotlin.random.Random
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class BestConfig(
    val optimize: OptimizeFor = OptimizeFor.BALANCED,
    val catalogFetchTimeoutMs: Long = 15_000,
    val scanAttempts: Int = 5,
    val scanSpacingMs: Long = 400,
    val scanTimeoutMs: Long = 1_500,
    val scanConcurrency: Int = 48,
    val scanMax: Int = 400,
    val dropLoss: Double = 0.4,
    val maxCandidates: Int = 10,
    val minCandidates: Int = 8,
    val poolFactor: Double = 3.0,
    val poolAddMs: Double = 150.0,
    val connectTimeoutMs: Long = 15_000,
    val perCandidateBytes: Long = 30L * 1024 * 1024,
    val runCapBytes: Long = 300L * 1024 * 1024,
    val minTestedForStop: Int = 6,
    val stopAfterNoImprovement: Int = 4,
    val improvement: Double = 0.05,
    val confirmTop: Int = 3,
    val maxConfirmExtraRounds: Int = 2,
    val finalConnectTimeoutMs: Long = 15_000,
    val mode: EncryptionMode = EncryptionMode.COMPATIBLE,
    val scoring: ScoringConfig = ScoringConfig(),
)

/** Lets the UI end the run early ("Connect to current best now"). */
class BestControl {
    @Volatile var finishNow: Boolean = false
        private set
    fun finishNow() { finishNow = true }
}

/**
 * Best Connect: no time limit. Scans every server in scope, then speed-tests the leading candidates through
 * real tunnels, stops when newer candidates keep losing, re-tests the top few, and connects to the winner.
 * Cancel by cancelling the calling coroutine; the caller then restores [RunContext.previous].
 */
class BestConnectEngine(
    private val catalog: Catalog,
    prober: LatencyProber,
    private val tunnel: TunnelController,
    private val tester: ThroughputTester,
    private val history: History = NoHistory,
    private val reputation: Reputation = NoReputation,
    private val clock: Clock = MonotonicClock,
    private val cfg: BestConfig = BestConfig(),
    private val random: Random = Random.Default,
) {
    private val runner = ProbeRunner(prober, clock)

    private class Acc(val server: Server, val latency: LatencyStats, var cipher: String?) {
        val runs = ArrayList<ThroughputStats>()
    }

    suspend fun run(
        scope: Scope,
        ctx: RunContext = RunContext(),
        control: BestControl = BestControl(),
        emit: (EngineEvent) -> Unit = {},
    ): BestOutcome {
        var connected = false
        try {
            val out = attempt(scope, ctx, control, emit)
            connected = out is BestOutcome.Connected
            return out
        } finally {
            if (!connected) withContext(NonCancellable) { tunnel.abort() }
        }
    }

    private suspend fun attempt(scope: Scope, ctx: RunContext, control: BestControl, emit: (EngineEvent) -> Unit): BestOutcome {
        val start = clock.nowMs()
        emit(EngineEvent.Phase(PhaseId.LOADING_SERVERS))
        val pool = catalog.servers(0, cfg.catalogFetchTimeoutMs)
            .filter { scope.matches(it) && !reputation.isBlocked(it.id) && CipherPolicy.acceptsServer(it, cfg.mode) }
        if (pool.isEmpty()) return BestOutcome.Failed(FailReason.NO_SERVERS_IN_SCOPE, ctx.previous, null)

        // Stage 2: full latency scan.
        emit(EngineEvent.Phase(PhaseId.SCANNING, "${pool.size}"))
        val toScan = Prioritizer.pick(pool, ctx, history, cfg.scanMax, 0, random)
        val board = ProbeBoard(reputation, history, ctx.fingerprint, cfg.scoring)
        runner.run(
            toScan, board,
            ProbeParams(
                attempts = cfg.scanAttempts, spacingMs = cfg.scanSpacingMs, timeoutMs = cfg.scanTimeoutMs,
                concurrency = cfg.scanConcurrency, earlyStop = false,
            ),
            emit,
        ) { control.finishNow }
        val ranked = board.ranked(maxLoss = cfg.dropLoss)
        ranked.forEach { history.recordLatency(it.server.id, ctx.fingerprint, it.latency.medianMs) }
        val scanned = toScan.size

        // Candidate pool for speed testing.
        val candidates: List<Ranked> = if (ranked.isEmpty()) {
            pool.sortedBy { Prioritizer.estimate(it, ctx, history) }.take(cfg.maxCandidates)
                .map { Ranked(it, LatencyStats(Prioritizer.estimate(it, ctx, history), 0.0, 0.0, 0, ProbeMethod.NONE), 0.0) }
        } else {
            val bestQ = ranked.first().q
            val limit = maxOf(cfg.poolFactor * bestQ, bestQ + cfg.poolAddMs)
            val inRange = ranked.filter { it.q <= limit }
            val chosen = if (inRange.size >= cfg.minCandidates) inRange else ranked.take(cfg.minCandidates)
            // Slow home lines go last: they rarely win and cost volunteers bandwidth.
            val (fast, slow) = chosen.partition { (it.server.reportedSpeedBps ?: Long.MAX_VALUE) >= 10_000_000L }
            (fast + slow).take(cfg.maxCandidates)
        }

        val accs = LinkedHashMap<String, Acc>()
        var bytes = 0L
        fun entries(): List<BestEntry> =
            Composite.score(accs.values.filter { it.runs.isNotEmpty() }.map { Composite.Sample(it.server, it.latency, it.runs, it.cipher) }, cfg.optimize)

        suspend fun test(c: Ranked): ThroughputStats? {
            val r = tunnel.connect(c.server, cfg.connectTimeoutMs)
            if (!r.success) { reputation.recordFailure(c.server.id, r.hard); return null }
            if (!CipherPolicy.acceptsNegotiated(r.cipher, cfg.mode)) {
                reputation.recordFailure(c.server.id, true); return null
            }
            reputation.recordSuccess(c.server.id)
            val budget = minOf(cfg.perCandidateBytes, cfg.runCapBytes - bytes)
            if (budget <= 0) return null
            val t = tester.run(c.server, TestLimits(budget)) ?: return null
            bytes += t.bytesUsed
            val acc = accs.getOrPut(c.server.id) { Acc(c.server, c.latency, r.cipher) }
            acc.cipher = r.cipher ?: acc.cipher
            acc.runs.add(t)
            history.recordThroughput(c.server.id, ctx.fingerprint, t.downMbps)
            return t
        }

        // Stage 3: adaptive speed tests.
        emit(EngineEvent.Phase(PhaseId.SPEED_TESTING))
        var tested = 0
        var sinceLeader = 0
        for (c in candidates) {
            if (control.finishNow || bytes >= cfg.runCapBytes) break
            if (test(c) == null) continue
            tested++
            val es = entries()
            val top = es.firstOrNull()
            val second = es.getOrNull(1)
            val newLeader = top != null && top.server.id == c.server.id &&
                (second == null || top.score >= second.score * (1 + cfg.improvement))
            if (newLeader) sinceLeader = 0 else sinceLeader++
            emit(EngineEvent.Leaderboard(es, tested, candidates.size, bytes))
            if (tested >= cfg.minTestedForStop && sinceLeader >= cfg.stopAfterNoImprovement) break
        }

        // Stage 4: confirm the leaders, interleaved.
        if (!control.finishNow && accs.size >= 2) {
            emit(EngineEvent.Phase(PhaseId.CONFIRMING))
            var round = 0
            while (true) {
                val top = entries().take(cfg.confirmTop)
                for (e in top) {
                    if (control.finishNow || bytes >= cfg.runCapBytes) break
                    val c = candidates.firstOrNull { it.server.id == e.server.id } ?: continue
                    test(c)
                }
                round++
                val es = entries()
                emit(EngineEvent.Leaderboard(es, tested, candidates.size, bytes))
                val decisive = es.size < 2 || es[0].score >= es[1].score * (1 + cfg.improvement)
                if (decisive || round > cfg.maxConfirmExtraRounds || control.finishNow || bytes >= cfg.runCapBytes) break
            }
        }

        // Stage 5: connect to the winner (fall back to the next best).
        emit(EngineEvent.Phase(PhaseId.CONNECTING))
        val finalEntries = entries()
        val order: List<Server> =
            (finalEntries.map { it.server } + candidates.map { it.server } + ranked.map { it.server })
                .distinctBy { it.id }.take(4)
        var report: BestReport? = null
        for ((i, s) in order.withIndex()) {
            val cipher: String?
            if (tunnel.currentServerId == s.id) {
                cipher = accs[s.id]?.cipher
            } else {
                emit(EngineEvent.Connecting(s, i + 1))
                val r = tunnel.connect(s, cfg.finalConnectTimeoutMs)
                if (!r.success) { reputation.recordFailure(s.id, r.hard); continue }
                if (!CipherPolicy.acceptsNegotiated(r.cipher, cfg.mode)) {
                    tunnel.disconnect(); reputation.recordFailure(s.id, true); continue
                }
                cipher = r.cipher
            }
            val why = when {
                control.finishNow -> "Connected to the leader when you asked to finish early."
                finalEntries.isEmpty() -> "No speed test could run; chose the lowest-latency server."
                else -> "Highest overall score after ${accs.size} speed tests."
            }
            report = BestReport(finalEntries, s, scanned, accs.size, bytes, clock.nowMs() - start, why)
            return BestOutcome.Connected(report, cipher)
        }
        return BestOutcome.Failed(
            FailReason.COULD_NOT_CONNECT, ctx.previous,
            BestReport(finalEntries, order.firstOrNull() ?: pool.first(), scanned, accs.size, bytes, clock.nowMs() - start, "Could not connect"),
        )
    }
}
