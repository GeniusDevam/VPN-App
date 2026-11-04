package io.github.geniusdevam.vpnapp.engine

import kotlinx.coroutines.delay

fun srv(
    id: String,
    iso: String = "JP",
    transport: Transport = Transport.UDP,
    speedBps: Long? = 100_000_000,
    sessions: Int? = 10,
    cipher: String? = "AES-128-CBC",
    tlsAuth: Boolean = false,
) = Server(
    id = id, ip = "10.0.0.1", port = 1194, transport = transport, countryCode = iso, countryName = iso,
    reportedSpeedBps = speedBps, sessions = sessions, uptimeMs = 1000, logPolicy = "2weeks", operator = "op",
    message = null, declaredCipher = cipher, tlsAuthOrCrypt = tlsAuth, config = "",
)

class FakeCatalog(var servers: List<Server>, private val delayMs: Long = 0) : Catalog {
    override suspend fun servers(maxAgeMs: Long, fetchTimeoutMs: Long): List<Server> {
        if (delayMs > 0) delay(delayMs)
        return servers
    }
}

/** rtt returns the round trip in ms, or null for no answer (costs the full timeout, like the real prober). */
class FakeProber(private val rtt: (Server) -> Double?) : LatencyProber {
    override suspend fun probe(server: Server, timeoutMs: Long): ProbeSample {
        val r = rtt(server)
        if (r == null || r > timeoutMs) {
            delay(timeoutMs)
            return ProbeSample(null, ProbeMethod.OPENVPN_UDP_RESET)
        }
        delay(r.toLong().coerceAtLeast(1))
        return ProbeSample(r, ProbeMethod.OPENVPN_UDP_RESET)
    }
}

class Behavior(
    val success: Boolean = true,
    val takesMs: Long = 3_000,
    /** True models a stuck engine that ignores the timeout we pass in. */
    val ignoresTimeout: Boolean = false,
    val cipher: String? = "AES-256-GCM",
)

class FakeTunnel(private val behavior: (Server) -> Behavior = { Behavior() }) : TunnelController {
    override var currentServerId: String? = null
    val connectLog = ArrayList<String>()
    var aborted = 0
    override suspend fun connect(server: Server, timeoutMs: Long): ConnectResult {
        connectLog.add(server.id)
        val b = behavior(server)
        if (b.ignoresTimeout) {
            delay(b.takesMs)
        } else if (b.takesMs > timeoutMs) {
            delay(timeoutMs)
            return ConnectResult(false, error = "timeout")
        } else {
            delay(b.takesMs)
        }
        return if (b.success) {
            currentServerId = server.id
            ConnectResult(true, cipher = b.cipher)
        } else ConnectResult(false, error = "failed")
    }
    override suspend fun abort() { aborted++ }
    override suspend fun disconnect() { currentServerId = null }
}

class FakeTester(private val stats: (Server) -> ThroughputStats?) : ThroughputTester {
    val runs = ArrayList<String>()
    override suspend fun run(server: Server, limits: TestLimits): ThroughputStats? {
        runs.add(server.id)
        delay(5_000)
        return stats(server)
    }
}

fun tp(down: Double, up: Double = down / 4, idle: Double = 30.0, loaded: Double = 60.0, bytes: Long = 10_000_000) =
    ThroughputStats(down, up, idle, loaded, bytes)
