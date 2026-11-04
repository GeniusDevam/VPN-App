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
 * - UDP servers that need tls-auth/crypt (cannot answer an unauthenticated reset): TCP connect to port 443;
 *   a connection refusal is a valid RTT sample too, a timeout is a loss.
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
                server.transport == Transport.UDP -> tcpConnect(server.ip, 443, timeoutMs, ProbeMethod.TCP_FALLBACK)
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
