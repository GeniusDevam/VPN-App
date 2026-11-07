package io.github.geniusdevam.vpnapp.engine

import kotlin.random.Random
import kotlin.system.exitProcess
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Engine micro-benchmarks for the performance review (docs/PERFORMANCE.md). Not a unit test: run with
 *   gradle -q benchCli                 (defaults)       or   gradle -q benchCli --args="5000 3"   (max rows, repetitions)
 * Each case prints the median wall time of several repetitions after a warm-up. The JVM on a desktop/CI runner is much faster than a
 * phone; the numbers are for comparing sizes and code versions, and docs/PERFORMANCE.md states the phone scaling assumptions.
 */
private fun pem(label: String, bytes: Int, r: Random): String {
    val b64 = java.util.Base64.getEncoder().encodeToString(ByteArray(bytes).also { r.nextBytes(it) })
    return "-----BEGIN $label-----\n" + b64.chunked(64).joinToString("\n") + "\n-----END $label-----"
}

/** A profile about the size and shape of a real VPN Gate one: CA chain, client certificate and key, the usual directives. */
private fun profile(ip: String, r: Random) = buildString {
    append("###############################################################################\n# OpenVPN 2.0 Sample Configuration File\n")
    repeat(40) { append("# comment line $it of the long header VPN Gate profiles carry, explaining every option in detail\n") }
    append("client\ndev tun\nproto tcp\nremote $ip 443\ncipher AES-128-CBC\nauth SHA1\nresolv-retry infinite\nnobind\npersist-key\npersist-tun\nverb 3\n")
    append("<ca>\n${Pki.ROOT}\n${Pki.INTER}\n</ca>\n")
    append("<cert>\n${pem("CERTIFICATE", 1200, r)}\n</cert>\n<key>\n${pem("PRIVATE KEY", 1200, r)}\n</key>\n")
}

private fun rows(n: Int, seed: Int = 1): String {
    val r = Random(seed)
    val cc = listOf("JP", "KR", "US", "TH", "VN", "RU", "DE", "IN", "TW", "MX")
    return buildString {
        append("*vpn_servers\n#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64\n")
        for (i in 0 until n) {
            val ip = "${11 + i / 65000}.${(i / 250) % 250 + 1}.${i % 250 + 1}.${r.nextInt(1, 250)}"
            val b64 = java.util.Base64.getEncoder().encodeToString(profile(ip, r).toByteArray())
            append("vpn$i,$ip,${r.nextInt(100000)},${r.nextInt(5, 300)},${r.nextLong(1, 900_000_000)},Country,${cc[i % cc.size]},${r.nextInt(50)},${r.nextLong(1, 99_999_999)},${r.nextInt(99999)},${r.nextLong(1, 9_999_999_999)},2weeks,op,msg,$b64\n")
        }
        append("*\n")
    }
}

private inline fun <T> timed(reps: Int, block: () -> T): Pair<Double, T> {
    var last: T = block() // warm-up
    val ms = (1..reps).map { val t = System.nanoTime(); last = block(); (System.nanoTime() - t) / 1e6 }.sorted()
    return ms[ms.size / 2] to last
}

fun main(args: Array<String>) {
    val maxRows = args.getOrNull(0)?.toIntOrNull() ?: 1500
    val reps = args.getOrNull(1)?.toIntOrNull() ?: 5
    val sizes = listOf(100, 1000, maxRows).distinct().filter { it <= maxRows }
    val auth = ServerAuthPolicy.of(setOf(Pki.ROOT_FP))
    val rt = Runtime.getRuntime()
    println("engine benchmarks: JVM ${System.getProperty("java.version")}, ${rt.availableProcessors()} CPUs, median of $reps after warm-up")
    println("%-46s %10s %12s".format("case", "median ms", "per item"))
    fun line(name: String, ms: Double, n: Int) = println("%-46s %10.1f %10.3f ms".format(name, ms, ms / n))

    for (n in sizes) {
        val csv = rows(n)
        val mb = csv.length / 1e6
        val (pMs, report) = timed(reps) { VpnGateParser.parseDetailed(csv) }
        line("parse $n rows (%.1f MB)".format(mb), pMs, n)
        val (dMs, result) = timed(reps) { DirectoryProcessor.process(report, auth) }
        line("validate+sanitize+identity $n rows", dMs, n)
        require(result.usable.size == n) { "benchmark rows must all be usable, got ${result.usable.size}/$n: ${result.stats.rejected}" }
        val servers = result.usable
        // Heap actually retained per pooled server (config text included), measured with everything else dropped.
        run {
            fun used(): Long { repeat(3) { System.gc(); Thread.sleep(50) }; return rt.totalMemory() - rt.freeMemory() }
            val before = used()
            val keep = VpnGateParser.parseDetailed(rows(n, seed = 7)).let { DirectoryProcessor.process(it, auth).usable }
            val after = used()
            println("  heap retained by $n pooled servers: %.1f MB (%.1f KB each)".format((after - before) / 1e6, (after - before) / 1024.0 / keep.size))
        }
        val (mMs, _) = timed(reps) { DirectoryPool().apply { merge(servers, 1_000L) } }
        line("pool merge $n", mMs, n)
        val listing = servers.associate { it.id to EndpointInfo(it.ip, it.port, it.transport, it.countryCode) }
        val (oMs, ledger) = timed(reps) { ServerLedger().apply { observeDirectory(listing, usable = true, at = 1_000L) } }
        line("ledger observe (new) $n", oMs, n)
        servers.forEach { s ->
            repeat(3) { ledger.recordSuccess(s.id) }
            ledger.recordLatency(s.id, "net-a", LatencyStats(40.0, 3.0, 0.0, 5, ProbeMethod.OPENVPN_UDP_RESET))
        }
        val (eMs, json) = timed(reps) { Codec.ledgerToJson(ledger.snapshot()).toString() }
        line("ledger encode $n (%.0f KB JSON)".format(json.length / 1024.0), eMs, n)
        val (fMs, _) = timed(reps) { Codec.ledgerFromJson(org.json.JSONObject(json)) }
        line("ledger decode $n", fMs, n)
        val (sMs, _) = timed(reps) { servers.take(200).forEach { ConfigSanitizer.sanitize(it.config, EncryptionMode.COMPATIBLE, it.ip, auth) } }
        line("sanitize one profile (x200)", sMs, 200)
        val ca = "${Pki.ROOT}\n${Pki.INTER}"
        val (cMs, _) = timed(reps) { repeat(200) { CertInspector.judgeCaBlock(ca, auth.roots) } }
        line("identity check of one <ca> chain (x200)", cMs, 200)
        // Selection CPU cost: Quick Connect on the whole pool with instant fake probes/tunnel (virtual time), measured in wall time.
        val (qMs, _) = timed(reps) {
            runTest {
                val e = QuickConnectEngine(FakeCatalog(servers), FakeProber { 30.0 + (it.id.hashCode() and 63) }, FakeTunnel(),
                    clock = clockOf(this))
                e.run(Scope.Any)
            }
        }
        line("Quick Connect selection CPU, pool $n", qMs, 1)
        println("  retained heap after this size: %.0f MB".format((rt.totalMemory() - rt.freeMemory()) / 1e6))
    }
    // The download cap: a listing above VpnGateParser.MAX_INPUT_CHARS is refused as a whole (and the cached list is kept).
    val big = rows(2000)
    val capped = VpnGateParser.parseDetailed(big)
    println("listing of 2000 rows (%.1f MB): servers=%d oversize=%s (cap %.0f MB)".format(big.length / 1e6, capped.servers.size, capped.oversize, VpnGateParser.MAX_INPUT_CHARS / 1e6))
    val blob = java.util.Base64.getMimeEncoder().encodeToString(ByteArray(2_000_000).also { Random(3).nextBytes(it) })
    val (bMs, _) = timed(reps) { Base64Codec.decodeMime(blob) }
    line("base64 MIME decode 2 MB (portable codec)", bMs, 1)
    val (jMs, _) = timed(reps) { java.util.Base64.getMimeDecoder().decode(blob) }
    line("base64 MIME decode 2 MB (JDK, for comparison)", jMs, 1)
    exitProcess(0)
}

private fun clockOf(s: TestScope) = Clock { s.testScheduler.currentTime }
