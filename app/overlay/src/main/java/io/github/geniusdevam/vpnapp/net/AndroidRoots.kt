package io.github.geniusdevam.vpnapp.net

import io.github.geniusdevam.vpnapp.engine.ServerAuthPolicy
import java.security.KeyStore
import java.security.MessageDigest

/**
 * The root certificates Android itself trusts. Only the SYSTEM store counts ("system:" aliases): a CA the user
 * (or malware) installed on the device must never be able to vouch for a VPN relay.
 */
object AndroidRoots {
    @Volatile private var cache: Set<String>? = null

    fun systemRootFingerprints(): Set<String> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val set = try {
                val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
                val out = HashSet<String>()
                for (alias in ks.aliases()) {
                    if (!alias.startsWith("system:")) continue
                    val c = ks.getCertificate(alias) ?: continue
                    out.add(MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString("") { "%02x".format(it) })
                }
                out
            } catch (_: Exception) {
                emptySet() // fail closed except for the pinned Let's Encrypt roots built into ServerAuthPolicy
            }
            cache = set
            return set
        }
    }

    fun policy(): ServerAuthPolicy = ServerAuthPolicy.of(systemRootFingerprints())
}
