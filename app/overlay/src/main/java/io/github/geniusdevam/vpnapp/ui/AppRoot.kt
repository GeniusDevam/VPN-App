package io.github.geniusdevam.vpnapp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import io.github.geniusdevam.vpnapp.data.Repo

/** Widest the content may grow: cards and text stay readable on tablets, Chromebooks and TVs instead of stretching edge to edge. */
val MaxContentWidth = 840.dp

/**
 * Compact width (phones, split screen, folded foldables): bottom navigation bar.
 * Medium/expanded width (>= 600 dp: tablets, unfolded foldables, Chromebook windows, TVs): navigation rail on the side, content
 * centred and capped at [MaxContentWidth]. The choice follows the current window, so resizing, folding or rotating switches it live,
 * and the selected tab survives the recreation (rememberSaveable).
 */
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
    val wide = LocalConfiguration.current.screenWidthDp >= 600

    val content: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = MaxContentWidth)) {
            when (tab) {
                0 -> HomeScreen(withVpnPermission)
                1 -> ServersScreen(withVpnPermission)
                else -> SettingsScreen()
            }
        }
    }

    if (wide) {
        Scaffold { pad ->
            Row(Modifier.padding(pad)) {
                NavigationRail(Modifier.fillMaxHeight()) {
                    labels.forEachIndexed { i, label ->
                        NavigationRailItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Text(icons[i]) },
                            label = { Text(label) },
                        )
                    }
                }
                content()
            }
        }
    } else {
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
        ) { pad -> Box(Modifier.padding(pad)) { content() } }
    }
}
