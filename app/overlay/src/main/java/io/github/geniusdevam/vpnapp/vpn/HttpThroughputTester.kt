package io.github.geniusdevam.vpnapp.vpn

import io.github.geniusdevam.vpnapp.engine.TestLimits
import io.github.geniusdevam.vpnapp.engine.ThroughputStats
import io.github.geniusdevam.vpnapp.engine.ThroughputTester
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Measures speed through the active tunnel (the app's own traffic goes through the VPN unless excluded).
 * Uses Cloudflare's public speed-test endpoints over HTTPS. Tests are short to spare volunteers' bandwidth.
 *
 * Honesty notes:
 *  - Byte counts are payload bytes plus a [OVERHEAD] allowance for TLS/HTTP framing, so the data cap is conservative;
 *    they are still estimates, and bytes already in flight when a transfer is cut are not counted exactly.
 *  - Before measuring, the test checks that its traffic really leaves through the VPN: the egress IP seen through the
 *    default route must differ from the one seen through the physical network ([directIp]). If they match, the test did
 *    not go through the tunnel and no result is returned.
 *  - If the physical network changes during the test ([networkToken]), the result is discarded.
 *  - These are Cloudflare's public speed-test endpoints; their availability and terms can change.
 */
class HttpThroughputTester(
    private val base: String = "https://speed.cloudflare.com",
    /** Egress IP seen when bypassing the VPN (bound to the physical network), or null if unavailable. */
    private val directIp: (() -> String?)? = null,
    /** Identifies the physical network in use; if it differs at the end of a test from the start, the result is discarded. */
    private val networkToken: (() -> Long?)? = null,
) : ThroughputTester {

    override suspend fun run(server: io.github.geniusdevam.vpnapp.engine.Server, limits: TestLimits): ThroughputStats? =
        try {
            coroutineScope { measure(limits) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private suspend fun measure(limits: TestLimits): ThroughputStats? {
        val token0 = networkToken?.invoke()
        if (!awaitTunnelRoute()) return null // not (yet) routed through the VPN: refuse to report a number
        val idle = idleRtt() ?: return null
        val budgetBytes = (limits.maxBytes / OVERHEAD).toLong()

        // ---- download ----
        val downBytes = AtomicLong(0)
        val stop = AtomicBoolean(false)
        val loaded = ArrayList<Double>()
        val downBudget = (budgetBytes * 0.7).toLong()
        var downMbps = 0.0
        coroutineScope {
            val workers = (1..4).map {
                async(Dispatchers.IO) { downloadLoop(downBytes) { stop.get() || downBytes.get() >= downBudget } }
            }
            val pinger = async(Dispatchers.IO) {
                while (!stop.get()) {
                    rtt()?.let { synchronized(loaded) { loaded.add(it) } }
                    delay(300)
                }
            }
            val start = System.nanoTime()
            var rampBytes = 0L
            var rampAt = 0L
            val samples = ArrayList<Pair<Long, Long>>() // (ms, bytes)
            while (true) {
                delay(250)
                val ms = (System.nanoTime() - start) / 1_000_000
                val b = downBytes.get()
                samples.add(ms to b)
                if (rampAt == 0L && ms >= 1_000) { rampAt = ms; rampBytes = b }
                if (ms >= limits.maxDownMs || b >= downBudget) break
                if (ms >= 3_000 && isStable(samples, ms)) break
            }
            stop.set(true)
            val endMs = samples.last().first
            val endBytes = samples.last().second
            val seconds = ((endMs - rampAt).coerceAtLeast(1)) / 1000.0
            downMbps = if (rampAt > 0) (endBytes - rampBytes) * 8 / 1e6 / seconds else endBytes * 8 / 1e6 / (endMs / 1000.0)
            pinger.cancel()
            workers.awaitAll()
        }
        if (downBytes.get() <= 0) return null

        // ---- upload ----
        val upBytes = AtomicLong(0)
        val upMs = limits.maxUpMs
        val upStart = System.nanoTime()
        coroutineScope {
            (1..2).map {
                async(Dispatchers.IO) {
                    uploadLoop(upBytes) { (System.nanoTime() - upStart) / 1_000_000 >= upMs || upBytes.get() >= budgetBytes * 0.3 }
                }
            }.awaitAll()
        }
        val upSeconds = ((System.nanoTime() - upStart) / 1e9).coerceAtLeast(0.2)
        val upMbps = upBytes.get() * 8 / 1e6 / upSeconds

        if (networkToken != null && networkToken.invoke() != token0) return null // Wi-Fi/cellular switched mid-test: the numbers mean nothing
        val loadedMedian = synchronized(loaded) { median(loaded) } ?: idle
        return ThroughputStats(
            downMbps = downMbps, upMbps = upMbps, idleRttMs = idle, loadedRttMs = loadedMedian,
            bytesUsed = ((downBytes.get() + upBytes.get()) * OVERHEAD).toLong(),
        )
    }

    /** Waits (up to ~6 s) until a request through the default route exits somewhere other than the phone's own connection. */
    private suspend fun awaitTunnelRoute(): Boolean {
        val direct = withContext(Dispatchers.IO) { directIp?.invoke() }
        repeat(12) {
            val seen = withContext(Dispatchers.IO) { egressIp() }
            if (seen != null && (direct == null || seen != direct)) return true
            delay(500)
        }
        return false
    }

    /** `ip=` from Cloudflare's trace endpoint: the address the internet sees for this request. */
    private fun egressIp(): String? = try {
        val c = open("$base/cdn-cgi/trace")
        try {
            if (c.responseCode != 200) null
            else c.inputStream.use { it.readBytes() }.decodeToString().lineSequence()
                .firstOrNull { it.startsWith("ip=") }?.substring(3)?.trim()?.takeIf { it.length in 3..45 }
        } finally { c.disconnect() }
    } catch (_: Exception) { null }

    private fun isStable(samples: List<Pair<Long, Long>>, nowMs: Long): Boolean {
        val window = samples.filter { it.first >= nowMs - 1_500 }
        if (window.size < 5) return false
        val rates = window.zipWithNext { a, b -> (b.second - a.second).toDouble() / (b.first - a.first).coerceAtLeast(1) }
        val mean = rates.average()
        if (mean <= 0) return false
        val sd = kotlin.math.sqrt(rates.map { (it - mean) * (it - mean) }.average())
        return sd / mean < 0.10
    }

    private suspend fun idleRtt(): Double? = withContext(Dispatchers.IO) {
        rtt() // warm-up (TLS handshake)
        val r = (1..4).mapNotNull { rtt() }
        median(r)
    }

    private fun rtt(): Double? = try {
        val c = open("$base/__down?bytes=0")
        val t0 = System.nanoTime()
        c.responseCode
        c.inputStream.use { it.readBytes() }
        val ms = (System.nanoTime() - t0) / 1e6
        c.disconnect()
        ms
    } catch (_: Exception) { null }

    private fun downloadLoop(counter: AtomicLong, shouldStop: () -> Boolean) {
        val buf = ByteArray(64 * 1024)
        try {
            while (!shouldStop()) {
                val c = open("$base/__down?bytes=25000000")
                c.inputStream.use { input ->
                    while (!shouldStop()) {
                        val n = input.read(buf)
                        if (n < 0) break
                        counter.addAndGet(n.toLong())
                    }
                }
                c.disconnect()
            }
        } catch (_: Exception) {
        }
    }

    private fun uploadLoop(counter: AtomicLong, shouldStop: () -> Boolean) {
        val chunk = Random.nextBytes(64 * 1024)
        try {
            while (!shouldStop()) {
                val c = open("$base/__up")
                c.requestMethod = "POST"
                c.doOutput = true
                c.setFixedLengthStreamingMode(4L * 1024 * 1024)
                c.outputStream.use { out ->
                    var sent = 0
                    while (sent < 4 * 1024 * 1024 && !shouldStop()) {
                        out.write(chunk)
                        out.flush()
                        sent += chunk.size
                        counter.addAndGet(chunk.size.toLong())
                    }
                }
                try { c.responseCode } catch (_: Exception) {}
                c.disconnect()
            }
        } catch (_: Exception) {
        }
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        io.github.geniusdevam.vpnapp.net.LegacyTls.apply(c)
        c.connectTimeout = 5_000
        c.readTimeout = 5_000
        c.useCaches = false
        c.setRequestProperty("User-Agent", "VPN-App/1 (Android)")
        return c
    }

    private companion object { const val OVERHEAD = 1.06 }

    private fun median(v: List<Double>): Double? {
        if (v.isEmpty()) return null
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
