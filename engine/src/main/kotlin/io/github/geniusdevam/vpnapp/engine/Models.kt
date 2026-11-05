package io.github.geniusdevam.vpnapp.engine

enum class Transport { UDP, TCP }

enum class ProbeMethod(val confidence: ProbeConfidence) {
    /** A real OpenVPN hard-reset round trip: the VPN service itself answered. */
    OPENVPN_UDP_RESET(ProbeConfidence.HIGH),
    /** TCP handshake with the VPN port: the port is open, but the OpenVPN service may still be dead. */
    TCP_CONNECT(ProbeConfidence.MEDIUM),
    /** UDP relay that needs tls-auth/crypt: we only reached TCP 443 on the same host. This says the HOST is up, nothing about the VPN service. */
    TCP_FALLBACK(ProbeConfidence.LOW),
    NONE(ProbeConfidence.NONE),
}

enum class ProbeConfidence { HIGH, MEDIUM, LOW, NONE }

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
    /** VPN Gate's own quality score, ping (measured from Japan, NOT from this phone), user count and traffic. Display/prior only. */
    val vpnGateScore: Long? = null,
    val vpnGatePingMs: Int? = null,
    val totalUsers: Long? = null,
    val totalTrafficBytes: Long? = null,
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

/** Why a connection attempt failed. Drives reputation (hard kinds earn a long block) and the history log. */
enum class FailureKind(val hard: Boolean) {
    /** The relay refused our credentials. */
    AUTH(true),
    /** TLS/certificate verification failed: possible impersonation or a misconfigured relay. */
    CERTIFICATE(true),
    /** The connection worked but violated the encryption policy. */
    POLICY(true),
    /** The profile was rejected before it reached the network. */
    CONFIG_REJECTED(true),
    TIMEOUT(false),
    /** We stopped it (user cancel, newer command). Never counted against the server. */
    CANCELLED(false),
    NETWORK(false),
    OTHER(false),
}

/**
 * Outcome of one connection attempt.
 *
 * Built only through [connected] / [failed], so the failure text can never land in the cipher field again
 * (the old positional constructor did exactly that for authentication errors).
 *
 * [cipher] is the data-channel cipher that was actually OBSERVED in the engine's log; it is null when the
 * engine did not report one. A cipher merely declared by the relay's profile is never put here.
 */
@ConsistentCopyVisibility
data class ConnectResult private constructor(
    val success: Boolean,
    val cipher: String?,
    val error: String?,
    val kind: FailureKind?,
) {
    /** True for security-relevant failures (TLS/certificate/auth/policy): the server gets a long block. */
    val hard: Boolean get() = kind?.hard == true
    val cipherVerified: Boolean get() = success && cipher != null

    companion object {
        fun connected(observedCipher: String?): ConnectResult = ConnectResult(true, observedCipher, null, null)
        fun failed(kind: FailureKind, error: String): ConnectResult = ConnectResult(false, null, error, kind)
    }
}

data class ProbeSample(val rttMs: Double?, val method: ProbeMethod)

data class Ranked(val server: Server, val latency: LatencyStats, val q: Double) {
    val confidence: ProbeConfidence get() = latency.method.confidence
}

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
    /** Progress of the worldwide search: see [SearchProgress]. */
    data class Search(val progress: SearchProgress) : EngineEvent
}

data class BestEntry(
    val server: Server,
    val latency: LatencyStats,
    val throughput: ThroughputStats?,
    val cipher: String?,
    val score: Double,
)

/**
 * Why a run ended without a connection. The first three describe the server pool, and are deliberately distinct (see [PoolSelection]).
 */
enum class FailReason { DIRECTORY_UNAVAILABLE, NO_SERVERS_IN_SCOPE, NO_SERVERS_MATCH_SETTINGS, COULD_NOT_CONNECT, TIMEOUT }

/** How Quick Connect arrived at its pick. Quick Connect is always approximate: it compares latency of a subset, within a time budget. */
data class SelectionInfo(
    /** Relays in the chosen scope that passed policy. */
    val poolSize: Int,
    /** How many of them were probed. */
    val probed: Int,
    /** How many answered. */
    val responded: Int,
    /** True when the pick rests on weaker evidence than a direct VPN-service probe (see [ProbeConfidence.LOW]). */
    val lowConfidence: Boolean,
    val approximate: Boolean = true,
)

sealed interface QuickOutcome {
    data class Connected(
        val server: Server, val cipher: String?, val cipherVerified: Boolean, val elapsedMs: Long,
        val selection: SelectionInfo = SelectionInfo(0, 0, 0, false),
    ) : QuickOutcome
    data class Failed(val reason: FailReason, val restore: Server?) : QuickOutcome
}

sealed interface BestOutcome {
    data class Connected(val report: BestReport, val cipher: String?, val cipherVerified: Boolean) : BestOutcome
    data class Failed(val reason: FailReason, val restore: Server?, val report: BestReport?) : BestOutcome
}

/** What the result of a Best/worldwide run may honestly be called. */
enum class BestClaim {
    /** Every relay in scope was latency-probed, the leaders were speed-tested repeatedly, and the winner's margin was decisive. */
    BEST_OF_TESTED_CONFIRMED,
    /** Best of the relays that were speed-tested; the margin over the runner-up was small or unconfirmed. */
    BEST_OF_TESTED,
    /** Only part of the relays in scope could be probed. */
    PARTIAL_COVERAGE,
    /** The user ended the run early. */
    EARLY_FINISH,
    /** No speed test could run; chosen by latency alone. */
    LATENCY_ONLY,
}

data class BestReport(
    val entries: List<BestEntry>,
    val winner: Server,
    val scannedCount: Int,
    val testedCount: Int,
    val bytesUsed: Long,
    val elapsedMs: Long,
    val reason: String,
    /** Relays in scope that passed policy (the denominator for coverage). */
    val poolSize: Int = scannedCount,
    /** Relays that answered at least one probe. */
    val respondedCount: Int = 0,
    val claim: BestClaim = BestClaim.BEST_OF_TESTED,
    /** (winner score - runner-up score) / winner score; null with fewer than two tested relays. */
    val marginOverRunnerUp: Double? = null,
    /** Number of speed-test runs behind the winner's numbers (more runs, steadier numbers). */
    val winnerRuns: Int = 0,
)
