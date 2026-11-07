package io.github.geniusdevam.vpnapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.unit.dp
import io.github.geniusdevam.vpnapp.ConnState
import io.github.geniusdevam.vpnapp.RunMode
import io.github.geniusdevam.vpnapp.RunUi
import io.github.geniusdevam.vpnapp.Session
import io.github.geniusdevam.vpnapp.SessionUi
import io.github.geniusdevam.vpnapp.data.Repo
import io.github.geniusdevam.vpnapp.engine.BestReport
import io.github.geniusdevam.vpnapp.engine.Countries
import io.github.geniusdevam.vpnapp.engine.PhaseId
import io.github.geniusdevam.vpnapp.engine.countryOptions
import io.github.geniusdevam.vpnapp.engine.state

@Composable
fun HomeScreen(withVpnPermission: (() -> Unit) -> Unit) {
    val ui by Session.ui.collectAsState()
    val settings by Repo.settings.collectAsState()
    val servers by Repo.servers.collectAsState()
    val now by rememberNow()
    var picker by rememberSaveable { mutableStateOf<RunMode?>(null) }
    var showReport by rememberSaveable { mutableStateOf(false) }
    val options = remember(servers) { servers.filter { !Repo.reputation.isBlocked(it.id) }.countryOptions() }
    val directoryDiag by Repo.directory.collectAsState()
    val directoryState = directoryDiag.state(servers.size)
    val busy = ui.run != null

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { StatusCard(ui, now) { Session.disconnect() } }
        ui.banner?.let { b ->
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (b.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(b.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { Session.dismissBanner() }) { Text("OK") }
                    }
                }
            }
        }
        item {
            ModeCard(
                tag = "quick",
                title = "Quick Connect",
                subtitle = "Connects to a fast server in 35 seconds or less. It compares response times of a sample of servers, so the pick is an estimate; it does not measure speed.",
                scopeText = settings.quickScope,
                busy = busy,
                running = ui.run?.mode == RunMode.QUICK,
                actionLabel = "Connect",
                onPickCountry = { picker = RunMode.QUICK },
                onStart = { withVpnPermission { Session.startQuick() } },
            ) { run -> QuickProgress(run, now) }
        }
        item {
            ModeCard(
                tag = "best",
                title = "Best Connect",
                subtitle = "Takes its time: probes every server in the chosen country, speed-tests the best ones, and connects to the fastest it measured.",
                scopeText = settings.bestScope,
                busy = busy,
                running = ui.run?.mode == RunMode.BEST,
                actionLabel = "Find the best",
                tonal = true,
                onPickCountry = { picker = RunMode.BEST },
                onStart = { withVpnPermission { Session.startBest() } },
            ) { run -> BestProgress(run, now) }
        }
        item { WorldwideCard(ui, now, withVpnPermission) }
        ui.lastReport?.let { r ->
            item { TextButton(onClick = { showReport = true }) { Text("View last Best Connect results") } }
        }
        item { DirectoryCard(now) }
    }

    picker?.let { mode ->
        CountryPickerSheet(
            options = options,
            current = if (mode == RunMode.QUICK) settings.quickScope else settings.bestScope,
            directory = directoryState,
            directoryError = directoryDiag.lastError,
            onSelect = { sel ->
                Repo.update { if (mode == RunMode.QUICK) it.copy(quickScope = sel) else it.copy(bestScope = sel) }
                picker = null
            },
            onDismiss = { picker = null },
        )
    }
    if (showReport) ui.lastReport?.let { ReportDialog(it) { showReport = false } }
}

@Composable
private fun StatusCard(ui: SessionUi, now: Long, onDisconnect: () -> Unit) {
    val v = ui.vpn
    val connected = v.status == ConnState.CONNECTED
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val working = ui.run != null
            val headline = when {
                working -> "⏳ Working…"
                connected -> "🔒 Connected"
                v.status == ConnState.CONNECTING -> "Connecting…"
                else -> "🔓 Not connected"
            }
            Text(headline, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("status_headline"))
            if (working) {
                Text(
                    if (ui.run?.mode == RunMode.BEST || ui.run?.mode == RunMode.WORLDWIDE) "Testing servers. The VPN reconnects to each candidate in turn, so your traffic may go outside the VPN, or through test servers, until it finishes."
                    else "Finding the fastest server…",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else if (connected) {
                v.server?.let { Text(serverTitle(it) + " · " + Countries.name(it.countryCode, fallback = it.countryName), style = MaterialTheme.typography.titleMedium) }
                Text(
                    "Encryption: ${if (v.cipherVerified && v.cipher != null) "${v.cipher} (verified)" else "not verified"}  ·  Session " + duration(now - (v.connectedAtMs ?: now)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("↓ ${rate(v.rxRate)}   ↑ ${rate(v.txRate)}", style = MaterialTheme.typography.bodyMedium)
                // The action before the small print: at 200% font on a small phone the two notes below fill a whole screen.
                OutlinedButton(onClick = onDisconnect, modifier = Modifier.testTag("disconnect")) { Text("Disconnect") }
                Text(
                    "Leak protection: this app keeps the VPN interface up while it reconnects, but only Android's “Block connections without VPN” guarantees nothing leaks if the VPN drops (Settings > Kill switch).",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Run by a volunteer: avoid logging in to banking or other sensitive accounts.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    if (v.status == ConnState.CONNECTING) connectingText(v.stateName) else "Choose Quick Connect or Best Connect below.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (v.status == ConnState.CONNECTING && ui.run == null) OutlinedButton(onClick = onDisconnect) { Text("Stop") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeCard(
    tag: String,
    title: String,
    subtitle: String,
    scopeText: io.github.geniusdevam.vpnapp.engine.Scope,
    busy: Boolean,
    running: Boolean,
    actionLabel: String,
    onPickCountry: () -> Unit,
    onStart: () -> Unit,
    tonal: Boolean = false,
    progress: @Composable (RunUi) -> Unit,
) {
    val run = Session.ui.collectAsState().value.run
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            // Wraps onto two lines on narrow screens or at very large font sizes instead of squeezing the button off-screen.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CountryChip(scopeText, enabled = !busy, onClick = onPickCountry, modifier = Modifier.testTag("${tag}_chip"))
                if (tonal) FilledTonalButton(onClick = onStart, enabled = !busy, modifier = Modifier.testTag("${tag}_connect")) { Text(actionLabel) }
                else Button(onClick = onStart, enabled = !busy, modifier = Modifier.testTag("${tag}_connect")) { Text(actionLabel) }
            }
            if (running && run != null) progress(run)
        }
    }
}

@Composable
private fun QuickProgress(run: RunUi, now: Long) {
    val elapsed = (now - run.startedAtMs).coerceAtLeast(0)
    val frac = (elapsed / 35_000f).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { frac }, modifier = Modifier.size(52.dp))
                Text("${(elapsed / 1000)}s", style = MaterialTheme.typography.labelMedium)
            }
            Text(
                when (run.phase) {
                    PhaseId.SCANNING -> "Checking servers… ${run.done}/${run.total}"
                    PhaseId.CONNECTING -> "Securing connection to ${run.connectingTo?.id ?: "server"}…"
                    else -> "Loading server list…"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        run.top.forEach { r ->
            Text("${Countries.flag(r.server.countryCode)} ${r.server.id} — ${"%.0f".format(r.latency.medianMs)} ms", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { Session.cancel() }) { Text("Cancel") }
    }
}

@Composable
private fun BestProgress(run: RunUi, now: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val stage = when (run.phase) {
            PhaseId.SCANNING -> "1/4  Scanning ${run.total} servers (${run.done}/${run.total})"
            PhaseId.SPEED_TESTING -> "2/4  Speed-testing ${run.tested}/${run.planned}  ·  ${megabytes(run.bytesUsed)} used"
            PhaseId.CONFIRMING -> "3/4  Confirming the winner…"
            PhaseId.CONNECTING -> "4/4  Connecting to ${run.connectingTo?.id ?: "the best server"}…"
            else -> "Loading server list…"
        }
        Text(stage, style = MaterialTheme.typography.bodyMedium)
        val p = when (run.phase) {
            PhaseId.SCANNING -> if (run.total > 0) run.done / run.total.toFloat() else 0f
            PhaseId.SPEED_TESTING -> if (run.planned > 0) run.tested / run.planned.toFloat() else 0f
            else -> 0f
        }
        if (run.phase == PhaseId.SCANNING || run.phase == PhaseId.SPEED_TESTING) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text("Elapsed ${duration(now - run.startedAtMs)}. Testing the most promising servers one by one.", style = MaterialTheme.typography.bodySmall)
        run.leaderboard.take(5).forEachIndexed { i, e ->
            val t = e.throughput
            Text(
                "${i + 1}. ${Countries.flag(e.server.countryCode)} ${e.server.id} — " +
                    (if (t != null) "↓ ${"%.0f".format(t.downMbps)} Mbps · ${"%.0f".format(t.idleRttMs)} ms" else "no speed data"),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { Session.finishBestNow() }, enabled = run.phase == PhaseId.SPEED_TESTING || run.phase == PhaseId.SCANNING) {
                Text("Connect to leader now")
            }
            TextButton(onClick = { Session.cancel() }) { Text("Cancel") }
        }
    }
}

@Composable
private fun ReportDialog(r: BestReport, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Best Connect results") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Winner: ${serverTitle(r.winner)}", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${r.reason} Probed ${r.scannedCount} of ${r.poolSize} servers (${r.respondedCount} answered), speed-tested ${r.testedCount}, " +
                        "${megabytes(r.bytesUsed)} used, took ${duration(r.elapsedMs)}.",
                    style = MaterialTheme.typography.bodySmall,
                )
                r.marginOverRunnerUp?.let {
                    Text("Margin over the runner-up: ${"%.0f".format(it * 100)}% (${r.winnerRuns} speed test${if (r.winnerRuns == 1) "" else "s"} of the winner). Speed varies from minute to minute; treat close results as ties.", style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn {
                    items(r.entries.take(10)) { e ->
                        val t = e.throughput
                        Text(
                            "${Countries.flag(e.server.countryCode)} ${e.server.id}: " +
                                (if (t != null) "↓ ${"%.0f".format(t.downMbps)} ↑ ${"%.0f".format(t.upMbps)} Mbps, ${"%.0f".format(t.idleRttMs)} ms, ${e.cipher ?: "cipher not verified"}" else "-"),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        },
    )
}

/** OpenVPN's state names (upstream VpnStatus) in plain words. Unknown ones are shown as they are. */
internal fun connectingText(state: String): String = when (state) {
    "CONNECTRETRY", "RECONNECTING" -> "Connection to the server was lost. Reconnecting…"
    "NONETWORK" -> "No network. Waiting for one…"
    "RESOLVE", "TCP_CONNECT", "WAIT", "VPN_GENERATE_CONFIG", "" -> "Reaching the server…"
    "AUTH", "AUTH_PENDING" -> "Signing in to the server…"
    "GET_CONFIG", "ASSIGN_IP", "ADD_ROUTES" -> "Setting up the tunnel…"
    "USER_VPN_PERMISSION" -> "Waiting for your permission to start the VPN…"
    else -> state
}
