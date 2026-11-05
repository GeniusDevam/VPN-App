package io.github.geniusdevam.vpnapp.engine

/**
 * Human-readable view of one server's saved history (A4): what happened, what was measured on THIS network, how old each
 * fact is, and how close the record is to the seven-day absence deletion. Pure, so every sentence is unit-tested.
 */
object RecordSummary {
    fun ago(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> "${s / 86_400} d ago"
        }
    }

    fun lines(r: ServerRecord, fingerprint: String?, now: Long): List<String> = buildList {
        if (r.successCount + r.failureCount > 0) {
            add(
                buildString {
                    append("✓ ${r.successCount} · ✗ ${r.failureCount}")
                    r.lastSuccessAt?.let { append(" · last connected ${ago(now - it)}") }
                    r.connectMsAvg?.let { append(" · connects in ~%.1f s".format(it / 1000)) }
                    r.dropRate?.let { append(" · dropped ${"%.0f".format(it * 100)}% of sessions") }
                    r.lastFailure?.let { append(" · last problem: ${it.name.lowercase().replace('_', ' ')}") }
                },
            )
        }
        val net = fingerprint?.let { r.networks[it] }
        if (net != null && (net.latencyMs != null || net.downMbps != null)) {
            add(
                buildString {
                    append("On this network: ")
                    val parts = ArrayList<String>()
                    net.latencyMs?.let { parts += "%.0f ms".format(it) + if (net.probeMethod == ProbeMethod.TCP_FALLBACK) " (host only, weak)" else "" }
                    if (net.downMbps != null) parts += "↓ %.0f Mbps".format(net.downMbps) + (net.upMbps?.let { " ↑ %.0f Mbps".format(it) } ?: "")
                    append(parts.joinToString(", "))
                    val at = maxOf(net.latencyAt, net.throughputAt).takeIf { it > 0 }
                    at?.let { append(" · measured ${ago(now - it)}") }
                },
            )
        }
        r.lastCipher?.let { add("Last encryption: $it ${if (r.lastCipherVerified) "(observed)" else "(not verified)"}") }
        val seen = "In the server list ${ago(now - r.lastSeenInDirectoryAt)}"
        if (r.absentObservedMs > 0) {
            val days = r.absentObservedMs / 86_400_000.0
            add("$seen · missing from ${"%.1f".format(days)} of ${ServerLedger.ABSENCE_LIMIT_MS / 86_400_000} days; this record is deleted at ${ServerLedger.ABSENCE_LIMIT_MS / 86_400_000}")
        } else add(seen)
    }
}
