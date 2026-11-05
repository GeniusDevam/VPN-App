package io.github.geniusdevam.vpnapp.engine

import java.io.File
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/**
 * Test-only helper for tools/probe-test.sh: runs the REAL [NetworkProber] against a real OpenVPN server using a profile file.
 * Usage: ProbeCliKt <profileFile> <ip> <port> <udp|tcp>. Prints `method=<M> rtt=<ms|none>`.
 */
fun main(args: Array<String>) {
    if (args.size < 4) { System.err.println("usage: <profile> <ip> <port> <udp|tcp>"); exitProcess(64) }
    val config = File(args[0]).readText()
    val info = ConfigInfoParser.parse(config)
    val s = Server(
        id = "probe-test", ip = args[1], port = args[2].toInt(), transport = if (args[3] == "tcp") Transport.TCP else Transport.UDP,
        countryCode = "JP", countryName = "Japan", reportedSpeedBps = null, sessions = null, uptimeMs = null, logPolicy = null,
        operator = null, message = null, declaredCipher = info?.cipher, tlsAuthOrCrypt = info?.tlsAuthOrCrypt ?: false, config = config,
    )
    val r = runBlocking { NetworkProber().probe(s, 3_000) }
    println("method=${r.method} rtt=${r.rttMs?.let { "%.2f".format(it) } ?: "none"}")
}
