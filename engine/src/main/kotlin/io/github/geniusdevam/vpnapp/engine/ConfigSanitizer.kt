package io.github.geniusdevam.vpnapp.engine

enum class EncryptionMode {
    /** Default: accepts AES-GCM, ChaCha20-Poly1305 and AES-CBC+HMAC. Never accepts weak ciphers. */
    COMPATIBLE,
    /** Only authenticated-encryption (AEAD) ciphers, and the cipher must be verified. Far fewer servers qualify. */
    STRONG,
}

sealed interface CipherVerdict {
    /** [cipher] is the OBSERVED cipher (null when none was observed); [verified] is true only when it was observed. */
    data class Accept(val cipher: String?, val verified: Boolean) : CipherVerdict
    data class Reject(val reason: String) : CipherVerdict
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

    /** Data-channel ciphers handed to the engine as its allowlist. The engine itself then refuses anything else. */
    fun dataCiphers(mode: EncryptionMode, declared: String?): String {
        val base = "AES-256-GCM:AES-128-GCM:CHACHA20-POLY1305"
        return if (mode == EncryptionMode.COMPATIBLE && declared != null && !isAead(declared) && !isWeak(declared)) "$base:$declared" else base
    }

    fun acceptsServer(server: Server, mode: EncryptionMode): Boolean {
        val c = server.declaredCipher
        if (c != null && isWeak(c)) return false
        return when (mode) {
            EncryptionMode.COMPATIBLE -> true
            EncryptionMode.STRONG -> isAead(c)
        }
    }

    /**
     * Judges a completed connection. [observed] is what the engine actually reported negotiating (null =
     * not observed); [declared] is only what the relay's profile says and is NEVER treated as verified.
     *  - Observed weak cipher: rejected in every mode.
     *  - Strong: requires an observed AEAD cipher.
     *  - Compatible: an unobserved cipher is accepted as "unverified" only because the engine was configured
     *    with an allowlist ([dataCiphers]) that excludes weak ciphers, so it cannot have negotiated one; the
     *    UI must say "not verified" rather than show the declared name.
     */
    fun evaluate(observed: String?, declared: String?, mode: EncryptionMode): CipherVerdict {
        if (observed != null) {
            if (isWeak(observed)) return CipherVerdict.Reject("negotiated weak cipher $observed")
            if (mode == EncryptionMode.STRONG && !isAead(observed)) return CipherVerdict.Reject("negotiated $observed, Strong mode needs AES-GCM or ChaCha20-Poly1305")
            return CipherVerdict.Accept(observed.uppercase(), true)
        }
        if (mode == EncryptionMode.STRONG) return CipherVerdict.Reject("cipher could not be verified, Strong mode requires verification")
        if (declared != null && isWeak(declared)) return CipherVerdict.Reject("relay declares weak cipher $declared")
        return CipherVerdict.Accept(null, false)
    }
}

sealed interface SanitizeResult {
    data class Ok(
        val config: String,
        val info: ConfigInfo,
        /** SHA-256 fingerprints of the `<ca>` certificates (for diagnostics). */
        val caFingerprints: List<String>,
        /** Directives deliberately removed that a relay asked for (e.g. "comp-lzo"). */
        val dropped: Set<String>,
    ) : SanitizeResult
    data class Rejected(val reason: String, val code: RejectCode = RejectCode.INVALID) : SanitizeResult
}

enum class RejectCode { INVALID, ADDRESS, WEAK_CRYPTO, IDENTITY, UNSUPPORTED, OVERSIZE }

/**
 * VPN Gate configs come from anonymous volunteers. We never hand them to the VPN engine verbatim:
 * the output is rebuilt from an allowlist, so scripts, plugins, management sockets, route/DNS pushes
 * and proxies cannot be smuggled in. The sanitizer also enforces the relay-identity policy
 * ([ServerAuthPolicy]) and refuses destinations that are not public unicast addresses.
 */
object ConfigSanitizer {
    const val MAX_CONFIG_CHARS = 64 * 1024
    private const val MAX_BLOCK_CHARS = 24 * 1024
    private val blockAllowed = setOf("ca", "cert", "key", "tls-auth", "tls-crypt")
    private val blockContent = Regex("^[A-Za-z0-9+/=\\-# ]*$")
    private val hostRe = Regex("^[A-Za-z0-9.\\-:]{1,253}$")
    private val simpleKeep = setOf(
        "client", "nobind", "persist-key", "persist-tun", "tls-client", "auth-nocache",
    )

    /**
     * @param expectedIp the IP the directory listed for this relay. The profile's `remote` must not
     *   contradict it, and the sanitized profile always dials this literal address (never a hostname we would
     *   have to resolve, whose answer could differ from what we vetted).
     */
    fun sanitize(
        raw: String,
        mode: EncryptionMode,
        expectedIp: String,
        auth: ServerAuthPolicy,
    ): SanitizeResult {
        if (raw.length > MAX_CONFIG_CHARS) return SanitizeResult.Rejected("Config too large", RejectCode.OVERSIZE)
        if (raw.any { it.code < 0x20 && it != '\n' && it != '\r' && it != '\t' || it.code == 0x7F }) {
            return SanitizeResult.Rejected("Control characters in config", RejectCode.INVALID)
        }
        val info = ConfigInfoParser.parse(raw) ?: return SanitizeResult.Rejected("No usable remote/proto in config", RejectCode.INVALID)
        if (info.remoteCount != 1) return SanitizeResult.Rejected("Config has ${info.remoteCount} remote directives", RejectCode.UNSUPPORTED)
        if (info.hasConnectionBlock) return SanitizeResult.Rejected("<connection> blocks are not supported", RejectCode.UNSUPPORTED)
        if (info.hasTlsCryptV2) return SanitizeResult.Rejected("tls-crypt-v2 is not supported", RejectCode.UNSUPPORTED)
        if (!info.hasCa) return SanitizeResult.Rejected("No CA certificate in config", RejectCode.IDENTITY)
        info.cipher?.let { if (CipherPolicy.isWeak(it)) return SanitizeResult.Rejected("Weak cipher $it", RejectCode.WEAK_CRYPTO) }
        info.auth?.let { if (it == "NONE") return SanitizeResult.Rejected("Authentication disabled", RejectCode.WEAK_CRYPTO) }
        if (mode == EncryptionMode.STRONG && !CipherPolicy.isAead(info.cipher)) {
            return SanitizeResult.Rejected("Not an AEAD cipher (Strong mode)", RejectCode.WEAK_CRYPTO)
        }

        // ---- destination ----
        val dest = AddressPolicy.canonical(expectedIp) ?: return SanitizeResult.Rejected("Directory IP is not an IP address", RejectCode.ADDRESS)
        var why = ""
        if (!AddressPolicy.isPublicUnicast(dest) { why = it }) return SanitizeResult.Rejected("Relay address refused: $why", RejectCode.ADDRESS)
        if (!hostRe.matches(info.host)) return SanitizeResult.Rejected("Invalid remote host", RejectCode.ADDRESS)
        if (AddressPolicy.isLiteral(info.host)) {
            if (AddressPolicy.canonical(info.host) != dest) {
                return SanitizeResult.Rejected("Config remote differs from the directory's address", RejectCode.ADDRESS)
            }
        }
        // A hostname in the profile is ignored: we dial the vetted literal. Certificate identity is checked by name below.

        // ---- body ----
        val out = StringBuilder()
        out.append("client\n")
        out.append("dev tun\n")
        out.append(if (info.transport == Transport.UDP) "proto udp\n" else "proto tcp-client\n")
        out.append("remote $dest ${info.port}\n")
        info.cipher?.let { out.append("cipher $it\n") }
        info.auth?.let { out.append("auth $it\n") }

        var block: String? = null
        val blockLines = StringBuilder()
        var sawAuthUserPass = false
        var keyDirection: String? = null
        val simple = LinkedHashSet<String>()
        val dropped = LinkedHashSet<String>()
        var caText: String? = null

        for (rawLine in raw.lineSequence()) {
            val line = rawLine.trim()
            if (block != null) {
                if (line.startsWith("</")) {
                    if (line != "</$block>") return SanitizeResult.Rejected("Malformed block", RejectCode.INVALID)
                    if (block in blockAllowed) {
                        if (block == "ca") caText = blockLines.toString()
                        out.append("<$block>\n").append(blockLines).append("</$block>\n")
                    }
                    block = null
                    blockLines.setLength(0)
                } else if (block in blockAllowed) {
                    if (!blockContent.matches(line)) return SanitizeResult.Rejected("Suspicious data in <$block>", RejectCode.INVALID)
                    blockLines.append(line).append('\n')
                    if (blockLines.length > MAX_BLOCK_CHARS) return SanitizeResult.Rejected("<$block> too large", RejectCode.OVERSIZE)
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
                // OpenVPN requires an argument here ("infinite" or seconds); a bare `resolv-retry` is an options error.
                t[0] == "resolv-retry" -> simple.add("resolv-retry infinite")
                t[0] == "auth-user-pass" -> sawAuthUserPass = true
                t[0] == "key-direction" && t.size >= 2 && (t[1] == "0" || t[1] == "1") -> keyDirection = t[1]
                // Compression is never forwarded. OpenVPN 2.7 never compresses outbound traffic anyway ("We never
                // compress"), so `comp-lzo` could only switch on decompression of attacker-controlled input.
                t[0] == "comp-lzo" || t[0] == "compress" || t[0] == "comp-noadapt" -> dropped.add(t[0])
                // everything else (script-security, up/down, plugin, route*, dhcp-option, management*, proxies,
                // setenv, log*, pull-filter, redirect-gateway, remote-cert-tls, verify-x509-name, ...) is dropped
                // on purpose: the app's own server-auth policy is appended below, never the relay's.
            }
        }
        if (block != null) return SanitizeResult.Rejected("Unterminated block", RejectCode.INVALID)

        // ---- identity ----
        val ca = caText ?: return SanitizeResult.Rejected("No CA certificate in config", RejectCode.IDENTITY)
        val verdict = try { CertInspector.judgeCaBlock(ca, auth.roots) } catch (e: Exception) {
            CaVerdict.Bad("certificate inspection failed: ${e.javaClass.simpleName}")
        }
        val fingerprints = when (verdict) {
            is CaVerdict.Bad -> return SanitizeResult.Rejected(verdict.reason, RejectCode.IDENTITY)
            is CaVerdict.Ok -> verdict.fingerprints
        }

        simple.forEach { out.append(it).append('\n') }
        if (sawAuthUserPass) out.append("auth-user-pass\n")
        keyDirection?.let { out.append("key-direction $it\n") }
        out.append("remote-cert-tls server\n")
        out.append("verify-x509-name ${auth.expectedName} name\n")
        out.append("verb 3\n")
        return SanitizeResult.Ok(out.toString(), info, fingerprints, dropped)
    }
}
