package io.github.geniusdevam.vpnapp.engine

enum class EncryptionMode {
    /** Default: accepts AES-GCM, ChaCha20-Poly1305 and AES-CBC+HMAC. Never accepts weak ciphers. */
    COMPATIBLE,
    /** Only authenticated-encryption (AEAD) ciphers. Far fewer servers qualify. */
    STRONG,
}

object CipherPolicy {
    private val weak = listOf("NONE", "BF-", "DES", "RC2", "RC4", "CAST", "IDEA", "SEED", "BLOWFISH")
    private val aead = setOf("AES-128-GCM", "AES-192-GCM", "AES-256-GCM", "CHACHA20-POLY1305")

    fun isWeak(cipher: String): Boolean {
        val c = cipher.uppercase()
        if (c in aead) return false
        return weak.any { c == it || c.startsWith(it) }
    }

    fun isAead(cipher: String?): Boolean = cipher != null && cipher.uppercase() in aead

    fun acceptsServer(server: Server, mode: EncryptionMode): Boolean {
        val c = server.declaredCipher
        if (c != null && isWeak(c)) return false
        return when (mode) {
            EncryptionMode.COMPATIBLE -> true
            EncryptionMode.STRONG -> isAead(c)
        }
    }

    /** [negotiated] is what the tunnel reported; null means unknown. Unknown is accepted in COMPATIBLE only. */
    fun acceptsNegotiated(negotiated: String?, mode: EncryptionMode): Boolean {
        if (negotiated == null) return mode == EncryptionMode.COMPATIBLE
        if (isWeak(negotiated)) return false
        return mode == EncryptionMode.COMPATIBLE || isAead(negotiated)
    }
}

sealed interface SanitizeResult {
    data class Ok(val config: String, val info: ConfigInfo) : SanitizeResult
    data class Rejected(val reason: String) : SanitizeResult
}

/**
 * VPN Gate configs come from anonymous volunteers. We never hand them to the VPN engine verbatim:
 * the output is rebuilt from an allowlist, so scripts, plugins, management sockets, route/DNS pushes
 * and proxies cannot be smuggled in.
 */
object ConfigSanitizer {
    private val blockAllowed = setOf("ca", "cert", "key", "tls-auth", "tls-crypt")
    private val blockContent = Regex("^[A-Za-z0-9+/=\\-# ]*$")
    private val hostRe = Regex("^[A-Za-z0-9.\\-:]{1,253}$")
    private val simpleKeep = setOf(
        "client", "resolv-retry", "nobind", "persist-key", "persist-tun", "tls-client", "auth-nocache",
    )

    fun sanitize(raw: String, mode: EncryptionMode = EncryptionMode.COMPATIBLE): SanitizeResult {
        val info = ConfigInfoParser.parse(raw) ?: return SanitizeResult.Rejected("No usable remote/proto in config")
        if (!info.hasCa) return SanitizeResult.Rejected("No CA certificate in config")
        info.cipher?.let { if (CipherPolicy.isWeak(it)) return SanitizeResult.Rejected("Weak cipher ${it}") }
        info.auth?.let { if (it == "NONE") return SanitizeResult.Rejected("Authentication disabled") }
        if (!hostRe.matches(info.host)) return SanitizeResult.Rejected("Invalid remote host")
        if (mode == EncryptionMode.STRONG && !CipherPolicy.isAead(info.cipher)) {
            return SanitizeResult.Rejected("Not an AEAD cipher (Strong mode)")
        }

        val out = StringBuilder()
        out.append("client\n")
        out.append("dev tun\n")
        out.append(if (info.transport == Transport.UDP) "proto udp\n" else "proto tcp-client\n")
        out.append("remote ${info.host} ${info.port}\n")
        info.cipher?.let { out.append("cipher $it\n") }
        info.auth?.let { out.append("auth $it\n") }

        var block: String? = null
        val blockLines = StringBuilder()
        var sawAuthUserPass = false
        var keyDirection: String? = null
        val simple = LinkedHashSet<String>()

        for (rawLine in raw.lineSequence()) {
            val line = rawLine.trim()
            if (block != null) {
                if (line.startsWith("</")) {
                    if (line != "</$block>") return SanitizeResult.Rejected("Malformed block")
                    if (block in blockAllowed) out.append("<$block>\n").append(blockLines).append("</$block>\n")
                    block = null
                    blockLines.setLength(0)
                } else if (block in blockAllowed) {
                    if (!blockContent.matches(line)) return SanitizeResult.Rejected("Suspicious data in <$block>")
                    blockLines.append(line).append('\n')
                }
                continue
            }
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("<") && line.endsWith(">") && !line.startsWith("</")) {
                block = line.substring(1, line.length - 1)
                continue
            }
            val t = line.split(Regex("\\s+"))
            when {
                t[0] in simpleKeep -> simple.add(t[0])
                t[0] == "auth-user-pass" -> sawAuthUserPass = true
                t[0] == "key-direction" && t.size >= 2 && (t[1] == "0" || t[1] == "1") -> keyDirection = t[1]
                t[0] == "remote-cert-tls" && t.size >= 2 && t[1] == "server" -> simple.add("remote-cert-tls server")
                t[0] == "comp-lzo" -> simple.add("comp-lzo") // required to interoperate with servers that compress
                // everything else (script-security, up/down, plugin, route*, dhcp-option, management*, proxies,
                // setenv, log*, pull-filter, redirect-gateway, ...) is dropped on purpose.
            }
        }
        if (block != null) return SanitizeResult.Rejected("Unterminated block")
        simple.forEach { out.append(it).append('\n') }
        if (sawAuthUserPass) out.append("auth-user-pass\n")
        keyDirection?.let { out.append("key-direction $it\n") }
        out.append("verb 3\n")
        return SanitizeResult.Ok(out.toString(), info)
    }
}
