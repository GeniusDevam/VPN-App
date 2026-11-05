package io.github.geniusdevam.vpnapp.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.geniusdevam.vpnapp.data.DnsChoice
import io.github.geniusdevam.vpnapp.data.Repo
import io.github.geniusdevam.vpnapp.engine.EncryptionMode
import io.github.geniusdevam.vpnapp.engine.OptimizeFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen() {
    val st by Repo.settings.collectAsState()
    val ctx = LocalContext.current
    var showApps by remember { mutableStateOf(false) }
    var showTips by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall) }
        item { Section("Encryption") }
        item {
            Choice("Compatible (recommended)", "Uses the strongest cipher each server supports. VPN Gate servers currently use AES-128-CBC with SHA-1 authentication, which is sound; weak or broken ciphers are always refused.", st.encryption == EncryptionMode.COMPATIBLE) {
                Repo.update { it.copy(encryption = EncryptionMode.COMPATIBLE) }
            }
        }
        item {
            Choice("Strong only", "Only servers whose connection is verified to use modern authenticated encryption (AES-GCM / ChaCha20). Every VPN Gate server we have seen uses AES-128-CBC, so this setting normally finds no servers. Leave it off unless you want to refuse all of them.", st.encryption == EncryptionMode.STRONG) {
                Repo.update { it.copy(encryption = EncryptionMode.STRONG) }
            }
        }
        item { Section("Best Connect") }
        item { Choice("Balanced", "Download speed first, then responsiveness.", st.optimize == OptimizeFor.BALANCED) { Repo.update { it.copy(optimize = OptimizeFor.BALANCED) } } }
        item { Choice("Download speed", "Biggest downloads and streaming.", st.optimize == OptimizeFor.DOWNLOAD) { Repo.update { it.copy(optimize = OptimizeFor.DOWNLOAD) } } }
        item { Choice("Low latency", "Gaming and calls: low delay and jitter.", st.optimize == OptimizeFor.LOW_LATENCY) { Repo.update { it.copy(optimize = OptimizeFor.LOW_LATENCY) } } }
        item {
            Text("Servers to speed-test", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 15, 25).forEach { n ->
                    FilterChip(selected = st.maxCandidates == n, onClick = { Repo.update { it.copy(maxCandidates = n) } }, label = { Text("$n") })
                }
            }
            Text("On mobile data, Best Connect automatically uses much less data (about 60 MB per run at most). Data figures are estimates and include a small allowance for protocol overhead.", style = MaterialTheme.typography.bodySmall)
        }
        item { Section("Privacy") }
        DnsChoice.values().forEach { d ->
            item { Choice("DNS: ${d.label}", null, st.dns == d) { Repo.update { it.copy(dns = d) } } }
        }
        item {
            Text(
                "For encrypted DNS, also turn on Android's Private DNS (Settings › Network & internet › Private DNS) and enter dns.quad9.net or one.one.one.one.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            OutlinedButton(onClick = { showApps = true }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text("Apps that skip the VPN (${st.excludedApps.size})")
            }
            Text("Banking or work apps can bypass the free server entirely.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            OutlinedButton(onClick = { showTips = !showTips }, modifier = Modifier.fillMaxWidth()) { Text(if (showTips) "Hide safety tips" else "Safety tips") }
            if (showTips) Text(
                "• Prefer websites that show the padlock (HTTPS).\n" +
                    "• Don't sign in to banking, email or other important accounts through a free server.\n" +
                    "• Servers marked with shorter log retention are slightly better.\n" +
                    "• If a server behaves strangely, use ⋮ › Report & avoid.\n" +
                    "• Free servers are for bypassing local blocks and safer public Wi-Fi, not anonymity.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            OutlinedButton(onClick = { Repo.clearHistory() }, modifier = Modifier.fillMaxWidth()) { Text("Clear saved server history") }
        }
        item {
            OutlinedButton(onClick = { Repo.update { it.copy(acknowledged = false) } }, modifier = Modifier.fillMaxWidth()) { Text("Show privacy notice again") }
        }
        item { Section("Kill switch and leaks") }
        item {
            Text(
                "What this app does by itself: while it reconnects, the VPN interface stays up, and traffic is not sent outside it. " +
                    "What it cannot guarantee: if the VPN process is stopped or crashes, the phone is restarted, or Android switches networks at the wrong moment, " +
                    "apps may briefly connect without the VPN. Only Android itself can prevent that.\n\n" +
                    "To block all internet whenever the VPN is down, open Android's VPN settings, tap the gear next to “VPN App”, and turn on " +
                    "“Always-on VPN” and “Block connections without VPN”. The app cannot detect whether you did, so it never claims a kill switch.\n\n" +
                    "While Best Connect or the worldwide search is running, the VPN switches between test servers, so protection is not continuous. " +
                    "Apps you chose to skip the VPN are never protected. IPv6 is blocked when a server has no IPv6; DNS goes to the resolver chosen above.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { ctx.startActivity(Intent(AndroidSettings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open Android VPN settings")
            }
        }
        item { Section("What this app keeps and sends") }
        item {
            Text(
                "Stored on this phone, encrypted: your settings, the server list, and a history of how each server worked for you " +
                    "(successes, failures, speed, stability). History for a server is deleted after it has been missing from VPN Gate's list for 7 days of successful refreshes; " +
                    "it is never deleted just for being old. Nothing is uploaded and there is no analytics.\n\n" +
                    "Network contacts: vpngate.net (server list, HTTPS only), the VPN servers themselves, and Cloudflare's public speed-test servers during Best Connect and the worldwide search. " +
                    "Every server you probe or connect to sees your IP address.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item { Section("About") }
        item {
            Text(
                "Free servers by the VPN Gate Academic Experiment (University of Tsukuba). " +
                    "VPN engine: OpenVPN for Android (ics-openvpn) by Arne Schwabe, under the GNU GPL v2. " +
                    "This app is open source: github.com/GeniusDevam/VPN-App. " +
                    "VPN use is restricted in some countries; you are responsible for following local law.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (showApps) AppPickerDialog(st.excludedApps, onDone = { sel -> Repo.update { it.copy(excludedApps = sel) }; showApps = false }, onDismiss = { showApps = false })
}

@Composable
private fun Section(title: String) {
    Column(Modifier.padding(top = 14.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Choice(title: String, sub: String?, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private data class AppEntry(val label: String, val pkg: String)

@Composable
private fun AppPickerDialog(selected: Set<String>, onDone: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf(selected) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(i, PackageManager.MATCH_ALL)
                .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .filter { it.pkg != ctx.packageName }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
        }
        loaded = true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apps that skip the VPN") },
        text = {
          Column {
            // States the real count (and an empty result) instead of showing a blank list; the emulator test reads this line to
            // prove the list still works without the QUERY_ALL_PACKAGES permission.
            Text(
                if (!loaded) "Loading apps…" else if (apps.isEmpty()) "No launchable apps were found." else "${apps.size} apps can be excluded",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            LazyColumn {
                items(apps, key = { it.pkg }) { a ->
                    Row(
                        Modifier.fillMaxWidth().clickable { sel = if (a.pkg in sel) sel - a.pkg else sel + a.pkg },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = a.pkg in sel, onCheckedChange = { sel = if (it) sel + a.pkg else sel - a.pkg })
                        Text(a.label, Modifier.padding(start = 4.dp))
                    }
                }
            }
          }
        },
        confirmButton = { TextButton(onClick = { onDone(sel) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
