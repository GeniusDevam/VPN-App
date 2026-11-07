package io.github.geniusdevam.vpnapp.ui

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.geniusdevam.vpnapp.engine.Countries
import io.github.geniusdevam.vpnapp.engine.CountryOption
import io.github.geniusdevam.vpnapp.engine.DirectoryState
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
fun CountryChip(scope: Scope, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AssistChip(onClick = onClick, enabled = enabled, label = { Text(scopeLabel(scope) + "  ▾") }, modifier = modifier)
}

/**
 * The country picker. A dialog, not a bottom sheet: Material 3's ModalBottomSheet crashed on Android 6.0 the moment it opened
 * ("The offset was read before being initialized", compat run 39), while dialogs work on every supported version, on TV and
 * on tablets. The list scrolls inside the dialog, so it also works at 200% font on a 320 dp phone.
 *
 * The search field appears only after "Search countries" is tapped. Before Android 9 a new window gives focus to its first
 * focusable control even in touch mode; when that was the text field, the keyboard opened over half the list every time the
 * picker opened (compat run 41, Android 6.0 and 7.0). A button taking that focus opens nothing.
 */
@Composable
fun CountryPickerSheet(
    options: List<CountryOption>,
    current: Scope,
    directory: DirectoryState,
    directoryError: String?,
    onSelect: (Scope) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val filtered = remember(options, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) options else options.filter { it.name.lowercase().contains(q) || it.iso.lowercase() == q }
    }
    // A country chosen earlier may have no server in the current list. Show it (selected) instead of hiding the user's own choice.
    val missingCurrent = (current as? Scope.Country)?.takeIf { c -> options.none { it.iso == c.iso } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a country") },
        text = {
          Column {
            Text(
                when (directory) {
                    DirectoryState.LOADING -> "Loading the server list…"
                    DirectoryState.UNAVAILABLE -> "The server list could not be loaded${directoryError?.let { " ($it)" } ?: ""}, so no country is known to have servers. This does not mean there are none."
                    DirectoryState.EMPTY -> "The latest server list has no usable servers."
                    DirectoryState.STALE -> "Showing saved servers: the latest refresh failed${directoryError?.let { " ($it)" } ?: ""}. Counts may be out of date."
                    DirectoryState.FRESH -> "Only countries with servers in the current list are shown."
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (searching) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("Search") }, modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                )
                LaunchedEffect(Unit) { searchFocus.requestFocus() }
            } else if (options.size > 1) {
                TextButton(onClick = { searching = true }) { Text("\uD83D\uDD0D  Search countries") }
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                item {
                    PickerRow(
                        "\uD83C\uDF10  Any country (sample of all countries)",
                        if (options.isEmpty()) "no servers loaded" else "${options.sumOf { it.serverCount }} servers",
                        current == Scope.Any,
                    ) { onSelect(Scope.Any) }
                    HorizontalDivider()
                }
                if (missingCurrent != null && query.isBlank()) item {
                    PickerRow("${Countries.flag(missingCurrent.iso)}  ${Countries.name(missingCurrent.iso)}", "none in the current list", true) { onSelect(missingCurrent) }
                }
                items(filtered, key = { it.iso }) { c ->
                    PickerRow(
                        "${Countries.flag(c.iso)}  ${c.name}", "${c.serverCount} server${if (c.serverCount == 1) "" else "s"}",
                        current == Scope.Country(c.iso),
                    ) { onSelect(Scope.Country(c.iso)) }
                }
                if (filtered.isEmpty() && options.isNotEmpty()) item { Text("No matching country.", Modifier.padding(16.dp)) }
            }
          }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PickerRow(title: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .semantics(mergeDescendants = true) {}
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The title takes whatever width is left and wraps; the count never wraps. Without the weight, a long title at a large
        // font on a narrow phone squeezes the count into a one-character column and pushes the other rows off the sheet.
        Text(title + if (selected) "  \u2713" else "", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, softWrap = false)
    }
}
