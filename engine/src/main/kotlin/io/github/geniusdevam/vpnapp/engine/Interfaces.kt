package io.github.geniusdevam.vpnapp.engine

fun interface Clock { fun nowMs(): Long }

object MonotonicClock : Clock { override fun nowMs(): Long = System.nanoTime() / 1_000_000 }

interface Catalog {
    /** Returns the current server list. Implementations serve a cache if the network is slow. */
    suspend fun servers(maxAgeMs: Long, fetchTimeoutMs: Long): List<Server>
}

interface LatencyProber {
    /** One round-trip measurement. Must return within about [timeoutMs]. Never throws for network errors. */
    suspend fun probe(server: Server, timeoutMs: Long): ProbeSample
}

interface TunnelController {
    /** Id of the server we are connected to right now, if any. */
    val currentServerId: String?
    /** Connect (replacing any current tunnel). Must be cancellable. */
    suspend fun connect(server: Server, timeoutMs: Long): ConnectResult
    /** Stop an in-progress connection attempt; keeps nothing half-open. */
    suspend fun abort()
    suspend fun disconnect()
}

interface ThroughputTester {
    /** Measure through the tunnel, which is currently connected to [server]. Null if the test could not run. */
    suspend fun run(server: Server, limits: TestLimits): ThroughputStats?
}

interface History {
    /** Expected median RTT for this server on this network, if recently measured. */
    fun latencyPrior(serverId: String, fingerprint: String): Double?
    /** Score bonus in ms (0..15) for servers that recently performed well. */
    fun throughputBonusMs(serverId: String, fingerprint: String): Double
    fun recordLatency(serverId: String, fingerprint: String, medianMs: Double)
    fun recordThroughput(serverId: String, fingerprint: String, downMbps: Double)
}

interface Reputation {
    fun isBlocked(serverId: String): Boolean
    fun penaltyMs(serverId: String): Double
    fun recordFailure(serverId: String, hard: Boolean)
    fun recordSuccess(serverId: String)
}

object NoHistory : History {
    override fun latencyPrior(serverId: String, fingerprint: String): Double? = null
    override fun throughputBonusMs(serverId: String, fingerprint: String): Double = 0.0
    override fun recordLatency(serverId: String, fingerprint: String, medianMs: Double) {}
    override fun recordThroughput(serverId: String, fingerprint: String, downMbps: Double) {}
}

object NoReputation : Reputation {
    override fun isBlocked(serverId: String) = false
    override fun penaltyMs(serverId: String) = 0.0
    override fun recordFailure(serverId: String, hard: Boolean) {}
    override fun recordSuccess(serverId: String) {}
}

/**
 * Receives one record per connection attempt, for the permanent local history. Implementations must be cheap
 * and thread-safe; they run on the connecting coroutine.
 */
interface AttemptRecorder {
    fun connectSucceeded(serverId: String, fingerprint: String, connectMs: Long, cipher: String?, cipherVerified: Boolean)
    fun connectFailed(serverId: String, fingerprint: String, kind: FailureKind, connectMs: Long)
}

object NoRecorder : AttemptRecorder {
    override fun connectSucceeded(serverId: String, fingerprint: String, connectMs: Long, cipher: String?, cipherVerified: Boolean) {}
    override fun connectFailed(serverId: String, fingerprint: String, kind: FailureKind, connectMs: Long) {}
}
