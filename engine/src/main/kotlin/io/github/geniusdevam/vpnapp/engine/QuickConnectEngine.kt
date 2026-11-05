package io.github.geniusdevam.vpnapp.engine

import kotlin.random.Random
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class QuickConfig(
    val budgetMs: Long = 35_000,
    val cacheMaxAgeMs: Long = 30L * 60_000,
    val fetchTimeoutMs: Long = 4_000,
    val maxCandidates: Int = 64,
    val exploreCount: Int = 4,
    val attempts: Int = 3,
    val spacingMs: Long = 250,
    val probeTimeoutMs: Long = 1_000,
    val concurrency: Int = 64,
    val probeHardCapMs: Long = 6_000,
    val tryTop: Int = 4,
    val minConnectWindowMs: Long = 6_000,
    val firstConnectTimeoutMs: Long = 12_000,
    val laterConnectTimeoutMs: Long = 10_000,
    val connectMarginMs: Long = 500,
    val mode: EncryptionMode = EncryptionMode.COMPATIBLE,
    val scoring: ScoringConfig = ScoringConfig(),
)

/**
 * Quick Connect: probe the most promising servers in parallel, stop as soon as the picture stabilises,
 * then connect to the best one (failing over to the next). The whole run is wrapped in a hard timeout of
 * [QuickConfig.budgetMs]; the result is always [QuickOutcome.Connected] or [QuickOutcome.Failed] by then.
 * On failure the caller restores [QuickOutcome.Failed.restore] (the previous connection) in the background.
 */
class QuickConnectEngine(
    private val catalog: Catalog,
    prober: LatencyProber,
    private val tunnel: TunnelController,
    private val history: History = NoHistory,
    private val reputation: Reputation = NoReputation,
    private val clock: Clock = MonotonicClock,
    private val cfg: QuickConfig = QuickConfig(),
    private val random: Random = Random.Default,
    private val recorder: AttemptRecorder = NoRecorder,
) {
    private val runner = ProbeRunner(prober, clock)

    suspend fun run(scope: Scope, ctx: RunContext = RunContext(), emit: (EngineEvent) -> Unit = {}): QuickOutcome {
        val start = clock.nowMs()
        var connected = false
        try {
            val out = withTimeoutOrNull(cfg.budgetMs) { attempt(scope, ctx, start, emit) }
                ?: return QuickOutcome.Failed(FailReason.TIMEOUT, ctx.previous)
            connected = out is QuickOutcome.Connected
            return out
        } finally {
            if (!connected) withContext(NonCancellable) { tunnel.abort() }
        }
    }

    private suspend fun attempt(scope: Scope, ctx: RunContext, start: Long, emit: (EngineEvent) -> Unit): QuickOutcome {
        emit(EngineEvent.Phase(PhaseId.LOADING_SERVERS))
        val pool = when (val sel = PoolSelection.select(catalog.servers(cfg.cacheMaxAgeMs, cfg.fetchTimeoutMs), scope, cfg.mode, reputation)) {
            is PoolResult.Ready -> sel.pool
            is PoolResult.Empty -> return QuickOutcome.Failed(sel.reason, ctx.previous)
        }

        val candidates = Prioritizer.pick(pool, ctx, history, cfg.maxCandidates, cfg.exploreCount, random)
        emit(EngineEvent.Phase(PhaseId.SCANNING, "${candidates.size}"))
        val board = ProbeBoard(reputation, history, ctx.fingerprint, cfg.scoring)
        runner.run(
            candidates, board,
            ProbeParams(
                attempts = cfg.attempts, spacingMs = cfg.spacingMs, timeoutMs = cfg.probeTimeoutMs,
                concurrency = cfg.concurrency, earlyStop = true, hardCapMs = cfg.probeHardCapMs,
            ),
            emit,
        )
        val ranked = board.ranked()
        val info = SelectionInfo(
            poolSize = pool.size, probed = candidates.size, responded = board.responded(),
            lowConfidence = ranked.take(cfg.tryTop).any { it.confidence == ProbeConfidence.LOW },
        )
        ranked.forEach { history.recordLatency(it.server.id, ctx.fingerprint, it.latency) }

        val order: List<Server> =
            if (ranked.isNotEmpty()) ranked.take(cfg.tryTop).map { it.server }
            else candidates.sortedBy { Prioritizer.estimate(it, ctx, history) }.take(3)

        emit(EngineEvent.Phase(PhaseId.CONNECTING))
        val gate = ConnectionGate(tunnel, cfg.mode, reputation, recorder, ctx.fingerprint, clock)
        var n = 0
        for (s in order) {
            val remaining = start + cfg.budgetMs - clock.nowMs()
            if (remaining < cfg.minConnectWindowMs) break
            n++
            emit(EngineEvent.Connecting(s, n))
            val timeout = minOf(
                if (n == 1) cfg.firstConnectTimeoutMs else cfg.laterConnectTimeoutMs,
                remaining - cfg.connectMarginMs,
            )
            when (val r = gate.connect(s, timeout)) {
                is GateResult.Open -> return QuickOutcome.Connected(s, r.admitted.cipher, r.admitted.cipherVerified, clock.nowMs() - start, info)
                is GateResult.Refused -> Unit // reputation and history were updated by the gate; try the next server
            }
        }
        return QuickOutcome.Failed(FailReason.COULD_NOT_CONNECT, ctx.previous)
    }
}
