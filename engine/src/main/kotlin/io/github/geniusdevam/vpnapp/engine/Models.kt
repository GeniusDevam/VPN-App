package io.github.geniusdevam.vpnapp.engine

enum class Transport { UDP, TCP }

enum class ProbeMethod { OPENVPN_UDP_RESET, TCP_CONNECT, TCP_FALLBACK, NONE }

/** One VPN Gate relay. [config] is the raw, UNTRUSTED OpenVPN config; always pass it through [ConfigSanitizer]. */
data class Server(
    val id: String,
    val ip: String,
    val port: Int,
    val transport: Transport,
    val countryCode: String,
    val countryName: String,
    val reportedSpeedBps: Long?,
    val sessions: Int?,
    val uptimeMs: Long?,
    val logPolicy: String?,
    val operator: String?,
    val message: String?,
    val declaredCipher: String?,
    val tlsAuthOrCrypt: Boolean,
    val config: String,
)

sealed interface Scope {
    data object Any : Scope
    data class Country(val iso: String) : Scope
}

fun Scope.matches(s: Server): Boolean = when (this) {
    Scope.Any -> true
    is Scope.Country -> s.countryCode.equals(iso, ignoreCase = true)
}

data class LatencyStats(
    val medianMs: Double,
    val jitterMs: Double,
    val loss: Double,
    val samples: Int,
    val method: ProbeMethod,
)

data class ThroughputStats(
    val downMbps: Double,
    val upMbps: Double,
    val idleRttMs: Double,
    val loadedRttMs: Double,
    val bytesUsed: Long,
    val noisy: Boolean = false,
)

data class ConnectResult(
    val success: Boolean,
    val cipher: String? = null,
    val error: String? = null,
    /** True for security-relevant failures (TLS/certificate/auth): the server gets a long block. */
    val hard: Boolean = false,
)

data class ProbeSample(val rttMs: Double?, val method: ProbeMethod)

data class Ranked(val server: Server, val latency: LatencyStats, val q: Double)

data class TestLimits(val maxBytes: Long, val maxDownMs: Long = 6_000, val maxUpMs: Long = 3_000)

/** Where this run is happening; not persisted. */
data class RunContext(
    val userCountry: String? = null,
    val fingerprint: String = "unknown",
    /** The server the user is connected to now; restored by the caller if the run fails. */
    val previous: Server? = null,
    val favorites: Set<String> = emptySet(),
)

enum class PhaseId { LOADING_SERVERS, SCANNING, SPEED_TESTING, CONFIRMING, CONNECTING }

sealed interface EngineEvent {
    data class Phase(val phase: PhaseId, val detail: String? = null) : EngineEvent
    data class ProbeProgress(val done: Int, val total: Int, val top: List<Ranked>) : EngineEvent
    data class Connecting(val server: Server, val attempt: Int) : EngineEvent
    data class Leaderboard(
        val entries: List<BestEntry>,
        val tested: Int,
        val planned: Int,
        val bytesUsed: Long,
    ) : EngineEvent
}

data class BestEntry(
    val server: Server,
    val latency: LatencyStats,
    val throughput: ThroughputStats?,
    val cipher: String?,
    val score: Double,
)

enum class FailReason { NO_SERVERS_IN_SCOPE, COULD_NOT_CONNECT, TIMEOUT }

sealed interface QuickOutcome {
    data class Connected(val server: Server, val cipher: String?, val elapsedMs: Long) : QuickOutcome
    data class Failed(val reason: FailReason, val restore: Server?) : QuickOutcome
}

sealed interface BestOutcome {
    data class Connected(val report: BestReport, val cipher: String?) : BestOutcome
    data class Failed(val reason: FailReason, val restore: Server?, val report: BestReport?) : BestOutcome
}

data class BestReport(
    val entries: List<BestEntry>,
    val winner: Server,
    val scannedCount: Int,
    val testedCount: Int,
    val bytesUsed: Long,
    val elapsedMs: Long,
    val reason: String,
)
