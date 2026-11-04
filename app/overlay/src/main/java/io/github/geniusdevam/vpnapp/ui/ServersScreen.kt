package io.github.geniusdevam.vpnapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import io.github.geniusdevam.vpnapp.Session
import io.github.geniusdevam.vpnapp.data.Repo
import io.github.geniusdevam.vpnapp.engine.Countries
import io.github.geniusdevam.vpnapp.engine.Server

@Composable
fun ServersScreen(withVpnPermission: (() -> Unit) -> Unit) {
    val servers by Repo.servers.collectAsState()
    val settings by Repo.settings.collectAsState()
    val ui by Session.ui.collectAsState()
    var query by remember { mutableStateOf("") }
    var favoritesOnly by remember { mutableStateOf(false) }

    val visible = remember(servers, query, favoritesOnly, settings.favorites) {
        val q = query.trim().lowercase()
        servers.filter { s ->
            (!favoritesOnly || s.id in settings.favorites) &&
                (q.isEmpty() || s.id.lowercase().contains(q) || s.countryName.lowercase().contains(q) || s.countryCode.lowercase() == q)
        }.sortedWith(compareBy({ Countries.name(it.countryCode, fallback = it.countryName) }, { -(it.reportedSpeedBps ?: 0L) }))
    }
    val grouped = remember(visible) { visible.groupBy { it.countryCode } }

    Column(Modifier.padding(horizontal = 16.dp)) {
        Text("Servers", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Search country or server") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = favoritesOnly, onClick = { favoritesOnly = !favoritesOnly }, label = { Text("★ Favorites") })
            Text("${visible.size} of ${servers.size}", style = MaterialTheme.typography.bodySmall)
        }
        if (servers.isEmpty()) {
            Text(
                "No servers yet. Tap Quick Connect or Best Connect on the Home tab and the list will load.",
                modifier = Modifier.padding(top = 24.dp),
            )
        }
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            grouped.forEach { (iso, list) ->
                item(key = "h-$iso") {
                    Text(
                        "${Countries.flag(iso)}  ${Countries.name(iso, fallback = list.first().countryName)}  (${list.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                    )
                }
                items(list, key = { it.id }) { s ->
                    ServerRow(s, s.id in settings.favorites, Repo.reputation.isBlocked(s.id), ui.run == null, withVpnPermission)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ServerRow(
    s: Server,
    favorite: Boolean,
    blocked: Boolean,
    canConnect: Boolean,
    withVpnPermission: (() -> Unit) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = canConnect && !blocked) { withVpnPermission { Session.connectTo(s) } }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text((if (favorite) "★ " else "") + s.id + if (blocked) "  (avoided)" else "", style = MaterialTheme.typography.bodyLarge)
            Text(serverDetail(s), style = MaterialTheme.typography.bodySmall)
        }
        Box {
            TextButton(onClick = { menu = true }) { Text("⋮") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(if (favorite) "Remove favorite" else "Add to favorites") },
                    onClick = {
                        menu = false
                        Repo.update { st -> st.copy(favorites = if (favorite) st.favorites - s.id else st.favorites + s.id) }
                    },
                )
                if (blocked) {
                    DropdownMenuItem(text = { Text("Stop avoiding this server") }, onClick = { menu = false; Repo.reputation.unblock(s.id); Repo.saveLearning() })
                } else {
                    DropdownMenuItem(text = { Text("Report & avoid this server") }, onClick = { menu = false; Repo.reputation.blockLong(s.id); Repo.saveLearning() })
                }
            }
        }
    }
}
