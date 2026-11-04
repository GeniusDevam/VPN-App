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
            Choice("Strong only", "Only servers that use modern authenticated encryption (AES-GCM / ChaCha20). Right now every VPN Gate server uses AES-128-CBC, so this setting finds no servers. Leave it off unless you want to refuse all of them.", st.encryption == EncryptionMode.STRONG) {
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
            Text("On mobile data, Best Connect automatically uses much less data (about 60 MB per run).", style = MaterialTheme.typography.bodySmall)
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
            OutlinedButton(onClick = { Repo.clearHistory() }, modifier = Modifier.fillMaxWidth()) { Text("Clear learned speed history") }
        }
        item {
            OutlinedButton(onClick = { Repo.update { it.copy(acknowledged = false) } }, modifier = Modifier.fillMaxWidth()) { Text("Show privacy notice again") }
        }
        item { Section("Kill switch") }
        item {
            Text(
                "To block all internet if the VPN drops, turn on “Always-on VPN” and “Block connections without VPN” for this app in Android's VPN settings.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { ctx.startActivity(Intent(AndroidSettings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open Android VPN settings")
            }
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
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apps that skip the VPN") },
        text = {
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
        },
        confirmButton = { TextButton(onClick = { onDone(sel) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
