package io.github.geniusdevam.vpnapp.engine

import java.net.InetAddress

/**
 * Which addresses a VPN relay may legitimately have.
 *
 * Directory data is untrusted: a manipulated record could point the app at loopback, a LAN device, a
 * link-local/metadata address or a multicast group, turning "connect to a VPN relay" into a probe of
 * something the user never meant to contact. Only globally routable unicast addresses pass.
 *
 * Only IP LITERALS are ever parsed here (no DNS lookup is performed), so evaluating a hostile string can
 * neither block nor leak.
 */
object AddressPolicy {
    private val v4 = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")
    private val v6chars = Regex("^[0-9A-Fa-f:.]{2,45}$")

    /** Parses a strict dotted-quad IPv4 or an IPv6 literal. Leading zeros ("010.1.1.1") are rejected: they are octal in some parsers. */
    fun parseLiteral(s: String): ByteArray? {
        if (v4.matches(s)) return s.split('.').map { it.toInt().toByte() }.toByteArray()
        if (s.contains(':') && v6chars.matches(s) && s.count { it == ':' } in 2..8) {
            return try { InetAddress.getByName(s).address.takeIf { it.size == 16 } } catch (_: Exception) { null }
        }
        return null
    }

    fun isLiteral(s: String): Boolean = parseLiteral(s) != null

    /** Canonical text form, so "2001:0DB8::1" and "2001:db8:0:0:0:0:0:1" compare equal. */
    fun canonical(s: String): String? {
        val b = parseLiteral(s) ?: return null
        return if (b.size == 4) b.joinToString(".") { (it.toInt() and 0xFF).toString() }
        else InetAddress.getByAddress(b).hostAddress
    }

    /** True only for a global-unicast literal. [why] receives the reason otherwise. */
    fun isPublicUnicast(s: String, why: ((String) -> Unit)? = null): Boolean {
        val b = parseLiteral(s)
        if (b == null) { why?.invoke("not an IP address"); return false }
        val bad = if (b.size == 4) badV4(b) else badV6(b)
        if (bad != null) { why?.invoke(bad); return false }
        return true
    }

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    private fun badV4(b: ByteArray): String? {
        val a = u(b, 0); val c = u(b, 1); val d = u(b, 2)
        return when {
            a == 0 -> "unspecified/this-network (0.0.0.0/8)"
            a == 10 -> "private (10.0.0.0/8)"
            a == 100 && c in 64..127 -> "carrier-grade NAT (100.64.0.0/10)"
            a == 127 -> "loopback"
            a == 169 && c == 254 -> "link-local (169.254.0.0/16)"
            a == 172 && c in 16..31 -> "private (172.16.0.0/12)"
            a == 192 && c == 0 && d == 0 -> "IETF protocol assignments (192.0.0.0/24)"
            a == 192 && c == 0 && d == 2 -> "documentation (192.0.2.0/24)"
            a == 192 && c == 88 && d == 99 -> "6to4 relay anycast"
            a == 192 && c == 168 -> "private (192.168.0.0/16)"
            a == 198 && (c == 18 || c == 19) -> "benchmarking (198.18.0.0/15)"
            a == 198 && c == 51 && d == 100 -> "documentation (198.51.100.0/24)"
            a == 203 && c == 0 && d == 113 -> "documentation (203.0.113.0/24)"
            a in 224..239 -> "multicast"
            a >= 240 -> "reserved/broadcast"
            else -> null
        }
    }

    private fun badV6(b: ByteArray): String? {
        val first = u(b, 0)
        // Global unicast is 2000::/3. Everything else (::, ::1, fc00::/7, fe80::/10, ff00::/8, ::ffff:a.b.c.d, 64:ff9b::/96 ...) is refused.
        if (first and 0xE0 != 0x20) return "not global unicast (outside 2000::/3)"
        val w0 = (u(b, 0) shl 8) or u(b, 1)
        val w1 = (u(b, 2) shl 8) or u(b, 3)
        return when {
            w0 == 0x2001 && w1 == 0x0db8 -> "documentation (2001:db8::/32)"
            w0 == 0x2001 && w1 == 0x0000 -> "Teredo (2001::/32)"
            w0 == 0x2002 -> "6to4 (2002::/16): embeds an IPv4 address"
            w0 == 0x3fff && (w1 and 0xF000) == 0 -> "documentation (3fff::/20)"
            else -> null
        }
    }
}
