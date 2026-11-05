package io.github.geniusdevam.vpnapp.engine

import java.io.IOException
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Runs blocking code so that cancelling the caller returns immediately. The blocking call finishes on its own
 * (bounded by its own socket timeouts) and its result is dropped. Plain withContext would wait for it, which
 * could push a Quick Connect run past its 35 s budget.
 */
suspend fun <T> blockingCancellable(block: () -> T): T = ioScope.async { block() }.await()

/** Packet building/validation for the OpenVPN hard-reset latency probe (kept separate so it is unit-testable). */
object OpenVpnPacket {
    private const val OP_HARD_RESET_CLIENT_V2 = 7
    private const val OP_HARD_RESET_SERVER_V2 = 8

    /** 14 bytes: opcode|key_id, 8-byte session id, ack array length 0, 4-byte packet id 0. */
    fun hardReset(sessionId: ByteArray, tcpFramed: Boolean = false): ByteArray {
        require(sessionId.size == 8)
        val body = ByteArray(14)
        body[0] = ((OP_HARD_RESET_CLIENT_V2 shl 3) or 0).toByte()
        System.arraycopy(sessionId, 0, body, 1, 8)
        // body[9] = ack count (0); body[10..13] = packet id 0
        if (!tcpFramed) return body
        return byteArrayOf(0, body.size.toByte()) + body
    }

    /** True if [reply] is a server hard reset that acknowledges our [sessionId]. */
    fun isValidReply(reply: ByteArray, len: Int, sessionId: ByteArray, tcpFramed: Boolean = false): Boolean {
        val off = if (tcpFramed) 2 else 0
        if (len < off + 1 + 8 + 1) return false
        if ((reply[off].toInt() and 0xFF) ushr 3 != OP_HARD_RESET_SERVER_V2) return false
        val ackCount = reply[off + 9].toInt() and 0xFF
        if (ackCount < 1) return false
        val remoteSidAt = off + 10 + 4 * ackCount
        if (len < remoteSidAt + 8) return false
        for (i in 0 until 8) if (reply[remoteSidAt + i] != sessionId[i]) return false
        return true
    }
}

/**
 * Real network prober.
 * - UDP servers without tls-auth/crypt: OpenVPN hard-reset round trip (proves the VPN service is alive).
 * - TCP servers: TCP connect time to the VPN port.
 * - UDP servers with an inline tls-auth key: the same reset, HMAC-signed with that key (and the reply's HMAC verified).
 * - UDP servers with tls-crypt or anything unparsable: TCP connect to port 443 (weak: shows the host answers, not the VPN
 *   service); a refusal still counts as an RTT sample, a timeout as a loss.
 * [protectUdp]/[protectTcp] let the Android layer keep probe sockets outside an active tunnel.
 */
class NetworkProber(
    private val protectUdp: (DatagramSocket) -> Unit = {},
    private val protectTcp: (Socket) -> Unit = {},
    private val random: SecureRandom = SecureRandom(),
) : LatencyProber {

    override suspend fun probe(server: Server, timeoutMs: Long): ProbeSample = blockingCancellable {
        try {
            when {
                server.transport == Transport.UDP && !server.tlsAuthOrCrypt -> udp(server, timeoutMs)
                server.transport == Transport.UDP -> TlsAuthKey.parse(server.config)?.let { udpTlsAuth(server, it, timeoutMs) }
                    ?: tcpConnect(server.ip, 443, timeoutMs, ProbeMethod.TCP_FALLBACK)
                else -> tcpConnect(server.ip, server.port, timeoutMs, ProbeMethod.TCP_CONNECT)
            }
        } catch (_: Exception) {
            ProbeSample(null, ProbeMethod.NONE)
        }
    }

    private fun udp(server: Server, timeoutMs: Long): ProbeSample {
        val sid = ByteArray(8).also { random.nextBytes(it) }
        val packet = OpenVpnPacket.hardReset(sid)
        DatagramSocket().use { s ->
            protectUdp(s)
            s.soTimeout = timeoutMs.toInt().coerceAtLeast(1)
            s.connect(InetAddress.getByName(server.ip), server.port)
            val start = System.nanoTime()
            s.send(DatagramPacket(packet, packet.size))
            val buf = ByteArray(256)
            val deadline = start + timeoutMs * 1_000_000
            while (true) {
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) return ProbeSample(null, ProbeMethod.OPENVPN_UDP_RESET)
                s.soTimeout = left.toInt().coerceAtLeast(1)
                val dp = DatagramPacket(buf, buf.size)
                try {
                    s.receive(dp)
                } catch (_: SocketTimeoutException) {
                    return ProbeSample(null, ProbeMethod.OPENVPN_UDP_RESET)
                }
                val rtt = (System.nanoTime() - start) / 1e6
                if (OpenVpnPacket.isValidReply(buf, dp.length, sid)) return ProbeSample(rtt, ProbeMethod.OPENVPN_UDP_RESET)
            }
        }
    }

    /** Same exchange as [udp] but HMAC-authenticated with the profile's tls-auth key; the reply's HMAC must verify too. */
    private fun udpTlsAuth(server: Server, key: TlsAuthKey, timeoutMs: Long): ProbeSample {
        val sid = ByteArray(8).also { random.nextBytes(it) }
        val packet = key.hardReset(sid, 1, System.currentTimeMillis() / 1000)
        DatagramSocket().use { s ->
            protectUdp(s)
            s.connect(InetAddress.getByName(server.ip), server.port)
            val start = System.nanoTime()
            s.send(DatagramPacket(packet, packet.size))
            val buf = ByteArray(256)
            val deadline = start + timeoutMs * 1_000_000
            while (true) {
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) return ProbeSample(null, ProbeMethod.OPENVPN_UDP_RESET)
                s.soTimeout = left.toInt().coerceAtLeast(1)
                val dp = DatagramPacket(buf, buf.size)
                try { s.receive(dp) } catch (_: SocketTimeoutException) { return ProbeSample(null, ProbeMethod.OPENVPN_UDP_RESET) }
                val rtt = (System.nanoTime() - start) / 1e6
                if (key.isValidReply(buf, dp.length, sid)) return ProbeSample(rtt, ProbeMethod.OPENVPN_UDP_RESET)
            }
        }
    }

    private fun tcpConnect(ip: String, port: Int, timeoutMs: Long, method: ProbeMethod): ProbeSample {
        Socket().use { s ->
            protectTcp(s)
            val start = System.nanoTime()
            try {
                s.connect(InetSocketAddress(ip, port), timeoutMs.toInt().coerceAtLeast(1))
            } catch (_: ConnectException) {
                // Refused: the host answered with RST, which still measures the network round trip.
                if (method == ProbeMethod.TCP_FALLBACK) return ProbeSample((System.nanoTime() - start) / 1e6, method)
                return ProbeSample(null, method)
            } catch (_: SocketTimeoutException) {
                return ProbeSample(null, method)
            }
            return ProbeSample((System.nanoTime() - start) / 1e6, method)
        }
    }
}

/**
 * tls-auth key material taken from a relay's profile, used ONLY to authenticate a hard-reset probe to that relay's own vetted
 * address. Anything unexpected yields null, and the prober falls back to the weaker TCP check (finding C3).
 */
class TlsAuthKey internal constructor(private val outKey: ByteArray, private val inKey: ByteArray, val macName: String) {
    private fun mac(key: ByteArray, data: ByteArray): ByteArray =
        javax.crypto.Mac.getInstance("Hmac$macName").also { it.init(javax.crypto.spec.SecretKeySpec(key, "Hmac$macName")) }.doFinal(data)

    val macLen: Int get() = javax.crypto.Mac.getInstance("Hmac$macName").macLength

    /**
     * Wire format (UDP): opcode|keyid, session id(8), HMAC, packet-id(4), time(4), ack count(1), message packet id(4).
     * The HMAC covers packet-id, time, opcode|keyid, session id, then the remainder; it is computed over that order and
     * transmitted in the position shown.
     */
    fun hardReset(sessionId: ByteArray, packetId: Int, unixSeconds: Long): ByteArray {
        require(sessionId.size == 8)
        val head = ByteArray(9).also { it[0] = (7 shl 3).toByte(); System.arraycopy(sessionId, 0, it, 1, 8) }
        val pidTime = java.nio.ByteBuffer.allocate(8).putInt(packetId).putInt(unixSeconds.toInt()).array()
        val rest = byteArrayOf(0, 0, 0, 0, 0) // ack count 0, message packet id 0
        val tag = mac(outKey, pidTime + head + rest)
        return head + tag + pidTime + rest
    }

    /** A genuine server hard reset: right opcode, valid HMAC under the receive key, and it acknowledges OUR session id. */
    fun isValidReply(reply: ByteArray, len: Int, sessionId: ByteArray): Boolean {
        val m = macLen
        val fixed = 9 + m + 8 + 1
        if (len < fixed) return false
        if ((reply[0].toInt() and 0xFF) ushr 3 != 8) return false
        val head = reply.copyOfRange(0, 9)
        val tag = reply.copyOfRange(9, 9 + m)
        val pidTime = reply.copyOfRange(9 + m, 9 + m + 8)
        val rest = reply.copyOfRange(9 + m + 8, len)
        if (!java.security.MessageDigest.isEqual(tag, mac(inKey, pidTime + head + rest))) return false
        val acks = rest[0].toInt() and 0xFF
        if (acks < 1 || rest.size < 1 + 4 * acks + 8) return false
        for (i in 0 until 8) if (rest[1 + 4 * acks + i] != sessionId[i]) return false
        return true
    }

    companion object {
        private val HEX = Regex("^[0-9a-fA-F]{32}$")
        private val DIGESTS = setOf("SHA1", "SHA256", "SHA384", "SHA512")

        /** Reads an inline `<tls-auth>` block plus `key-direction` and `auth`. Null for tls-crypt, files, malformed or odd input. */
        fun parse(config: String): TlsAuthKey? {
            var inBlock = false; var lines = ArrayList<String>(); var seen = false
            var direction: Int? = null; var auth = "SHA1"
            for (raw in config.lineSequence()) {
                val line = raw.trim()
                if (inBlock) {
                    if (line == "</tls-auth>") { inBlock = false; continue }
                    lines.add(line); if (lines.size > 40) return null
                    continue
                }
                when {
                    line == "<tls-auth>" -> { if (seen) return null; seen = true; inBlock = true }
                    line.startsWith("key-direction") -> direction = line.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull()
                    line.startsWith("auth ") -> auth = line.split(Regex("\\s+")).getOrNull(1)?.uppercase()?.replace("-", "") ?: return null
                }
            }
            if (!seen || inBlock || auth !in DIGESTS) return null
            val hex = lines.filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("-----") }
            if (hex.size != 16 || !hex.all { HEX.matches(it) }) return null
            val raw = ByteArray(256) { i -> hex[i / 16].substring((i % 16) * 2, (i % 16) * 2 + 2).toInt(16).toByte() }
            val (outIdx, inIdx) = when (direction) { 1 -> 1 to 0; 0 -> 0 to 1; null -> 0 to 0; else -> return null }
            val len = javax.crypto.Mac.getInstance("Hmac$auth").macLength
            fun hmacKey(i: Int) = raw.copyOfRange(i * 128 + 64, i * 128 + 64 + len)
            return TlsAuthKey(hmacKey(outIdx), hmacKey(inIdx), auth)
        }
    }
}
