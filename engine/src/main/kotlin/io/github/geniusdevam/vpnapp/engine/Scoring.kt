package io.github.geniusdevam.vpnapp.engine

import kotlin.math.max
import kotlin.math.min

data class ScoringConfig(
    val jitterWeight: Double = 2.0,
    val lossWeight: Double = 400.0,
    val sessionPenaltyMs: Double = 30.0,
    val sessionSaturation: Double = 50.0,
    val tcpPenaltyMs: Double = 15.0,
    val fallbackProbePenaltyMs: Double = 60.0,
)

object Scoring {
    /** Quick Connect score in milliseconds: lower is better. */
    fun quickScore(
        server: Server,
        stats: LatencyStats,
        repPenaltyMs: Double = 0.0,
        historyBonusMs: Double = 0.0,
        cfg: ScoringConfig = ScoringConfig(),
    ): Double {
        var q = stats.medianMs + cfg.jitterWeight * stats.jitterMs + cfg.lossWeight * stats.loss
        server.sessions?.let { q += cfg.sessionPenaltyMs * min(1.0, it / cfg.sessionSaturation) }
        q += slowLinePenaltyMs(server)
        if (server.transport == Transport.TCP) q += cfg.tcpPenaltyMs
        if (stats.method == ProbeMethod.TCP_FALLBACK) q += cfg.fallbackProbePenaltyMs
        return q + repPenaltyMs - historyBonusMs
    }

    fun slowLinePenaltyMs(server: Server): Double {
        val bps = server.reportedSpeedBps ?: return 0.0
        return when {
            bps < 10_000_000L -> 60.0
            bps < 50_000_000L -> 25.0
            else -> 0.0
        }
    }

    fun latencyStats(samples: List<Double?>, method: ProbeMethod): LatencyStats? {
        if (samples.isEmpty()) return null
        val ok = samples.filterNotNull()
        if (ok.isEmpty()) return null
        val sorted = ok.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        val jitter = if (ok.size < 2) 0.0 else ok.zipWithNext { a, b -> kotlin.math.abs(a - b) }.average()
        val loss = (samples.size - ok.size).toDouble() / samples.size
        return LatencyStats(median, jitter, loss, samples.size, method)
    }
}

enum class OptimizeFor(
    val down: Double, val up: Double, val loaded: Double, val idle: Double, val jitter: Double, val loss: Double,
) {
    BALANCED(0.50, 0.15, 0.20, 0.10, 0.05, 0.5),
    DOWNLOAD(0.70, 0.15, 0.08, 0.05, 0.02, 0.5),
    LOW_LATENCY(0.20, 0.10, 0.35, 0.20, 0.15, 1.0),
}

object Composite {
    class Sample(
        val server: Server,
        val latency: LatencyStats,
        val runs: List<ThroughputStats>,
        val cipher: String?,
    )

    /** Averages runs; drops noisy runs when a clean one exists. Null if no run. */
    fun average(runs: List<ThroughputStats>): ThroughputStats? {
        if (runs.isEmpty()) return null
        val use = runs.filter { !it.noisy }.ifEmpty { runs }
        return ThroughputStats(
            downMbps = use.map { it.downMbps }.average(),
            upMbps = use.map { it.upMbps }.average(),
            idleRttMs = use.map { it.idleRttMs }.average(),
            loadedRttMs = use.map { it.loadedRttMs }.average(),
            bytesUsed = runs.sumOf { it.bytesUsed },
            noisy = use.all { it.noisy },
        )
    }

    /** Scores every sample relative to the best in the set (higher is better). Samples without throughput score 0. */
    fun score(samples: List<Sample>, profile: OptimizeFor): List<BestEntry> {
        val tp = samples.associateWith { average(it.runs) }
        val tested = tp.values.filterNotNull()
        val maxDown = max(1e-9, tested.maxOfOrNull { it.downMbps } ?: 0.0)
        val maxUp = max(1e-9, tested.maxOfOrNull { it.upMbps } ?: 0.0)
        val minLoaded = max(1e-3, tested.minOfOrNull { it.loadedRttMs } ?: 1.0)
        val minIdle = max(1e-3, tested.minOfOrNull { it.idleRttMs } ?: 1.0)
        val minJit = samples.minOfOrNull { it.latency.jitterMs } ?: 0.0
        return samples.map { s ->
            val t = tp[s]
            val score = if (t == null) 0.0 else {
                profile.down * (t.downMbps / maxDown) +
                    profile.up * (t.upMbps / maxUp) +
                    profile.loaded * (minLoaded / max(1e-3, t.loadedRttMs)) +
                    profile.idle * (minIdle / max(1e-3, t.idleRttMs)) +
                    profile.jitter * ((minJit + 1) / (s.latency.jitterMs + 1)) -
                    profile.loss * s.latency.loss +
                    (if (CipherPolicy.isAead(s.cipher)) 0.03 else 0.0)
            }
            BestEntry(s.server, s.latency, t, s.cipher, score)
        }.sortedByDescending { it.score }
    }
}
