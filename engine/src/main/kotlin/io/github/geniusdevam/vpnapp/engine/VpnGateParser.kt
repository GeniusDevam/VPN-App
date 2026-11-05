package io.github.geniusdevam.vpnapp.engine

import java.util.Base64
import java.util.Locale

/** Why a directory row was not accepted. Counted in [DirectoryReport] so the app can show real numbers. */
enum class RowReject {
    MALFORMED_ROW, BAD_COUNTRY, BAD_ADDRESS, BAD_ENCODING, OVERSIZE, UNSUPPORTED_PROFILE,
    DUPLICATE_ID, DUPLICATE_ENDPOINT,
}

/** Result of parsing one directory download. [rowsSeen] counts data rows, valid or not. */
class ParseReport(
    val servers: List<Server>,
    val rowsSeen: Int,
    val rejected: Map<RowReject, Int>,
    /** True if the download was refused wholesale (too large). */
    val oversize: Boolean = false,
)

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
    private const val MAX_ROWS = 200_000
    private const val MAX_LINE_CHARS = 200_000

    private val isoCountries: Set<String> = Locale.getISOCountries().toSet()
    private val countryAlias = mapOf("UK" to "GB")

    fun parse(csv: String): List<Server> = parseDetailed(csv).servers

    /** ISO-3166 alpha-2, upper-cased, or null if it is not a real country code. */
    fun normalizeCountry(raw: String): String? {
        val c = raw.trim().uppercase()
        val n = countryAlias[c] ?: c
        return n.takeIf { it in isoCountries }
    }

    fun parseDetailed(csv: String): ParseReport {
        if (csv.length > MAX_INPUT_CHARS) return ParseReport(emptyList(), 0, emptyMap(), oversize = true)
        return parseDetailed(csv.lineSequence())
    }

    /** Streaming form: lines are consumed one at a time, so a large directory never has to exist as one String. */
    fun parseDetailed(lines: Sequence<String>): ParseReport {
        val out = LinkedHashMap<String, Server>()
        val endpoints = HashSet<String>()
        val rejected = EnumMap()
        var rows = 0
        for (raw in lines) {
            if (raw.length > MAX_LINE_CHARS) { rejected.inc(RowReject.OVERSIZE); rows++; continue }
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("*") || line.startsWith("#")) continue
            if (++rows > MAX_ROWS) break
            when (val r = parseRow(line)) {
                is Row.Bad -> rejected.inc(r.why)
                is Row.Good -> {
                    val s = r.server
                    when {
                        s.id in out -> rejected.inc(RowReject.DUPLICATE_ID)
                        // Two names for the same ip:port:transport are one relay: keep the first listing.
                        !endpoints.add("${AddressPolicy.canonical(s.ip)}|${s.port}|${s.transport}") -> rejected.inc(RowReject.DUPLICATE_ENDPOINT)
                        else -> out[s.id] = s
                    }
                }
            }
        }
        return ParseReport(out.values.toList(), rows, rejected.map)
    }

    private class EnumMap {
        val map = java.util.EnumMap<RowReject, Int>(RowReject::class.java)
        fun inc(k: RowReject) { map[k] = (map[k] ?: 0) + 1 }
    }

    private sealed interface Row {
        class Good(val server: Server) : Row
        class Bad(val why: RowReject) : Row
    }

    private fun parseRow(line: String): Row {
        val f = line.split(",")
        if (f.size < 15) return Row.Bad(RowReject.MALFORMED_ROW)
        val id = f[0].trim()
        if (id.isEmpty() || id.length > 100 || id.any { it.code < 0x20 }) return Row.Bad(RowReject.MALFORMED_ROW)
        val ip = AddressPolicy.canonical(f[1].trim())
        if (ip == null || !AddressPolicy.isPublicUnicast(ip)) return Row.Bad(RowReject.BAD_ADDRESS)
        val iso = normalizeCountry(f[6]) ?: return Row.Bad(RowReject.BAD_COUNTRY)
        val b64 = f.last().trim()
        if (b64.isEmpty()) return Row.Bad(RowReject.MALFORMED_ROW)
        if (b64.length > MAX_CONFIG_BYTES * 2) return Row.Bad(RowReject.OVERSIZE)
        val config = try {
            String(Base64.getMimeDecoder().decode(b64), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return Row.Bad(RowReject.BAD_ENCODING)
        }
        if (config.length > MAX_CONFIG_BYTES) return Row.Bad(RowReject.OVERSIZE)
        val info = ConfigInfoParser.parse(config) ?: return Row.Bad(RowReject.UNSUPPORTED_PROFILE)
        val message = f.subList(13, f.size - 1).joinToString(",").trim().ifEmpty { null }
        return Row.Good(
            Server(
                id = id,
                ip = ip,
                port = info.port,
                transport = info.transport,
                countryCode = iso,
                countryName = f[5].trim().take(60).ifEmpty { iso },
                reportedSpeedBps = f[4].trim().toLongOrNull()?.takeIf { it >= 0 },
                sessions = f[7].trim().toIntOrNull()?.takeIf { it >= 0 },
                uptimeMs = f[8].trim().toLongOrNull()?.takeIf { it >= 0 },
                logPolicy = f[11].trim().take(80).ifEmpty { null },
                operator = f[12].trim().take(80).ifEmpty { null },
                message = message?.take(300),
                declaredCipher = info.cipher,
                tlsAuthOrCrypt = info.tlsAuthOrCrypt,
                config = config,
                vpnGateScore = f[2].trim().toLongOrNull(),
                vpnGatePingMs = f[3].trim().toIntOrNull(),
                totalUsers = f[9].trim().toLongOrNull()?.takeIf { it >= 0 },
                totalTrafficBytes = f[10].trim().toLongOrNull()?.takeIf { it >= 0 },
            ),
        )
    }
}
