package io.github.geniusdevam.vpnapp.net

import android.content.Context
import android.os.Build
import io.github.geniusdevam.vpnapp.engine.ServerAuthPolicy
import java.net.URLConnection
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * Android 6.0 and 7.0 predate the Let's Encrypt roots (ISRG Root X1 and X2 were added in Android 7.1.1), so any HTTPS site with a
 * Let's Encrypt certificate fails there with "Trust anchor for certification path not found". Android 7.0 could take the roots from a
 * network security config, but Android 6.0 ignores that file, so this is done in code for both.
 *
 * Trust on those versions = Android's own SYSTEM roots (never user-installed ones) + the two ISRG roots bundled in res/raw. Each bundled
 * root is re-checked against the SHA-256 pins in [ServerAuthPolicy.BUILT_IN_ROOTS] before use, so a modified file is ignored. Hostname
 * verification is unchanged (only the trust store is replaced). On Android 7.1.1 and later nothing changes at all.
 */
object LegacyTls {
    @Volatile private var appContext: Context? = null
    @Volatile private var factory: SSLSocketFactory? = null

    fun init(ctx: Context) { appContext = ctx.applicationContext }

    val needed: Boolean get() = Build.VERSION.SDK_INT < 25

    /** Call on every HTTPS connection before connecting. No-op on Android 7.1.1+. */
    fun apply(c: URLConnection) {
        if (!needed) return
        val https = c as? HttpsURLConnection ?: return
        socketFactory()?.let { https.sslSocketFactory = it }
    }

    private fun socketFactory(): SSLSocketFactory? {
        factory?.let { return it }
        val ctx = appContext ?: return null
        synchronized(this) {
            factory?.let { return it }
            val built = try {
                val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
                val system = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
                for (alias in system.aliases()) {
                    if (alias.startsWith("system:")) system.getCertificate(alias)?.let { ks.setCertificateEntry(alias, it) }
                }
                val cf = CertificateFactory.getInstance("X.509")
                for ((i, res) in listOf(de.blinkt.openvpn.R.raw.vpnapp_isrg_root_x1, de.blinkt.openvpn.R.raw.vpnapp_isrg_root_x2).withIndex()) {
                    val cert = ctx.resources.openRawResource(res).use { cf.generateCertificate(it) as X509Certificate }
                    val fp = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
                    if (fp in ServerAuthPolicy.BUILT_IN_ROOTS) ks.setCertificateEntry("vpnapp-isrg-$i", cert)
                }
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
                SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }.socketFactory
            } catch (_: Exception) {
                null // fall back to the platform default; the request may then fail, which is reported as a download error
            }
            factory = built
            return built
        }
    }
}
