package io.github.geniusdevam.vpnapp.engine

data class ConfigInfo(
    val host: String,
    val port: Int,
    val transport: Transport,
    val cipher: String?,
    val auth: String?,
    val tlsAuthOrCrypt: Boolean,
    val hasCa: Boolean,
)

/** Reads the few facts we need from an OpenVPN config without trusting anything else in it. */
object ConfigInfoParser {
    fun parse(config: String): ConfigInfo? {
        var host: String? = null
        var port = 1194
        var remoteProto: String? = null
        var proto: String? = null
        var cipher: String? = null
        var auth: String? = null
        var tlsAuth = false
        var hasCa = false
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
                        "tls-auth", "tls-crypt", "tls-crypt-v2" -> tlsAuth = true
                    }
                }
                continue
            }
            val t = line.split(Regex("\\s+"))
            when (t[0]) {
                "remote" -> if (host == null && t.size >= 2) {
                    host = t[1]
                    t.getOrNull(2)?.toIntOrNull()?.let { port = it }
                    remoteProto = t.getOrNull(3)
                }
                "proto" -> if (t.size >= 2) proto = t[1]
                "cipher" -> if (t.size >= 2) cipher = t[1].uppercase()
                "auth" -> if (t.size >= 2) auth = t[1].uppercase()
                "tls-auth", "tls-crypt", "tls-crypt-v2" -> tlsAuth = true
            }
        }
        val h = host ?: return null
        if (port !in 1..65535) return null
        val p = (remoteProto ?: proto ?: "udp").lowercase()
        val transport = when {
            p.startsWith("udp") -> Transport.UDP
            p.startsWith("tcp") -> Transport.TCP
            else -> return null
        }
        return ConfigInfo(h, port, transport, cipher, auth, tlsAuth, hasCa)
    }
}
