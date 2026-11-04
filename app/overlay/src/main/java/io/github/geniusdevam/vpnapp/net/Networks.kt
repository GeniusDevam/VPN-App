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

    fun isMetered(ctx: Context): Boolean {
        val cm = cm(ctx)
        val n = physicalNetwork(ctx) ?: return cm.isActiveNetworkMetered
        return cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
    }

    /** Privacy-preserving id of the current network: no SSID, no location permission. */
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
        val h = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
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
