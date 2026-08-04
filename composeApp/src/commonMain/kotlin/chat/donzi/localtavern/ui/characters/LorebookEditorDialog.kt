package chat.donzi.localtavern.ui.characters

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.domain.Lorebook
import chat.donzi.localtavern.domain.LorebookEntry
import chat.donzi.localtavern.saveFile
import chat.donzi.localtavern.utils.DefaultTokenizer
import chat.donzi.localtavern.utils.LorebookParser
import chat.donzi.localtavern.utils.rememberLorebookPickerLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

// Edits the SillyTavern-compatible characterBook (World Info / lorebook) of a
// character. Entries with keys are triggered by matching chat content; constant
// entries are always injected into the prompt. Lorebooks can be imported from
// and exported to standalone world-info JSON files.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LorebookEditorDialog(
    characterBook: JsonObject?,
    onSave: (JsonObject?) -> Unit,
    onDismiss: () -> Unit,
    fileNameHint: String? = null
) {
    var book by remember(characterBook) { mutableStateOf(LorebookParser.parse(characterBook)) }
    var editingEntry by remember { mutableStateOf<LorebookEntry?>(null) }
    var isNewEntry by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val pickLorebook = rememberLorebookPickerLauncher { pickedFiles ->
        val file = pickedFiles.firstOrNull() ?: return@rememberLorebookPickerLauncher
        val parsed = LorebookParser.parseWorldInfo(file.bytes.decodeToString())
        if (parsed != null) {
            book = parsed
            importError = null
        } else {
            importError = "Could not read \"${file.name}\" as a lorebook file."
        }
    }

    val exportLorebook: () -> Unit = {
        val baseName = (book.name ?: fileNameHint ?: "lorebook")
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()
            .ifBlank { "lorebook" }
            .take(80)
        val fileName = "$baseName.json"
        scope.launch {
            val bytes = withContext(Dispatchers.Default) {
                LorebookParser.worldInfoToJsonString(book).encodeToByteArray()
            }
            exportMessage = if (saveFile(fileName, bytes) != null) {
                "Exported as $fileName"
            } else {
                "Export failed: could not save the file"
            }
        }
    }

    val saveAndClose = {
        // No entries left: clear the character book so nothing is injected.
        onSave(if (book.entries.isNotEmpty()) LorebookParser.toJson(book) else null)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lorebook (World Info)") },
        text = {
            Column {
                Text(
                    text = "Entries are injected into the prompt when their keys appear in the chat. Use {{lorebook}} in a prompt block to place them, or they are appended automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(book.entries, key = { it.hashCode() }) { entry ->
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.name.ifBlank { entry.keys.firstOrNull()?.takeIf { it.isNotBlank() } ?: "Unnamed entry" },
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = buildString {
                                            if (entry.keys.isNotEmpty()) append("Keys: ${entry.keys.take(3).joinToString(", ")}${if (entry.keys.size > 3) "…" else ""}")
                                            if (entry.constant) {
                                                if (isNotEmpty()) append(" • ")
                                                append("Always on")
                                            }
                                            append(" • ${DefaultTokenizer.countTokens(entry.content)} tokens")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                                IconButton(onClick = {
                                    editingEntry = entry
                                    isNewEntry = false
                                }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit entry", modifier = Modifier.size(16.dp))
                                }
                                IconButton(onClick = {
                                    book = book.copy(entries = book.entries - entry)
                                }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete entry", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        editingEntry = LorebookEntry(name = "", keys = emptyList(), content = "")
                        isNewEntry = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Add Entry")
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = pickLorebook,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FileOpen, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Import")
                    }
                    OutlinedButton(
                        onClick = exportLorebook,
                        enabled = book.entries.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Export")
                    }
                }
                importError?.let {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                exportMessage?.let {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { saveAndClose() }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )

    val editing = editingEntry
    if (editing != null) {
        LorebookEntryEditDialog(
            entry = editing,
            title = if (isNewEntry) "New Entry" else "Edit Entry",
            onDismiss = { editingEntry = null },
            onSave = { updated ->
                book = if (isNewEntry) {
                    book.copy(entries = book.entries + updated)
                } else {
                    book.copy(entries = book.entries.map { if (it == editing) updated else it })
                }
                editingEntry = null
            }
        )
    }
}

@Composable
private fun LorebookEntryEditDialog(
    entry: LorebookEntry,
    title: String,
    onDismiss: () -> Unit,
    onSave: (LorebookEntry) -> Unit
) {
    var name by remember(entry) { mutableStateOf(entry.name) }
    var keysText by remember(entry) { mutableStateOf(entry.keys.joinToString(", ")) }
    var content by remember(entry) { mutableStateOf(entry.content) }
    var constant by remember(entry) { mutableStateOf(entry.constant) }
    var selective by remember(entry) { mutableStateOf(entry.selective) }
    var caseSensitive by remember(entry) { mutableStateOf(entry.caseSensitive) }
    var secondaryKeysText by remember(entry) { mutableStateOf(entry.secondaryKeys.joinToString(", ")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = keysText,
                    onValueChange = { keysText = it },
                    label = { Text("Trigger keys (comma separated)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Content") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = constant, onCheckedChange = { constant = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Always on (constant)", style = MaterialTheme.typography.bodyMedium)
                }
                if (constant) {
                    Text(
                        text = "Constant entries are always injected without key matching.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = selective, onCheckedChange = { selective = it })
                        Spacer(Modifier.width(8.dp))
                        Text("Selective (require secondary keys)", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (selective) {
                        OutlinedTextField(
                            value = secondaryKeysText,
                            onValueChange = { secondaryKeysText = it },
                            label = { Text("Secondary keys (comma separated)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = caseSensitive, onCheckedChange = { caseSensitive = it })
                        Spacer(Modifier.width(8.dp))
                        Text("Case sensitive matching", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsedKeys = keysText.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    val parsedSecondary = secondaryKeysText.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    if (content.isNotBlank() && (constant || parsedKeys.isNotEmpty())) {
                        onSave(
                            entry.copy(
                                name = name.trim(),
                                keys = if (constant) entry.keys else parsedKeys,
                                secondaryKeys = parsedSecondary,
                                content = content,
                                constant = constant,
                                selective = selective,
                                caseSensitive = caseSensitive
                            )
                        )
                    } else {
                        onDismiss()
                    }
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
