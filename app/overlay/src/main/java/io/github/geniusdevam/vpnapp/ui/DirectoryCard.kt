package io.github.geniusdevam.vpnapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.geniusdevam.vpnapp.Session
import io.github.geniusdevam.vpnapp.data.Repo

private val reasonText = mapOf(
    "malformed_row" to "malformed rows", "bad_country" to "invalid country code", "bad_address" to "non-public or invalid address",
    "bad_encoding" to "undecodable profile", "oversize" to "oversized", "unsupported_profile" to "unsupported protocol/profile",
    "duplicate_id" to "duplicate name", "duplicate_endpoint" to "duplicate address",
    "policy_identity" to "failed relay-identity check", "policy_address" to "address policy", "policy_weak_crypto" to "weak encryption",
    "policy_unsupported" to "unsupported profile", "policy_invalid" to "invalid profile", "policy_oversize" to "oversized profile",
)

/** What the server directory really contains: counts of usable relays, not the service's advertised total. */
@Composable
fun DirectoryCard(now: Long) {
    val d by Repo.directory.collectAsState()
    val ui by Session.ui.collectAsState()
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Server list", style = MaterialTheme.typography.titleSmall)
            Text(
                "${d.pooledUsable} usable servers in ${d.pooledCountries} countries (from the latest list and recent ones).",
                style = MaterialTheme.typography.bodyMedium,
            )
            val l = d.listing
            if (l.rowsReceived > 0) {
                Text(
                    "Latest download: ${l.rowsReceived} entries, ${l.validProfiles} valid OpenVPN profiles, ${l.usableServers} passed the safety checks.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (l.rejected.isNotEmpty()) {
                    Text(
                        "Rejected: " + l.rejected.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.value} ${reasonText[it.key] ?: it.key}" } + ".",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text(
                d.lastSuccessfulRefreshAt?.let { "Last successful refresh ${duration(now - it)} ago. ${d.newInLastRefresh} new in that refresh." } ?: "Not refreshed yet.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (d.servingStaleCache) Text("Showing saved servers: the latest refresh failed${d.lastError?.let { " ($it)" } ?: ""}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            else d.lastError?.let { Text("Last problem: $it", style = MaterialTheme.typography.bodySmall) }
            Text(
                "VPN Gate publishes only part of its network to each user, and says so on purpose, so this is not every server that exists. " +
                    "Over time, different samples add up. Saved history covers ${d.savedServerRecords} servers.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { Session.refreshDirectory() }, enabled = ui.run == null) { Text("Refresh list") }
        }
    }
}
