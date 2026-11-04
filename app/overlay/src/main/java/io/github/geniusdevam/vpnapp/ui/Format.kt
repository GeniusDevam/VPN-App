package io.github.geniusdevam.vpnapp.ui

import io.github.geniusdevam.vpnapp.engine.Countries
import io.github.geniusdevam.vpnapp.engine.Scope
import io.github.geniusdevam.vpnapp.engine.Server
import io.github.geniusdevam.vpnapp.engine.Transport

fun rate(bytesPerSec: Long): String = when {
    bytesPerSec >= 1_000_000 -> "%.1f MB/s".format(bytesPerSec / 1e6)
    bytesPerSec >= 1_000 -> "%.0f KB/s".format(bytesPerSec / 1e3)
    else -> "$bytesPerSec B/s"
}

fun duration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

fun megabytes(b: Long): String = "%.0f MB".format(b / 1048576.0)

fun scopeLabel(s: Scope): String = when (s) {
    Scope.Any -> "🌐 Any country"
    is Scope.Country -> "${Countries.flag(s.iso)} ${Countries.name(s.iso)}"
}

fun serverTitle(s: Server): String = "${Countries.flag(s.countryCode)} ${s.id}"

fun serverDetail(s: Server): String = buildString {
    append(if (s.transport == Transport.UDP) "UDP" else "TCP")
    s.reportedSpeedBps?.let { append(" · %.0f Mbps".format(it / 1e6)) }
    s.sessions?.let { append(" · $it users") }
    s.logPolicy?.let { append(" · logs: $it") }
}
