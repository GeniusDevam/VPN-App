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

@Composable
fun HomeScreen(withVpnPermission: (() -> Unit) -> Unit) {
    val ui by Session.ui.collectAsState()
    val settings by Repo.settings.collectAsState()
    val servers by Repo.servers.collectAsState()
    val now by rememberNow()
    var picker by remember { mutableStateOf<RunMode?>(null) }
    var showReport by remember { mutableStateOf(false) }
    val options = remember(servers) { servers.filter { !Repo.reputation.isBlocked(it.id) }.countryOptions() }
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
                title = "Quick Connect",
                subtitle = "Connects to the fastest server it can find in 35 seconds or less.",
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
                title = "Best Connect",
                subtitle = "Takes its time: scans every server and speed-tests the best ones to find the fastest.",
                scopeText = settings.bestScope,
                busy = busy,
                running = ui.run?.mode == RunMode.BEST,
                actionLabel = "Find the best",
                tonal = true,
                onPickCountry = { picker = RunMode.BEST },
                onStart = { withVpnPermission { Session.startBest() } },
            ) { run -> BestProgress(run, now) }
        }
        ui.lastReport?.let { r ->
            item { TextButton(onClick = { showReport = true }) { Text("View last Best Connect results") } }
        }
        item {
            val age = if (Repo.serversFetchedAt > 0) duration(now - Repo.serversFetchedAt) + " ago" else "not loaded yet"
            Text(
                "${servers.size} free servers from VPN Gate (list updated $age). Servers come and go; the list refreshes each time you connect.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    picker?.let { mode ->
        CountryPickerSheet(
            options = options,
            current = if (mode == RunMode.QUICK) settings.quickScope else settings.bestScope,
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
            val headline = when {
                connected -> "🔒 Connected"
                v.status == ConnState.CONNECTING -> "Connecting…"
                else -> "🔓 Not connected"
            }
            Text(headline, style = MaterialTheme.typography.headlineSmall)
            if (connected) {
                v.server?.let { Text(serverTitle(it) + " · " + Countries.name(it.countryCode, fallback = it.countryName), style = MaterialTheme.typography.titleMedium) }
                Text(
                    "Encryption: ${v.cipher ?: "negotiated"}  ·  Session " + duration(now - (v.connectedAtMs ?: now)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("↓ ${rate(v.rxRate)}   ↑ ${rate(v.txRate)}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Run by a volunteer: avoid logging in to banking or other sensitive accounts.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
            } else {
                Text(
                    if (v.status == ConnState.CONNECTING) v.stateName else "Choose Quick Connect or Best Connect below.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (v.status == ConnState.CONNECTING && ui.run == null) OutlinedButton(onClick = onDisconnect) { Text("Stop") }
            }
        }
    }
}

@Composable
private fun ModeCard(
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                CountryChip(scopeText, enabled = !busy, onClick = onPickCountry)
                if (tonal) FilledTonalButton(onClick = onStart, enabled = !busy) { Text(actionLabel) }
                else Button(onClick = onStart, enabled = !busy) { Text(actionLabel) }
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
        Text("Elapsed ${duration(now - run.startedAtMs)}. This switches servers while testing; you stay protected.", style = MaterialTheme.typography.bodySmall)
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
                    "${r.reason} Scanned ${r.scannedCount}, tested ${r.testedCount}, " +
                        "${megabytes(r.bytesUsed)} used, took ${duration(r.elapsedMs)}.",
                    style = MaterialTheme.typography.bodySmall,
                )
                LazyColumn {
                    items(r.entries.take(10)) { e ->
                        val t = e.throughput
                        Text(
                            "${Countries.flag(e.server.countryCode)} ${e.server.id}: " +
                                (if (t != null) "↓ ${"%.0f".format(t.downMbps)} ↑ ${"%.0f".format(t.upMbps)} Mbps, ${"%.0f".format(t.idleRttMs)} ms, ${e.cipher ?: "?"}" else "-"),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        },
    )
}
