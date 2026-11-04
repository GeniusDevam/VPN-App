package io.github.geniusdevam.vpnapp.engine

import java.util.Base64

/**
 * Parses the VPN Gate "iphone" API CSV:
 *   *vpn_servers
 *   #HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64
 *   ...rows...
 *   *
 * Everything in it is untrusted: parse defensively, skip anything malformed.
 */
object VpnGateParser {
    const val MAX_INPUT_CHARS = 20_000_000
    private const val MAX_CONFIG_BYTES = 64 * 1024

    fun parse(csv: String): List<Server> {
        if (csv.length > MAX_INPUT_CHARS) return emptyList()
        val out = LinkedHashMap<String, Server>()
        for (raw in csv.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("*") || line.startsWith("#")) continue
            parseRow(line)?.let { out.putIfAbsent(it.id, it) }
        }
        return out.values.toList()
    }

    private fun parseRow(line: String): Server? {
        val f = line.split(",")
        if (f.size < 15) return null
        val id = f[0].trim()
        val ip = f[1].trim()
        val iso = f[6].trim().uppercase()
        if (id.isEmpty() || id.length > 100 || !isIpLiteral(ip)) return null
        if (!Regex("^[A-Z]{2}$").matches(iso)) return null
        val b64 = f.last().trim()
        if (b64.isEmpty() || b64.length > MAX_CONFIG_BYTES * 2) return null
        val config = try {
            String(Base64.getMimeDecoder().decode(b64), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (config.length > MAX_CONFIG_BYTES) return null
        val info = ConfigInfoParser.parse(config) ?: return null
        val message = f.subList(13, f.size - 1).joinToString(",").trim().ifEmpty { null }
        return Server(
            id = id,
            ip = ip,
            port = info.port,
            transport = info.transport,
            countryCode = iso,
            countryName = f[5].trim().take(60).ifEmpty { iso },
            reportedSpeedBps = f[4].trim().toLongOrNull(),
            sessions = f[7].trim().toIntOrNull(),
            uptimeMs = f[8].trim().toLongOrNull(),
            logPolicy = f[11].trim().take(80).ifEmpty { null },
            operator = f[12].trim().take(80).ifEmpty { null },
            message = message?.take(300),
            declaredCipher = info.cipher,
            tlsAuthOrCrypt = info.tlsAuthOrCrypt,
            config = config,
        )
    }

    private val ipv4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    internal fun isIpLiteral(s: String): Boolean {
        ipv4.matchEntire(s)?.let { m -> return m.groupValues.drop(1).all { it.toInt() in 0..255 } }
        return s.contains(':') && s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
    }
}
