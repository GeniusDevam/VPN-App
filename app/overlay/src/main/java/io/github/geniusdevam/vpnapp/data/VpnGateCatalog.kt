package io.github.geniusdevam.vpnapp.data

import io.github.geniusdevam.vpnapp.engine.Catalog
import io.github.geniusdevam.vpnapp.engine.DirectoryProcessor
import io.github.geniusdevam.vpnapp.engine.EndpointInfo
import io.github.geniusdevam.vpnapp.engine.ParseReport
import io.github.geniusdevam.vpnapp.engine.RefreshPolicy
import io.github.geniusdevam.vpnapp.engine.Server
import io.github.geniusdevam.vpnapp.engine.VpnGateParser
import io.github.geniusdevam.vpnapp.net.AndroidRoots
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fetches VPN Gate's public relay list over HTTPS ONLY. There is no plain-HTTP fallback: an on-path attacker could
 * rewrite relay addresses and certificates in an unauthenticated response, and nothing in the list lets the app
 * tell. If HTTPS fails, the app keeps serving the cached pool (and says so) instead of downgrading.
 *
 * Everything fetched is untrusted regardless: it is parsed defensively, each profile must pass the address and
 * server-identity policy, and the config is sanitized again right before connecting.
 *
 * Only one endpoint is used. VPN Gate publishes only part of its list to each client and states that this is deliberate,
 * so this class does not try to enumerate more than the service offers: it refreshes politely ([RefreshPolicy]:
 * at most every 10 minutes, back-off on errors, Retry-After honoured) and keeps what recent listings contained
 * ([io.github.geniusdevam.vpnapp.engine.DirectoryPool]), so different samples add up over time.
 */
class VpnGateCatalog : Catalog {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var inFlight: Deferred<Boolean>? = null
    private val refresh = RefreshPolicy()

    override suspend fun servers(maxAgeMs: Long, fetchTimeoutMs: Long): List<Server> {
        val now = System.currentTimeMillis()
        val pooled = Repo.pooledServers()
        val age = now - Repo.serversFetchedAt
        if (maxAgeMs > 0 && pooled.isNotEmpty() && age in 0..maxAgeMs) return pooled
        // A forced refresh (maxAge 0, e.g. Best Connect) still obeys the minimum interval and any back-off.
        if (!refresh.mayRefresh(now, forced = maxAgeMs <= 0L)) return pooled
        // With nothing cached the list is the whole job: give the download real time.
        val wait = if (pooled.isEmpty()) maxOf(fetchTimeoutMs, 15_000L) else fetchTimeoutMs
        val job = startFetch((wait / 2).toInt().coerceIn(3_000, 10_000))
        withTimeoutOrNull(wait) { job.await() }
        return Repo.pooledServers()
    }

    @Synchronized
    private fun startFetch(perAttemptMs: Int): Deferred<Boolean> {
        inFlight?.takeIf { it.isActive }?.let { return it }
        val d = scope.async { fetchAndApply(perAttemptMs) }
        inFlight = d
        return d
    }

    private suspend fun fetchAndApply(timeoutMs: Int): Boolean {
        val started = System.currentTimeMillis()
        refresh.onAttempt(started)
        val fetched = try { fetch(timeoutMs) } catch (e: Exception) { Fetched(null, "network error (${e.javaClass.simpleName})", null) }
        val report = fetched.report
        if (report == null) {
            refresh.onFailure(System.currentTimeMillis(), fetched.retryAfterMs)
            Repo.directoryFailed(started, fetched.error ?: "download failed")
            return false
        }
        val result = DirectoryProcessor.process(report, AndroidRoots.policy(), Repo.lastValidProfiles())
        if (!result.listingUsable) {
            // Suspiciously small or oversize: neither cached relays nor saved history may be aged by it.
            refresh.onFailure(System.currentTimeMillis())
            Repo.directoryFailed(started, "list rejected: ${result.unusableReason}", result.stats)
            return false
        }
        refresh.onSuccess(System.currentTimeMillis())
        Repo.directoryOk(started, result)
        return true
    }

    private class Fetched(val report: ParseReport?, val error: String?, val retryAfterMs: Long?)

    private fun fetch(timeoutMs: Int): Fetched {
        val url = URL(URL_HTTPS)
        require(url.protocol == "https")
        val c = url.openConnection() as HttpsURLConnection
        io.github.geniusdevam.vpnapp.net.LegacyTls.apply(c)
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false // a redirect could silently leave the vetted host
            c.setRequestProperty("User-Agent", "VPN-App/1 (Android)")
            val code = c.responseCode
            if (code == 429 || code == 503) {
                val ra = c.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.times(1000)
                return Fetched(null, "VPN Gate asked us to slow down (HTTP $code)", ra)
            }
            if (code != HttpURLConnection.HTTP_OK) return Fetched(null, "HTTP $code", null)
            val raw: InputStream = c.inputStream
            val body = Limited(raw, VpnGateParser.MAX_INPUT_CHARS.toLong())
            val report = try {
                body.bufferedReader(Charsets.UTF_8).useLines { VpnGateParser.parseDetailed(it) }
            } catch (_: TooLarge) {
                ParseReport(emptyList(), 0, emptyMap(), oversize = true)
            }
            return Fetched(report, null, null)
        } finally {
            c.disconnect()
        }
    }

    private class TooLarge : IOException("directory response too large")

    /** Stops reading after [max] bytes so a hostile or broken server cannot exhaust memory or time. */
    private class Limited(input: InputStream, private val max: Long) : FilterInputStream(input) {
        private var n = 0L
        override fun read(): Int = super.read().also { if (it >= 0 && ++n > max) throw TooLarge() }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also {
            if (it > 0) { n += it; if (n > max) throw TooLarge() }
        }
    }

    companion object {
        const val URL_HTTPS = "https://www.vpngate.net/api/iphone/"
    }
}
