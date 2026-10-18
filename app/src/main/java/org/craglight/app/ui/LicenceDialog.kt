package org.craglight.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

private data class LicenceEntry(val title: String, val file: String, val sourceUrl: String)
private data class LicenceLoad(val loading: Boolean = true, val text: String = "", val error: Boolean = false)

/** All licence text is bundled in assets; opening this dialog makes no network request. */
@Composable internal fun LicenceDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<LicenceEntry?>(null) }
    val entries by produceState<List<LicenceEntry>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val json = context.assets.open("licenses/index.json").bufferedReader().use { JSONArray(it.readText()) }
                (0 until json.length()).map { index ->
                    val entry = json.getJSONObject(index)
                    val file = entry.getString("file")
                    require(file.matches(Regex("[a-z0-9][a-z0-9.-]*\\.txt")))
                    LicenceEntry(entry.getString("title"), file, entry.optString("sourceUrl"))
                }
            }.getOrDefault(emptyList())
        }
    }
    AlertDialog(
        onDismissRequest = { if (selected == null) onDismiss() else selected = null },
        modifier = Modifier.testTag("licence-dialog"),
        title = { Text(selected?.title ?: "Licences and credits") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 500.dp)) {
                val current = selected
                if (current == null) {
                    Text("Read all notices offline. Photo credits also appear beside each photograph.", Modifier.padding(bottom = 12.dp))
                    when {
                        entries == null -> CircularProgressIndicator()
                        entries.isNullOrEmpty() -> Text("Bundled notices could not be opened.")
                        else -> LazyColumn(Modifier.testTag("licence-list")) {
                            items(entries.orEmpty(), key = { it.file }) { entry ->
                                TextButton(onClick = { selected = entry }, modifier = Modifier.fillMaxWidth()) {
                                    Text(entry.title, Modifier.fillMaxWidth())
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                } else key(current.file) {
                    val loaded by produceState(LicenceLoad(), current.file) {
                        value = withContext(Dispatchers.IO) {
                            runCatching {
                                LicenceLoad(loading = false, text = context.assets.open("licenses/${current.file}")
                                    .bufferedReader().use { it.readText() })
                            }.getOrElse { LicenceLoad(loading = false, error = true) }
                        }
                    }
                    when {
                        loaded.loading -> CircularProgressIndicator()
                        loaded.error -> Text("This bundled notice could not be opened.")
                        else -> {
                            // Short blocks keep long native-library notices scrollable without one giant text layout.
                            val blocks = remember(loaded.text) { loaded.text.lineSequence().chunked(18).map { it.joinToString("\n") }.toList() }
                            LazyColumn(Modifier.fillMaxSize().testTag("licence-text"), state = rememberLazyListState(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (current.sourceUrl.isNotBlank()) item {
                                    SelectionContainer { Text("Source: ${current.sourceUrl}", style = MaterialTheme.typography.bodySmall) }
                                }
                                items(blocks) { block ->
                                    SelectionContainer { Text(block, style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { if (selected != null) TextButton(onClick = { selected = null }) { Text("All notices") } },
    )
}
