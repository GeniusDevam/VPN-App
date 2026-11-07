package io.github.geniusdevam.vpnapp.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import java.security.MessageDigest
import java.util.Locale

object Networks {
    private fun cm(ctx: Context) = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun isPhysical(cm: ConnectivityManager, n: Network): Boolean {
        val c = cm.getNetworkCapabilities(n) ?: return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    /** The real Wi-Fi/mobile network underneath any VPN. Sockets bound to it bypass the tunnel. */
    @Suppress("DEPRECATION")
    fun physicalNetwork(ctx: Context): Network? {
        val cm = cm(ctx)
        cm.activeNetwork?.let { if (isPhysical(cm, it)) return it }
        val all = cm.allNetworks.filter { isPhysical(cm, it) }
        return all.firstOrNull { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true }
            ?: all.firstOrNull()
    }

    /** Stable identifier of the current physical network (changes when Wi-Fi/cellular switch). */
    fun networkToken(ctx: Context): Long? = physicalNetwork(ctx)?.networkHandle

    /** The address the internet sees for a request bound to the physical network (bypassing any VPN). Null if it cannot be determined. */
    fun directEgressIp(ctx: Context, base: String = "https://speed.cloudflare.com"): String? = try {
        val n = physicalNetwork(ctx) ?: throw IllegalStateException()
        val c = n.openConnection(java.net.URL("$base/cdn-cgi/trace")) as java.net.HttpURLConnection
        LegacyTls.apply(c)
        c.connectTimeout = 4_000; c.readTimeout = 4_000
        try {
            if (c.responseCode != 200) null
            else c.inputStream.use { it.readBytes() }.decodeToString().lineSequence()
                .firstOrNull { it.startsWith("ip=") }?.substring(3)?.trim()?.takeIf { it.length in 3..45 }
        } finally { c.disconnect() }
    } catch (_: Exception) { null }

    fun isMetered(ctx: Context): Boolean {
        val cm = cm(ctx)
        val n = physicalNetwork(ctx) ?: return cm.isActiveNetworkMetered
        return cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
    }

    @Volatile private var salt: ByteArray? = null

    /**
     * Random per-install secret mixed into the network fingerprint, so the stored hashes cannot be matched against
     * hashes computed elsewhere (another device, a leaked file) and reveal where the phone has been.
     * Stored in the encrypted vault; backups are disabled.
     */
    private fun salt(ctx: Context): ByteArray {
        salt?.let { return it }
        synchronized(this) {
            salt?.let { return it }
            val existing = io.github.geniusdevam.vpnapp.data.SecureVault.read(ctx, "netsalt")
            val s = existing ?: ByteArray(16).also {
                java.security.SecureRandom().nextBytes(it)
                runCatching { io.github.geniusdevam.vpnapp.data.SecureVault.write(ctx, "netsalt", it) }
            }
            salt = s
            return s
        }
    }

    /** Privacy-preserving id of the current network: no SSID, no location permission, salted per install. */
    fun fingerprint(ctx: Context): String {
        val cm = cm(ctx)
        val n = physicalNetwork(ctx) ?: return "none"
        val caps = cm.getNetworkCapabilities(n)
        val transport = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cell"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "eth"
            else -> "other"
        }
        val lp = cm.getLinkProperties(n)
        val raw = buildString {
            append(transport)
            lp?.dnsServers?.forEach { append('|').append(it.hostAddress) }
            lp?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.let { append('|').append(it.hostAddress) }
            if (transport == "cell") {
                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                append('|').append(tm?.networkOperator ?: "")
            }
        }
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt(ctx))
        val h = md.digest(raw.toByteArray())
        return h.take(8).joinToString("") { "%02x".format(it) }
    }

    /** Rough user country for ranking candidates before measuring. No location permission needed. */
    fun userCountry(ctx: Context): String? {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val c = tm?.networkCountryIso?.takeIf { it.length == 2 }
            ?: tm?.simCountryIso?.takeIf { it.length == 2 }
            ?: Locale.getDefault().country.takeIf { it.length == 2 }
        return c?.uppercase()
    }
}
