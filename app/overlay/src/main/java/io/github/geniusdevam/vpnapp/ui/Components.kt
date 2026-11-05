package io.github.geniusdevam.vpnapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.geniusdevam.vpnapp.engine.Countries
import io.github.geniusdevam.vpnapp.engine.CountryOption
import io.github.geniusdevam.vpnapp.engine.Scope
import kotlinx.coroutines.delay

@Composable
fun rememberNow(intervalMs: Long = 250) = produceState(System.currentTimeMillis()) {
    while (true) {
        value = System.currentTimeMillis()
        delay(intervalMs)
    }
}

@Composable
fun CountryChip(scope: Scope, enabled: Boolean, onClick: () -> Unit) {
    AssistChip(onClick = onClick, enabled = enabled, label = { Text(scopeLabel(scope) + "  ▾") })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountryPickerSheet(
    options: List<CountryOption>,
    current: Scope,
    onSelect: (Scope) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val filtered = remember(options, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) options else options.filter { it.name.lowercase().contains(q) || it.iso.lowercase() == q }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp)) {
            Text("Choose a country", style = MaterialTheme.typography.titleLarge)
            Text(
                "Only countries with servers available right now are listed.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text("Search") }, modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(Modifier.weightedFill()) {
                item {
                    PickerRow("🌐  Any country (sample of all countries)", "${options.sumOf { it.serverCount }} servers", current == Scope.Any) {
                        onSelect(Scope.Any)
                    }
                    HorizontalDivider()
                }
                items(filtered, key = { it.iso }) { c ->
                    PickerRow(
                        "${Countries.flag(c.iso)}  ${c.name}", "${c.serverCount} server${if (c.serverCount == 1) "" else "s"}",
                        current == Scope.Country(c.iso),
                    ) { onSelect(Scope.Country(c.iso)) }
                }
                if (filtered.isEmpty()) item { Text("No matching country.", Modifier.padding(16.dp)) }
            }
        }
    }
}

private fun Modifier.weightedFill(): Modifier = this.fillMaxWidth()

@Composable
private fun PickerRow(title: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title + if (selected) "  ✓" else "", style = MaterialTheme.typography.bodyLarge)
        Text(sub, style = MaterialTheme.typography.bodySmall)
    }
}
