package io.github.geniusdevam.vpnapp.engine

import java.io.File
import kotlin.system.exitProcess

/**
 * Test-only helper used by tools/cert-policy-test.sh: runs the REAL [ConfigSanitizer] over every `*.profile` file in a
 * directory and writes `*.sanitized` (or `*.rejected`). Lets a shell test feed exactly what the app would hand to
 * OpenVPN to a real OpenVPN client. Usage: SanitizeCliKt <dir> <expectedIp> <trustedRootSha256>...
 */
fun main(args: Array<String>) {
    if (args.size < 3) { System.err.println("usage: <dir> <expectedIp> <trustedRootSha256>..."); exitProcess(64) }
    val dir = File(args[0]); val ip = args[1]; val roots = args.drop(2).map { it.lowercase() }.toSet()
    val auth = ServerAuthPolicy(ServerAuthPolicy.DEFAULT_NAME) { it.lowercase() in roots }
    for (f in dir.listFiles { x -> x.name.endsWith(".profile") }!!.sortedBy { it.name }) {
        val base = f.name.removeSuffix(".profile")
        when (val r = ConfigSanitizer.sanitize(f.readText(), EncryptionMode.COMPATIBLE, ip, auth)) {
            is SanitizeResult.Ok -> File(dir, "$base.sanitized").writeText(r.config)
            is SanitizeResult.Rejected -> File(dir, "$base.rejected").writeText("${r.code}: ${r.reason}\n")
        }
    }
}
