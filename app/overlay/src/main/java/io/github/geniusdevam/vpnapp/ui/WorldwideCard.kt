package io.github.geniusdevam.vpnapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.geniusdevam.vpnapp.RunMode
import io.github.geniusdevam.vpnapp.RunUi
import io.github.geniusdevam.vpnapp.Session
import io.github.geniusdevam.vpnapp.SessionUi
import io.github.geniusdevam.vpnapp.data.Repo
import io.github.geniusdevam.vpnapp.engine.Countries
import io.github.geniusdevam.vpnapp.engine.SearchPhase

/**
 * The optional, thorough search across all countries. Deliberately separate from Quick Connect's "Any country": it is
 * slow, uses data and battery, and only ever starts after the user reads and accepts the dialog below.
 */
@Composable
fun WorldwideCard(ui: SessionUi, now: Long, withVpnPermission: (() -> Unit) -> Unit) {
    val settings by Repo.settings.collectAsState()
    val saved by Repo.lastSearch.collectAsState()
    val run = ui.run
    val busy = run != null
    var confirm by remember { mutableStateOf(false) }
    var resume by remember { mutableStateOf(false) }
    val resumable = saved?.let { !it.finished && it.entries.isNotEmpty() && System.currentTimeMillis() - it.at < 6 * 3_600_000L } == true

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Worldwide search", style = MaterialTheme.typography.titleLarge)
            Text(
                "Optional and thorough. Samples servers in every country, then speed-tests a shortlist to find the fastest anywhere. " +
                    "It can take 10 to 45 minutes and uses data and battery. For a fast connection from any country, use Quick Connect with “Any country”.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (run?.mode == RunMode.WORLDWIDE) {
                WorldwideProgress(run, now, settings.worldwideDataMb)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = { resume = false; confirm = true }, enabled = !busy) { Text("Start…") }
                    if (resumable) OutlinedButton(onClick = { resume = true; confirm = true }, enabled = !busy) { Text("Resume") }
                }
                saved?.let { s ->
                    if (s.finished || s.entries.isNotEmpty()) {
                        val top = s.entries.maxByOrNull { it.score }
                        Text(
                            (if (s.finished) "Last search: " else "Interrupted search: ") +
                                "probed ${s.probed} of ${s.poolSize} servers, speed-tested ${s.tested}" +
                                (top?.let { "; best found ${Countries.flag(it.cc)} ${it.id} (↓ ${"%.0f".format(it.down)} Mbps)" } ?: "") + ".",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
    if (confirm) {
        WorldwideConsent(
            dataMb = settings.worldwideDataMb, allowMetered = settings.worldwideAllowMetered, resume = resume,
            onConfirm = { confirm = false; withVpnPermission { Session.startWorldwide(resume) } },
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun WorldwideConsent(dataMb: Int, allowMetered: Boolean, resume: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var understood by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (resume) "Resume worldwide search?" else "Start worldwide search?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("• Time: typically 10 to 45 minutes. It stops earlier if more testing stops helping.", style = MaterialTheme.typography.bodySmall)
                Text("• Data: up to $dataMb MB for speed tests, plus small probes to hundreds of servers. Pick the budget:", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(50, 150, 300).forEach { mb ->
                        FilterChip(selected = dataMb == mb, onClick = { Repo.update { it.copy(worldwideDataMb = mb) } }, label = { Text("$mb MB") })
                    }
                }
                Text("• Battery: keep the phone plugged in. The search pauses by itself on low battery or when the network drops, and Android may pause it when the screen is off for a long time.", style = MaterialTheme.typography.bodySmall)
                Text("• Privacy: every server that is probed sees your real IP address, as it would if you connected to it. Many volunteers will see it.", style = MaterialTheme.typography.bodySmall)
                Text("• Protection: while it tests, your traffic goes through test servers or outside any VPN. The app cannot promise continuous protection during this time. Don't run it while doing anything sensitive.", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().clickable { Repo.update { it.copy(worldwideAllowMetered = !allowMetered) } }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allowMetered, onCheckedChange = { c -> Repo.update { it.copy(worldwideAllowMetered = c) } })
                    Text("Allow on mobile data / metered Wi-Fi", style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth().clickable { understood = !understood }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = understood, onCheckedChange = { understood = it })
                    Text("I understand and want to run this search", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { Button(onClick = onConfirm, enabled = understood) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun WorldwideProgress(run: RunUi, now: Long, budgetMb: Int) {
    val p = run.search
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (p == null) {
            Text("Loading the server list…", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            val phase = when (p.phase) {
                SearchPhase.LOADING -> "Loading servers"
                SearchPhase.PROBING -> "Probing servers"
                SearchPhase.REFINING -> "Re-checking the leaders"
                SearchPhase.SPEED_TESTING -> "Speed-testing a shortlist"
                SearchPhase.CONFIRMING -> "Confirming the leaders"
                SearchPhase.CONNECTING -> "Connecting to the best"
                SearchPhase.DONE -> "Done"
            }
            Text((if (p.paused) "Paused: " else "") + phase, style = MaterialTheme.typography.titleSmall)
            run.pauseReason?.let { Text("$it The search resumes automatically when that clears.", style = MaterialTheme.typography.bodySmall) }
            val frac = if (p.poolSize > 0) (p.probed / p.poolSize.toFloat()).coerceIn(0f, 1f) else 0f
            if (p.paused || p.phase == SearchPhase.CONNECTING) LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                "Probed ${p.probed} of ${p.poolSize} servers; ${p.countriesSampled} of ${p.countriesTotal} countries sampled; ${p.responded} answered.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Speed-tested ${p.speedTested} (up to ${p.speedTestsPlanned} planned). ${megabytes(p.bytesUsed)} of $budgetMb MB budget used. Elapsed ${duration(now - run.startedAtMs)}.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (p.leaderboard.isNotEmpty()) {
                Text("Best so far (not final):", style = MaterialTheme.typography.labelMedium)
                p.leaderboard.take(5).forEachIndexed { i, e ->
                    val t = e.throughput
                    Text(
                        "${i + 1}. ${Countries.flag(e.server.countryCode)} ${e.server.id}: " +
                            (if (t != null) "↓ ${"%.0f".format(t.downMbps)} ↑ ${"%.0f".format(t.upMbps)} Mbps, ${"%.0f".format(t.idleRttMs)} ms" else "no speed data"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Text(
            "Your traffic is not protected continuously while this runs.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (p?.paused == true) FilledTonalButton(onClick = { Session.resumeWorldwide() }) { Text("Resume") }
            else OutlinedButton(onClick = { Session.pauseWorldwide() }) { Text("Pause") }
            FilledTonalButton(onClick = { Session.finishBestNow() }, enabled = p != null && p.phase != SearchPhase.CONNECTING) { Text("Connect to best so far") }
            TextButton(onClick = { Session.cancel() }) { Text("Cancel") }
        }
    }
}
