package io.github.geniusdevam.vpnapp.data

import io.github.geniusdevam.vpnapp.engine.Catalog
import io.github.geniusdevam.vpnapp.engine.Server
import io.github.geniusdevam.vpnapp.engine.VpnGateParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fetches VPN Gate's public relay list. HTTPS is tried first; plain HTTP is a fallback because some VPN Gate
 * endpoints only offer it. Everything fetched is untrusted: it is parsed defensively and every config is
 * sanitized again right before connecting.
 */
class VpnGateCatalog : Catalog {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var inFlight: Deferred<List<Server>?>? = null

    private val urls = listOf(
        "https://www.vpngate.net/api/iphone/",
        "http://www.vpngate.net/api/iphone/",
    )

    override suspend fun servers(maxAgeMs: Long, fetchTimeoutMs: Long): List<Server> {
        val cached = Repo.servers.value
        val age = System.currentTimeMillis() - Repo.serversFetchedAt
        if (maxAgeMs > 0 && cached.isNotEmpty() && age in 0..maxAgeMs) return cached
        // With nothing cached the list is the whole job: give the 1.3 MB download real time (Quick Connect's
        // 35 s budget has room). With a cache we only wait briefly and fall back to the stale list.
        val wait = if (cached.isEmpty()) maxOf(fetchTimeoutMs, 15_000L) else fetchTimeoutMs
        val job = startFetch((wait / 2).toInt().coerceIn(3_000, 10_000))
        val fresh = withTimeoutOrNull(wait) { job.await() }
        return if (!fresh.isNullOrEmpty()) fresh else cached
    }

    @Synchronized
    private fun startFetch(perAttemptMs: Int): Deferred<List<Server>?> {
        inFlight?.takeIf { it.isActive }?.let { return it }
        val d = scope.async {
            val list = fetchBlocking(perAttemptMs)
            if (!list.isNullOrEmpty()) Repo.saveServers(list)
            list
        }
        inFlight = d
        return d
    }

    private fun fetchBlocking(timeoutMs: Int): List<Server>? {
        for (u in urls) {
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs
                c.setRequestProperty("User-Agent", "VPN-App/1 (Android)")
                if (c.responseCode != 200) { c.disconnect(); continue }
                val text = c.inputStream.use { readLimited(it, VpnGateParser.MAX_INPUT_CHARS) } ?: continue
                c.disconnect()
                val list = VpnGateParser.parse(text)
                if (list.isNotEmpty()) return list
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun readLimited(input: InputStream, max: Int): String? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) return null
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
