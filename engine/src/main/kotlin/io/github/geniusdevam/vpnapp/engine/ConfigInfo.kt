package io.github.geniusdevam.vpnapp.engine

data class ConfigInfo(
    val host: String,
    val port: Int,
    val transport: Transport,
    val cipher: String?,
    val auth: String?,
    val tlsAuthOrCrypt: Boolean,
    val hasCa: Boolean,
    /** Number of `remote` directives. More than one is ambiguous and is refused by the sanitizer. */
    val remoteCount: Int = 1,
    /** A `<connection>` block can hide further remotes/protocols; the sanitizer refuses profiles that use one. */
    val hasConnectionBlock: Boolean = false,
    /** tls-crypt-v2 needs per-client key material this app does not handle. */
    val hasTlsCryptV2: Boolean = false,
)

/** Reads the few facts we need from an OpenVPN config without trusting anything else in it. */
object ConfigInfoParser {
    /** Transports the app can actually speak. "tcp-server" and unknown values are not clients. */
    private val udpNames = setOf("udp", "udp4", "udp6")
    private val tcpNames = setOf("tcp", "tcp4", "tcp6", "tcp-client", "tcp4-client", "tcp6-client")

    fun parse(config: String): ConfigInfo? {
        var host: String? = null
        var port = 1194
        var remoteProto: String? = null
        var proto: String? = null
        var cipher: String? = null
        var auth: String? = null
        var tlsAuth = false
        var tlsCryptV2 = false
        var hasCa = false
        var connectionBlock = false
        var remotes = 0
        var inBlock: String? = null

        for (raw in config.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (inBlock != null) {
                if (line.startsWith("</")) inBlock = null
                continue
            }
            if (line.startsWith("<") && line.endsWith(">")) {
                val name = line.substring(1, line.length - 1)
                if (!name.startsWith("/")) {
                    inBlock = name
                    when (name) {
                        "ca" -> hasCa = true
                        "tls-auth", "tls-crypt" -> tlsAuth = true
                        "tls-crypt-v2" -> { tlsAuth = true; tlsCryptV2 = true }
                        "connection" -> connectionBlock = true
                    }
                }
                continue
            }
            val t = line.split(Regex("\\s+"))
            when (t[0]) {
                "remote" -> {
                    remotes++
                    if (host == null && t.size >= 2) {
                        host = t[1]
                        // A malformed port makes the whole profile unusable instead of silently defaulting.
                        if (t.size >= 3) port = t[2].toIntOrNull() ?: return null
                        remoteProto = t.getOrNull(3)
                    }
                }
                "proto" -> if (t.size >= 2) proto = t[1]
                "cipher" -> if (t.size >= 2) cipher = t[1].uppercase()
                "auth" -> if (t.size >= 2) auth = t[1].uppercase()
                "tls-auth", "tls-crypt" -> tlsAuth = true
                "tls-crypt-v2" -> { tlsAuth = true; tlsCryptV2 = true }
            }
        }
        val h = host ?: return null
        if (port !in 1..65535) return null
        val p = (remoteProto ?: proto ?: "udp").lowercase()
        val transport = when (p) {
            in udpNames -> Transport.UDP
            in tcpNames -> Transport.TCP
            else -> return null
        }
        return ConfigInfo(h, port, transport, cipher, auth, tlsAuth, hasCa, remotes, connectionBlock, tlsCryptV2)
    }
}
