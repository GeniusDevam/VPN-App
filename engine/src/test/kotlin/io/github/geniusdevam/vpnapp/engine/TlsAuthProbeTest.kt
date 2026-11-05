package io.github.geniusdevam.vpnapp.engine

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TlsAuthProbeTest {
    private val hexLines = (0 until 16).map { i -> (0 until 16).joinToString("") { j -> "%02x".format((i * 16 + j) and 0xFF) } }
    private fun block(lines: List<String> = hexLines) =
        "<tls-auth>\n-----BEGIN OpenVPN Static key V1-----\n${lines.joinToString("\n")}\n-----END OpenVPN Static key V1-----\n</tls-auth>\n"
    private fun cfg(extra: String = "", b: String = block()) = "client\nproto udp\nremote 1.2.3.4 1194\nauth SHA1\n$extra$b"
    private val raw = ByteArray(256) { it.toByte() }
    private val sid = ByteArray(8) { (it + 1).toByte() }

    @Test fun parsesInlineKeyAndBuildsAPacketWhoseHmacFollowsTheDocumentedOrder() {
        val key = assertNotNull(TlsAuthKey.parse(cfg("key-direction 1\n")))
        val pkt = key.hardReset(sid, 1, 1_700_000_000L)
        assertEquals(9 + 20 + 8 + 5, pkt.size)
        assertEquals(7 shl 3, pkt[0].toInt() and 0xFF)
        assertContentEquals(sid, pkt.copyOfRange(1, 9))
        // independent recomputation: client with key-direction 1 sends with keys[1].hmac (bytes 128+64 ...)
        val k = raw.copyOfRange(192, 212)
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(k, "HmacSHA1")) }
        val expected = mac.doFinal(pkt.copyOfRange(29, 37) + pkt.copyOfRange(0, 9) + pkt.copyOfRange(37, pkt.size))
        assertContentEquals(expected, pkt.copyOfRange(9, 29))
    }

    @Test fun directionZeroAndBidirectionalUseDifferentSendKeys() {
        val a = TlsAuthKey.parse(cfg("key-direction 0\n"))!!.hardReset(sid, 1, 5)
        val b = TlsAuthKey.parse(cfg("key-direction 1\n"))!!.hardReset(sid, 1, 5)
        val c = TlsAuthKey.parse(cfg(""))!!.hardReset(sid, 1, 5)
        assertFalse(a.contentEquals(b)); assertContentEquals(a, c, "no key-direction means keys[0] both ways, same as direction 0 for sending")
    }

    @Test fun honoursDeclaredDigest() {
        assertEquals("SHA256", TlsAuthKey.parse(cfg("").replace("auth SHA1", "auth SHA-256"))!!.macName)
        assertEquals(9 + 32 + 8 + 5, TlsAuthKey.parse(cfg("").replace("auth SHA1", "auth SHA256"))!!.hardReset(sid, 1, 5).size)
    }

    @Test fun refusesAnythingUnexpectedAndFallsBack() {
        assertNull(TlsAuthKey.parse(cfg(b = "")), "no key")
        assertNull(TlsAuthKey.parse(cfg(b = block(hexLines.dropLast(1)))), "short key")
        assertNull(TlsAuthKey.parse(cfg(b = block(hexLines.take(15) + "zz".repeat(16)))), "non-hex")
        assertNull(TlsAuthKey.parse(cfg(b = block(hexLines + "00".repeat(16)))), "too long")
        assertNull(TlsAuthKey.parse(cfg(b = block() + block())), "two blocks")
        assertNull(TlsAuthKey.parse(cfg("key-direction 7\n")), "bad direction")
        assertNull(TlsAuthKey.parse(cfg("").replace("auth SHA1", "auth MD5")), "weak or unknown digest")
        assertNull(TlsAuthKey.parse(cfg(b = "<tls-crypt>\n${hexLines.joinToString("\n")}\n</tls-crypt>\n")), "tls-crypt is not tls-auth")
        assertNull(TlsAuthKey.parse(cfg(b = "<tls-auth>\n" + hexLines.joinToString("\n"))), "unterminated block")
    }

    @Test fun parserSurvivesGarbage() {
        val r = java.util.Random(7)
        repeat(300) { TlsAuthKey.parse(String(CharArray(r.nextInt(400)) { (r.nextInt(95) + 32).toChar() })) }
        repeat(300) { TlsAuthKey.parse("<tls-auth>\n" + (0 until r.nextInt(30)).joinToString("\n") { "%032x".format(r.nextLong()) }) }
    }

    @Test fun replyValidationRejectsForgeriesAndWrongSessions() {
        val key = TlsAuthKey.parse(cfg("key-direction 1\n"))!!
        assertFalse(key.isValidReply(ByteArray(10), 10, sid), "too short")
        val junk = ByteArray(60).also { it[0] = (8 shl 3).toByte() }
        assertFalse(key.isValidReply(junk, junk.size, sid), "right opcode but no valid HMAC")
        val ownRequest = key.hardReset(sid, 1, 5) // an echo of our own packet: opcode 7, and signed with the send key, not the receive key
        assertFalse(key.isValidReply(ownRequest, ownRequest.size, sid))
    }
}
