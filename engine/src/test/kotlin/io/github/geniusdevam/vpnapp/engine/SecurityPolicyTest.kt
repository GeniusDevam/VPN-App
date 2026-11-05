package io.github.geniusdevam.vpnapp.engine

import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SecurityPolicyTest {
    private val ip = "203.0.114.7" // not in any reserved block
    private val auth = ServerAuthPolicy.of(setOf(Pki.ROOT_FP))

    private fun cfg(
        ca: String = Pki.ROOT,
        remote: String = "remote $ip 1194",
        extra: String = "",
        proto: String = "proto udp",
        cipher: String = "cipher AES-128-CBC",
    ) = "client\ndev tun\n$proto\n$remote\n$cipher\nauth SHA1\nauth-user-pass\n$extra\n<ca>\n$ca\n</ca>\n"

    private fun san(raw: String, mode: EncryptionMode = EncryptionMode.COMPATIBLE, expect: String = ip) =
        ConfigSanitizer.sanitize(raw, mode, expect, auth)

    private fun assertRejected(r: SanitizeResult, code: RejectCode? = null, contains: String? = null) {
        assertIs<SanitizeResult.Rejected>(r, "expected rejection, got $r")
        code?.let { assertEquals(it, r.code, r.reason) }
        contains?.let { assertTrue(r.reason.contains(it, ignoreCase = true), r.reason) }
    }

    // ---------- B2: server authentication ----------

    @Test fun trustedRootIsAcceptedAndPolicyDirectivesAreAppended() {
        val r = san(cfg())
        assertIs<SanitizeResult.Ok>(r)
        val lines = r.config.lines()
        assertTrue("remote-cert-tls server" in lines)
        assertTrue("verify-x509-name opengw.net name" in lines)
        assertEquals(listOf(Pki.ROOT_FP), r.caFingerprints)
    }

    @Test fun relayCannotChooseItsOwnVerificationPolicy() {
        val r = san(cfg(extra = "remote-cert-tls client\nverify-x509-name evil.example name\nverify-x509-name evil2\ntls-remote evil\nns-cert-type server"))
        assertIs<SanitizeResult.Ok>(r)
        assertEquals(1, r.config.lines().count { it.startsWith("verify-x509-name") })
        assertEquals(1, r.config.lines().count { it.startsWith("remote-cert-tls") })
        assertFalse(r.config.contains("evil"))
        assertFalse(r.config.contains("tls-remote"))
    }

    @Test fun rogueRootIsRejectedEvenThoughOpenVpnWouldTrustIt() {
        // A manipulated directory ships its own CA and a server certificate issued by it.
        assertRejected(san(cfg(ca = Pki.ROGUE)), RejectCode.IDENTITY, "does not trust")
    }

    @Test fun trustedRootCannotSmuggleARogueRootAlongside() {
        assertRejected(san(cfg(ca = Pki.ROOT + "\n" + Pki.ROGUE)), RejectCode.IDENTITY)
    }

    @Test fun intermediateThatChainsToTrustedRootIsAccepted() {
        val r = san(cfg(ca = Pki.ROOT + "\n" + Pki.INTER))
        assertIs<SanitizeResult.Ok>(r)
        assertEquals(setOf(Pki.ROOT_FP, Pki.INTER_FP), r.caFingerprints.toSet())
    }

    @Test fun lonelyIntermediateAndUnchainedCertsAreRejected() {
        assertRejected(san(cfg(ca = Pki.INTER)), RejectCode.IDENTITY)            // no root at all
        assertRejected(san(cfg(ca = Pki.ROOT + "\n" + Pki.ORPHAN)), RejectCode.IDENTITY) // parent missing
    }

    @Test fun certClaimingTrustedIssuerNameButSignedByAnotherKeyIsRejected() {
        // Same distinguished name as the trusted root, different key: the signature must not verify.
        assertRejected(san(cfg(ca = Pki.ROOT + "\n" + Pki.FORGED)), RejectCode.IDENTITY, "chain")
    }

    @Test fun realLetsEncryptRootIsAcceptedThroughBuiltInPins() {
        val builtInOnly = ServerAuthPolicy.of(emptySet())
        assertIs<SanitizeResult.Ok>(ConfigSanitizer.sanitize(cfg(ca = Pki.ISRG_X1), EncryptionMode.COMPATIBLE, ip, builtInOnly))
        assertEquals(Pki.ISRG_X1_FP, ServerAuthPolicy.BUILT_IN_ROOTS.first { it == Pki.ISRG_X1_FP })
    }

    @Test fun garbageInCaBlockIsRejectedNotThrown() {
        val bad = listOf(
            "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----",
            "-----BEGIN CERTIFICATE-----\n!!!notbase64!!!\n-----END CERTIFICATE-----",
            "-----BEGIN CERTIFICATE-----\nMIIB",
            "no certificate here",
            Pki.ROOT.replace("MII", "MIJ"),
        )
        // Rejected either by the character allowlist (INVALID) or by certificate inspection (IDENTITY); never accepted, never thrown.
        for (ca in bad) assertRejected(san(cfg(ca = ca)))
    }

    @Test fun tooManyCertificatesAreRejected() {
        assertRejected(san(cfg(ca = (1..9).joinToString("\n") { Pki.ROOT })), RejectCode.IDENTITY, "too many")
    }

    // ---------- B8: destinations and profile shape ----------

    @Test fun reservedAndInternalDestinationsAreRefused() {
        val bad = listOf(
            "127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1", "169.254.169.254", "100.64.0.1",
            "0.0.0.0", "224.0.0.1", "255.255.255.255", "240.0.0.1", "198.18.0.1", "192.0.2.5", "198.51.100.9",
            "::1", "::", "fe80::1", "fc00::1", "ff02::1", "::ffff:8.8.8.8", "2001:db8::1", "2002:808:808::1", "64:ff9b::808:808",
        )
        for (a in bad) assertRejected(san(cfg(remote = "remote $a 1194"), expect = a), RejectCode.ADDRESS)
        assertIs<SanitizeResult.Ok>(san(cfg(remote = "remote 8.8.8.8 1194"), expect = "8.8.8.8"))
        assertIs<SanitizeResult.Ok>(san(cfg(remote = "remote 2606:4700:4700::1111 1194"), expect = "2606:4700:4700::1111"))
    }

    @Test fun configRemoteMustMatchTheDirectoryAddress() {
        assertRejected(san(cfg(remote = "remote 203.0.114.99 1194")), RejectCode.ADDRESS, "differs")
    }

    @Test fun hostnameInProfileIsReplacedByTheVettedLiteral() {
        val r = san(cfg(remote = "remote evil.example.com 443"))
        assertIs<SanitizeResult.Ok>(r)
        assertTrue(r.config.lines().contains("remote $ip 443"))
        assertFalse(r.config.contains("evil.example.com"))
    }

    @Test fun ambiguousOrUnsupportedProfilesAreRefused() {
        assertRejected(san(cfg(remote = "remote $ip 1194\nremote 203.0.114.8 1194")), RejectCode.UNSUPPORTED)
        assertRejected(san(cfg(extra = "<connection>\nremote 203.0.114.8 1194\n</connection>")), RejectCode.UNSUPPORTED)
        assertRejected(san(cfg(extra = "<tls-crypt-v2>\nAAAA\n</tls-crypt-v2>")), RejectCode.UNSUPPORTED)
        assertNull(ConfigInfoParser.parse(cfg(proto = "proto tcp-server")))
        assertNull(ConfigInfoParser.parse(cfg(proto = "proto sctp")))
        assertNull(ConfigInfoParser.parse(cfg(remote = "remote $ip notaport")))
        assertNull(ConfigInfoParser.parse(cfg(remote = "remote $ip 70000")))
        assertNull(ConfigInfoParser.parse(cfg(remote = "remote $ip 0")))
    }

    @Test fun oversizeAndControlCharactersAreRefused() {
        assertRejected(san(cfg(extra = "# " + "x".repeat(70_000))), RejectCode.OVERSIZE)
        assertRejected(san(cfg(extra = "verb 3\u0000")), RejectCode.INVALID)
        assertRejected(san(cfg(extra = "verb 3\u001b[2J")), RejectCode.INVALID)
    }

    @Test fun strictAddressLiteralParsing() {
        for (s in listOf("010.1.1.1", "1.2.3", "1.2.3.4.5", "256.1.1.1", "1.2.3.4 ", " 1.2.3.4", "1.2.3.-4", "0x7f.0.0.1", "2130706433", "1e2.1.1.1", "", "::g")) {
            assertNull(AddressPolicy.parseLiteral(s), "'$s' must not parse")
        }
        assertEquals("2001:db8:0:0:0:0:0:1", AddressPolicy.canonical("2001:DB8::1")?.let { it })
    }

    // ---------- B7: compression ----------

    @Test fun compressionIsNeverForwardedAndIsReported() {
        val r = san(cfg(extra = "comp-lzo yes\ncompress lz4\ncomp-noadapt"))
        assertIs<SanitizeResult.Ok>(r)
        assertFalse(r.config.contains("comp"))
        assertEquals(setOf("comp-lzo", "compress", "comp-noadapt"), r.dropped)
    }

    // ---------- B6: cipher policy ----------

    @Test fun cipherVerdictMatrix() {
        fun v(obs: String?, decl: String?, m: EncryptionMode) = CipherPolicy.evaluate(obs, decl, m)
        // observed ciphers decide
        assertEquals(CipherVerdict.Accept("AES-256-GCM", true), v("aes-256-gcm", "AES-128-CBC", EncryptionMode.STRONG))
        assertEquals(CipherVerdict.Accept("AES-128-CBC", true), v("AES-128-CBC", null, EncryptionMode.COMPATIBLE))
        assertIs<CipherVerdict.Reject>(v("AES-128-CBC", "AES-256-GCM", EncryptionMode.STRONG))
        assertIs<CipherVerdict.Reject>(v("BF-CBC", "AES-256-GCM", EncryptionMode.COMPATIBLE))
        assertIs<CipherVerdict.Reject>(v("none", null, EncryptionMode.COMPATIBLE))
        // an unobserved cipher is never reported as the declared one, and is never enough for Strong
        assertEquals(CipherVerdict.Accept(null, false), v(null, "AES-256-GCM", EncryptionMode.COMPATIBLE))
        assertIs<CipherVerdict.Reject>(v(null, "AES-256-GCM", EncryptionMode.STRONG))
        assertIs<CipherVerdict.Reject>(v(null, "BF-CBC", EncryptionMode.COMPATIBLE))
    }

    @Test fun dataCipherAllowlistNeverContainsWeakCiphers() {
        assertEquals("AES-256-GCM:AES-128-GCM:CHACHA20-POLY1305:AES-128-CBC", CipherPolicy.dataCiphers(EncryptionMode.COMPATIBLE, "AES-128-CBC"))
        assertEquals("AES-256-GCM:AES-128-GCM:CHACHA20-POLY1305", CipherPolicy.dataCiphers(EncryptionMode.COMPATIBLE, "BF-CBC"))
        assertEquals("AES-256-GCM:AES-128-GCM:CHACHA20-POLY1305", CipherPolicy.dataCiphers(EncryptionMode.STRONG, "AES-128-CBC"))
        assertEquals("AES-256-GCM:AES-128-GCM:CHACHA20-POLY1305", CipherPolicy.dataCiphers(EncryptionMode.COMPATIBLE, null))
    }

    // ---------- C1: ConnectResult ----------

    @Test fun failureTextNeverLandsInTheCipherField() {
        val r = ConnectResult.failed(FailureKind.AUTH, "Authentication failed")
        assertNull(r.cipher)
        assertEquals("Authentication failed", r.error)
        assertTrue(r.hard)
        assertFalse(r.cipherVerified)
        assertFalse(ConnectResult.failed(FailureKind.TIMEOUT, "t").hard)
        assertFalse(ConnectResult.failed(FailureKind.CANCELLED, "c").hard)
        val ok = ConnectResult.connected(null)
        assertTrue(ok.success); assertNull(ok.error); assertFalse(ok.cipherVerified)
        assertTrue(ConnectResult.connected("AES-256-GCM").cipherVerified)
    }

    // ---------- gate ----------

    @Test fun gateDisconnectsAPolicyViolatorAndRecordsIt() = runTest {
        val rep = ServerLedger(WallClock { testScheduler.currentTime })
        val t = FakeTunnel { Behavior(cipher = "AES-128-CBC") }
        val gate = ConnectionGate(t, EncryptionMode.STRONG, rep, clock = Clock { testScheduler.currentTime })
        val r = gate.connect(srv("a", cipher = "AES-256-GCM"), 10_000)
        assertIs<GateResult.Refused>(r)
        assertEquals(FailureKind.POLICY, r.result.kind)
        assertNull(t.currentServerId, "violating tunnel must be torn down")
        assertEquals(1, t.disconnects)
        assertTrue(rep.isBlocked("a"))
    }

    @Test fun gateAcceptsUnverifiedCipherInCompatibleOnlyAndLabelsItUnverified() = runTest {
        val t = FakeTunnel { Behavior(cipher = null) }
        val ok = ConnectionGate(t, EncryptionMode.COMPATIBLE, clock = Clock { testScheduler.currentTime }).connect(srv("a"), 10_000)
        assertIs<GateResult.Open>(ok)
        assertNull(ok.admitted.cipher); assertFalse(ok.admitted.cipherVerified)
        val strict = ConnectionGate(FakeTunnel { Behavior(cipher = null) }, EncryptionMode.STRONG, clock = Clock { testScheduler.currentTime })
            .connect(srv("a", cipher = "AES-256-GCM"), 10_000)
        assertIs<GateResult.Refused>(strict)
    }

    @Test fun gateRecheckDisconnectsWhenPolicyTightened() = runTest {
        val t = FakeTunnel { Behavior(cipher = "AES-128-CBC") }
        val s = srv("a")
        val open = ConnectionGate(t, EncryptionMode.COMPATIBLE, clock = Clock { testScheduler.currentTime }).connect(s, 10_000) as GateResult.Open
        val strong = ConnectionGate(t, EncryptionMode.STRONG, clock = Clock { testScheduler.currentTime })
        assertFalse(strong.recheck(open.admitted))
        assertNull(t.currentServerId)
    }

    // ---------- B8: fuzz / adversarial ----------

    private val forbidden = listOf(
        "script-security", "plugin", "management", "up ", "down ", "route", "dhcp-option", "setenv", "proxy", "log",
        "status", "daemon", "chroot", "cd ", "pull-filter", "redirect-gateway", "comp", "ifconfig", "tls-verify", "ca-path",
        "askpass", "key-method", "dev-node", "writepid", "echo", "ipchange", "learn-address", "client-connect", "auth-user-pass-verify",
    )

    @Test fun fuzzedProfilesNeverCrashAndNeverLeakDangerousDirectives() {
        val directives = listOf(
            "script-security 2", "up /system/bin/sh", "down x", "plugin /x.so", "management 0.0.0.0 1", "route 10.0.0.0 255.0.0.0",
            "dhcp-option DNS 6.6.6.6", "setenv A B", "http-proxy 1.1.1.1 80", "socks-proxy 1.1.1.1 1", "log /sdcard/x", "status /x",
            "pull-filter accept x", "redirect-gateway local", "comp-lzo", "compress lz4", "remote 10.0.0.1 22", "remote evil.example 1",
            "proto tcp-server", "cipher BF-CBC", "auth none", "ifconfig 1.1.1.1 2.2.2.2", "tls-verify /bin/x", "askpass /etc/x", "echo hi",
            "writepid /x", "ipchange x", "client-connect x", "<connection>", "</connection>", "<extra-certs>", "</extra-certs>", "<key>", "</key>",
            "<ca>", "</ca>", "<cert>", "</cert>", "<tls-crypt-v2>", "tls-auth ta.key", "key-direction 9", "verify-x509-name x", "remote-cert-tls client",
            "\u0000", "\t\t", "#", ";", "", "<", ">", "</>", "<>",
        )
        for (seed in 0 until 600) {
            val rnd = Random(seed)
            val lines = ArrayList(cfg().lines())
            repeat(rnd.nextInt(1, 12)) { lines.add(rnd.nextInt(lines.size + 1), directives[rnd.nextInt(directives.size)]) }
            if (rnd.nextInt(4) == 0 && lines.isNotEmpty()) lines.removeAt(rnd.nextInt(lines.size))
            var text = lines.joinToString("\n")
            if (rnd.nextInt(5) == 0 && text.isNotEmpty()) { // random byte-level damage
                val b = text.toCharArray(); repeat(rnd.nextInt(1, 8)) { b[rnd.nextInt(b.size)] = rnd.nextInt(0, 256).toChar() }; text = String(b)
            }
            val mode = if (rnd.nextBoolean()) EncryptionMode.COMPATIBLE else EncryptionMode.STRONG
            val r = try { san(text, mode) } catch (e: Throwable) { error("seed $seed threw $e") }
            if (r is SanitizeResult.Ok) {
                val out = r.config
                val body = out.substringBefore("<ca>") + out.substringAfter("</ca>")
                for (f in forbidden) {
                    // The only allowed matches are our own appended lines.
                    val offending = body.lines().filter { it.contains(f) && !it.startsWith("remote-cert-tls") && !it.startsWith("verify-x509-name") }
                    assertTrue(offending.isEmpty(), "seed $seed leaked '$f': $offending\n$out")
                }
                assertEquals(1, out.lines().count { it.startsWith("remote ") }, "seed $seed")
                assertTrue(out.lines().contains("remote ${r.info.port.let { "$ip $it" }}"), "seed $seed dialled an unvetted address\n$out")
                assertTrue(out.lines().contains("remote-cert-tls server") && out.lines().contains("verify-x509-name opengw.net name"), "seed $seed")
                assertTrue(out.lines().none { it.startsWith("proto") && it != "proto udp" && it != "proto tcp-client" }, "seed $seed")
                if (mode == EncryptionMode.STRONG) assertTrue(CipherPolicy.isAead(r.info.cipher), "seed $seed")
                r.info.cipher?.let { assertFalse(CipherPolicy.isWeak(it), "seed $seed") }
            }
        }
    }

    @Test fun fuzzedDirectoryCsvNeverCrashes() {
        val good = Base64.getEncoder().encodeToString(cfg().toByteArray())
        val rnd = Random(7)
        repeat(400) { seed ->
            val sb = StringBuilder("*vpn_servers\n#HostName,IP\n")
            repeat(rnd.nextInt(0, 30)) {
                val fields = MutableList(15) { listOf("x", "", "999999999999999999999", "-1", "JP", "ZZ", "1.2.3.4", "10.0.0.1", "😀", "a,b").random(rnd) }
                fields[0] = "h${rnd.nextInt(5)}"
                if (rnd.nextBoolean()) fields[1] = "203.0.114.${rnd.nextInt(1, 250)}"
                if (rnd.nextBoolean()) fields[6] = listOf("JP", "KR", "US", "uk", "XX", "J", "").random(rnd)
                fields[14] = if (rnd.nextInt(3) == 0) "!!!" else good
                sb.append(fields.joinToString(",")).append('\n')
            }
            val rep = VpnGateParser.parseDetailed(sb.toString())
            assertEquals(rep.rowsSeen, rep.servers.size + rep.rejected.values.sum(), "seed $seed accounting")
            rep.servers.forEach {
                assertTrue(AddressPolicy.isPublicUnicast(it.ip)); assertNotNull(VpnGateParser.normalizeCountry(it.countryCode))
            }
            assertEquals(rep.servers.size, rep.servers.map { it.id }.toSet().size)
        }
    }

    @Test fun countryCodesAreValidatedAndNormalised() {
        assertEquals("GB", VpnGateParser.normalizeCountry("uk"))
        assertEquals("JP", VpnGateParser.normalizeCountry(" jp "))
        assertNull(VpnGateParser.normalizeCountry("ZZ")); assertNull(VpnGateParser.normalizeCountry("JPN")); assertNull(VpnGateParser.normalizeCountry(""))
    }
}
