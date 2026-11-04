package io.github.geniusdevam.vpnapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun Onboarding(onContinue: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("🛡️", style = MaterialTheme.typography.displayMedium)
            Text("Welcome to VPN App", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Quick Connect finds a fast server in seconds. Best Connect takes its time and speed-tests " +
                    "servers to find the very fastest. In both, you can choose the country.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Before you start: how private is this?", style = MaterialTheme.typography.titleMedium)
                    Text("✅ Your connection to the server is encrypted. Your Wi-Fi owner, mobile carrier and anyone on the same network cannot read your traffic.")
                    Text("✅ Everything this app stores on your phone is encrypted too.")
                    Text(
                        "⚠️ The servers are free and run by volunteers through the VPN Gate project (University of Tsukuba, Japan). " +
                            "The volunteer running the server can see which websites and addresses you connect to. " +
                            "VPN Gate says it keeps connection logs for at least 3 months.",
                    )
                    Text(
                        "⚠️ Websites that don't use HTTPS can be read by that volunteer. " +
                            "Don't log in to banking or other important accounts through a free server.",
                    )
                    Text(
                        "This is great for getting around local blocks and for public Wi-Fi safety. " +
                            "It is not a tool for anonymity.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("I understand, continue") }
        }
    }
}
