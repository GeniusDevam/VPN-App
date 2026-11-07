package io.github.geniusdevam.vpnapp.engine

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** Answers "is this root certificate one the device (or this app) already trusts?" by SHA-256 fingerprint (lower-case hex). */
fun interface RootStore {
    fun isTrustedRoot(sha256Hex: String): Boolean
}

/**
 * How a relay proves it is a genuine VPN Gate relay, independent of the directory that listed it.
 *
 * Why this exists: a VPN Gate profile carries its own `<ca>` block, so "the server's certificate chains to
 * the profile's CA" proves nothing when the profile itself came from a tampered directory. The policy:
 *
 *  1. Every self-signed certificate in `<ca>` must be a root the device already trusts ([roots]), and every
 *     other certificate in `<ca>` must chain to one of them. A directory cannot smuggle in its own root.
 *  2. The server must present a certificate that passes `remote-cert-tls server` (server key usage/EKU).
 *  3. The certificate's name must equal [expectedName] (`verify-x509-name <name> name`).
 *
 * Observed in 15 of 15 handshakes of the project's emulator runs: the relay chain was Let's Encrypt
 * (ISRG Root X1) and the leaf was `CN=opengw.net`, the domain VPN Gate controls. A third party cannot obtain
 * such a certificate. What this does NOT prove: that a particular volunteer is honest. The certificate (and
 * its key) is shared by the relay software, so any relay operator can present it. See docs/SECURITY.md.
 */
class ServerAuthPolicy(val expectedName: String, val roots: RootStore) {
    init { require(NAME.matches(expectedName)) { "unsafe certificate name" } }

    companion object {
        const val DEFAULT_NAME = "opengw.net"
        private val NAME = Regex("^[A-Za-z0-9.\\-]{1,253}$")

        /** SHA-256 of ISRG Root X1 and X2 (Let's Encrypt). Always trusted, even if the system store cannot be read. */
        val BUILT_IN_ROOTS: Set<String> = setOf(
            "96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6",
            "69729b8e15a86efc177a57afb7171dfc64add28c2fca8cf1507e34453ccb1470",
        )

        fun of(systemRoots: Set<String>, name: String = DEFAULT_NAME): ServerAuthPolicy {
            val all = (systemRoots.map { it.lowercase() } + BUILT_IN_ROOTS).toHashSet()
            return ServerAuthPolicy(name) { it.lowercase() in all }
        }
    }
}

sealed interface CaVerdict {
    data class Ok(val fingerprints: List<String>) : CaVerdict
    data class Bad(val reason: String) : CaVerdict
}

/** Parses and judges the `<ca>` block of an untrusted profile using only the JDK/Android certificate classes. */
object CertInspector {
    const val MAX_CERTS = 8
    const val MAX_DER_BYTES = 8 * 1024
    private const val BEGIN = "-----BEGIN CERTIFICATE-----"
    private const val END = "-----END CERTIFICATE-----"

    fun sha256(c: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString("") { "%02x".format(it) }

    fun judgeCaBlock(pem: String, roots: RootStore): CaVerdict {
        val beginCount = pem.windowed(BEGIN.length).count { it == BEGIN }
        if (beginCount == 0) return CaVerdict.Bad("no certificate in <ca>")
        if (beginCount > MAX_CERTS) return CaVerdict.Bad("too many certificates in <ca>")
        val certs = ArrayList<X509Certificate>()
        var from = 0
        val cf = CertificateFactory.getInstance("X.509")
        while (true) {
            val b = pem.indexOf(BEGIN, from)
            if (b < 0) break
            val e = pem.indexOf(END, b)
            if (e < 0) return CaVerdict.Bad("unterminated certificate in <ca>")
            val body = pem.substring(b + BEGIN.length, e)
            val der = try { Base64Codec.decodeMime(body.trim()) } catch (_: IllegalArgumentException) {
                return CaVerdict.Bad("certificate in <ca> is not valid base64")
            }
            if (der.isEmpty() || der.size > MAX_DER_BYTES) return CaVerdict.Bad("certificate in <ca> has an unreasonable size")
            val cert = try { cf.generateCertificate(ByteArrayInputStream(der)) as? X509Certificate } catch (_: Exception) { null }
                ?: return CaVerdict.Bad("certificate in <ca> does not parse")
            certs.add(cert)
            from = e + END.length
        }
        if (certs.size != beginCount) return CaVerdict.Bad("malformed <ca> block")

        fun selfSigned(c: X509Certificate): Boolean =
            c.subjectX500Principal == c.issuerX500Principal && verifiedBy(c, c)

        val rootCerts = certs.filter { selfSigned(it) }
        if (rootCerts.isEmpty()) return CaVerdict.Bad("<ca> contains no root certificate")
        for (r in rootCerts) {
            if (!roots.isTrustedRoot(sha256(r))) {
                return CaVerdict.Bad("<ca> contains a root that this device does not trust (${r.subjectX500Principal.name.take(60)})")
            }
        }
        // Everything else must chain, by verified signature, to an already anchored certificate.
        val anchored = LinkedHashSet<X509Certificate>(rootCerts)
        var grew = true
        while (grew) {
            grew = false
            for (c in certs) {
                if (c in anchored) continue
                if (anchored.any { it.subjectX500Principal == c.issuerX500Principal && verifiedBy(c, it) }) {
                    anchored.add(c); grew = true
                }
            }
        }
        if (anchored.size != certs.size) return CaVerdict.Bad("<ca> contains a certificate that does not chain to a trusted root")
        return CaVerdict.Ok(certs.map { sha256(it) })
    }

    private fun verifiedBy(c: X509Certificate, issuer: X509Certificate): Boolean = try {
        c.verify(issuer.publicKey); true
    } catch (_: Exception) { false }
}
