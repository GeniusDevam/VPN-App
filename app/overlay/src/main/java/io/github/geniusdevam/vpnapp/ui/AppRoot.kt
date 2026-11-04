package io.github.geniusdevam.vpnapp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.geniusdevam.vpnapp.data.Repo

@Composable
fun AppRoot(withVpnPermission: (() -> Unit) -> Unit, askNotifications: () -> Unit) {
    val settings by Repo.settings.collectAsState()
    if (!settings.acknowledged) {
        Onboarding(onContinue = {
            askNotifications()
            Repo.update { it.copy(acknowledged = true) }
        })
        return
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val labels = listOf("Home", "Servers", "Settings")
    val icons = listOf("🏠", "🌐", "⚙️")
    Scaffold(
        bottomBar = {
            NavigationBar {
                labels.forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(icons[i]) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> HomeScreen(withVpnPermission)
                1 -> ServersScreen(withVpnPermission)
                else -> SettingsScreen()
            }
        }
    }
}
