package io.github.geniusdevam.vpnapp.engine

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ParsingAndSanitizerTest {
    private val goodConfig = """
        # comment
        client
        dev tun
        proto udp
        remote 219.100.37.63 1194
        cipher AES-128-CBC
        auth SHA1
        resolv-retry infinite
        nobind
        persist-key
        persist-tun
        verb 3
        auth-user-pass
        <ca>
    """.trimIndent() + "\n" + Pki.ROOT + "\n</ca>"

    private val ip = "219.100.37.63"
    private val auth = ServerAuthPolicy.of(setOf(Pki.ROOT_FP))
    private fun sanitize(raw: String, mode: EncryptionMode = EncryptionMode.COMPATIBLE, expectedIp: String = ip) =
        ConfigSanitizer.sanitize(raw, mode, expectedIp, auth)

    private fun row(name: String, iso: String, cfg: String, ip: String = "219.100.37.63") =
        "$name,$ip,1013,5,53433904,Japan,$iso,45,2864360957,2564,3345,2weeks,Some Operator,Hello,${Base64.getEncoder().encodeToString(cfg.toByteArray())}"

    @Test fun parsesVpnGateCsv() {
        val csv = "*vpn_servers\n#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64\n" +
            row("public-vpn-1", "JP", goodConfig) + "\n" + row("public-vpn-2", "KR", goodConfig.replace("udp", "tcp").replace("1194", "443"), "1.2.3.4") + "\n*\n"
        val servers = VpnGateParser.parse(csv)
        assertEquals(2, servers.size)
        val a = servers[0]
        assertEquals("JP", a.countryCode); assertEquals(Transport.UDP, a.transport); assertEquals(1194, a.port)
        assertEquals(53433904L, a.reportedSpeedBps); assertEquals("AES-128-CBC", a.declaredCipher)
        val b = servers[1]
        assertEquals(Transport.TCP, b.transport); assertEquals(443, b.port)
    }

    @Test fun skipsMalformedRowsAndBadInput() {
        val csv = listOf(
            "garbage", "a,b,c",
            row("x", "JPN", goodConfig),               // bad country code
            row("y", "JP", goodConfig, "not-an-ip"),   // bad ip
            "z,1.1.1.1,1,1,1,Japan,JP,1,1,1,1,2w,op,msg,!!!notbase64!!!",
            row("ok", "JP", goodConfig),
        ).joinToString("\n")
        assertEquals(listOf("ok"), VpnGateParser.parse(csv).map { it.id })
    }

    @Test fun sanitizerKeepsSafeDirectivesAndDropsDangerousOnes() {
        val evil = goodConfig + "\nscript-security 2\nup /system/bin/sh -c 'reboot'\nplugin /data/x.so\n" +
            "route 0.0.0.0 0.0.0.0\ndhcp-option DNS 6.6.6.6\nmanagement 0.0.0.0 7505\nsocks-proxy 1.2.3.4 1080\n" +
            "setenv FOO bar\nlog /sdcard/x\nhttp-proxy 5.5.5.5 80\n<extra-certs>\nzzz\n</extra-certs>\n"
        val r = sanitize(evil)
        assertIs<SanitizeResult.Ok>(r)
        val c = r.config
        for (bad in listOf("script-security", "up ", "plugin", "route", "dhcp-option", "management", "proxy", "setenv", "log ", "extra-certs", "zzz")) {
            assertFalse(c.contains(bad), "kept '$bad':\n$c")
        }
        assertTrue(c.contains("remote 219.100.37.63 1194"))
        assertTrue(c.contains("<ca>") && c.contains("BEGIN CERTIFICATE"))
        assertTrue(c.contains("auth-user-pass"))
    }

    @Test fun rejectsWeakCiphersNoAuthAndMissingCa() {
        for (cipher in listOf("BF-CBC", "DES-EDE3-CBC", "none", "RC2-40-CBC")) {
            assertIs<SanitizeResult.Rejected>(sanitize(goodConfig.replace("AES-128-CBC", cipher)), cipher)
        }
        assertIs<SanitizeResult.Rejected>(sanitize(goodConfig.replace("auth SHA1", "auth none")))
        assertIs<SanitizeResult.Rejected>(sanitize(goodConfig.substringBefore("<ca>")))
    }

    @Test fun rejectsSuspiciousCertificateBlockContent() {
        val bad = goodConfig.replace("-----BEGIN CERTIFICATE-----\n", "-----BEGIN CERTIFICATE-----\n<script>alert(1)</script>\n")
        assertIs<SanitizeResult.Rejected>(sanitize(bad))
    }

    @Test fun strongModeOnlyAcceptsAead() {
        assertIs<SanitizeResult.Rejected>(sanitize(goodConfig, EncryptionMode.STRONG))
        assertIs<SanitizeResult.Ok>(sanitize(goodConfig.replace("AES-128-CBC", "AES-256-GCM"), EncryptionMode.STRONG))
        assertIs<CipherVerdict.Accept>(CipherPolicy.evaluate("AES-256-GCM", "AES-256-GCM", EncryptionMode.STRONG))
        assertIs<CipherVerdict.Reject>(CipherPolicy.evaluate("AES-128-CBC", "AES-128-CBC", EncryptionMode.STRONG))
        assertIs<CipherVerdict.Reject>(CipherPolicy.evaluate("BF-CBC", null, EncryptionMode.COMPATIBLE))
    }

    @Test fun hardResetPacketLayoutAndReplyValidation() {
        val sid = ByteArray(8) { (it + 1).toByte() }
        val p = OpenVpnPacket.hardReset(sid)
        assertEquals(14, p.size); assertEquals(0x38, p[0].toInt()); assertEquals(0, p[9].toInt())
        val framed = OpenVpnPacket.hardReset(sid, tcpFramed = true)
        assertEquals(16, framed.size); assertEquals(14, framed[1].toInt())
        // opcode 8, server sid(8), ack count 1, ack id(4), our sid(8), pkt id(4)
        val reply = ByteArray(1 + 8 + 1 + 4 + 8 + 4)
        reply[0] = (8 shl 3).toByte(); reply[9] = 1
        System.arraycopy(sid, 0, reply, 14, 8)
        assertTrue(OpenVpnPacket.isValidReply(reply, reply.size, sid))
        val wrong = reply.copyOf().also { it[14] = 99 }
        assertFalse(OpenVpnPacket.isValidReply(wrong, wrong.size, sid))
        val notReset = reply.copyOf().also { it[0] = (4 shl 3).toByte() }
        assertFalse(OpenVpnPacket.isValidReply(notReset, notReset.size, sid))
        assertFalse(OpenVpnPacket.isValidReply(reply, 5, sid))
    }

    @Test fun quickScorePenalisesLossSlowLinesAndTcp() {
        val base = LatencyStats(30.0, 2.0, 0.0, 3, ProbeMethod.OPENVPN_UDP_RESET)
        val fast = Scoring.quickScore(srv("a", speedBps = 200_000_000, sessions = 0), base)
        assertTrue(Scoring.quickScore(srv("a", speedBps = 5_000_000, sessions = 0), base) > fast)
        assertTrue(Scoring.quickScore(srv("a", transport = Transport.TCP, speedBps = 200_000_000, sessions = 0), base) > fast)
        assertTrue(Scoring.quickScore(srv("a", speedBps = 200_000_000, sessions = 0), base.copy(loss = 0.3)) > fast)
    }

    @Test fun reputationBlocksAfterRepeatedFailuresAndLongBlockOnHardFailure() {
        var now = 0L
        val r = ServerLedger(WallClock { now })
        r.recordFailure("a", false); assertFalse(r.isBlocked("a"))
        r.recordFailure("a", false); assertTrue(r.isBlocked("a"))
        now += ServerLedger.SOFT_BLOCK_MS + 1; assertFalse(r.isBlocked("a"))
        r.recordFailure("b", true); now += ServerLedger.SOFT_BLOCK_MS * 10; assertTrue(r.isBlocked("b"))
    }

    @Test fun countryOptionsListOnlyCountriesWithServers() {
        val opts = listOf(srv("a", "JP"), srv("b", "JP"), srv("c", "KR")).countryOptions(java.util.Locale.ENGLISH)
        assertEquals(listOf("Japan", "South Korea"), opts.map { it.name })
        assertEquals(2, opts.first { it.iso == "JP" }.serverCount)
        assertEquals("🇯🇵", Countries.flag("JP"))
    }
}
