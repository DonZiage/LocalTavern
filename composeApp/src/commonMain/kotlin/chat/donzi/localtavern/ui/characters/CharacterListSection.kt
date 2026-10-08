package chat.donzi.localtavern.ui.characters

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.utils.BatchImportResult
import chat.donzi.localtavern.utils.KeepScreenOn
import chat.donzi.localtavern.utils.PickedFile
import chat.donzi.localtavern.utils.rememberCharacterCardPickerLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun CharacterListSection(
    characters: List<Character>,
    modifier: Modifier = Modifier,
    onSelect: (Character) -> Unit,
    onDeleteSelected: (Set<String>) -> Unit,
    onImportFiles: suspend (List<PickedFile>) -> BatchImportResult,
    onExportSelected: (Set<String>) -> Unit,
    onCreateCharacter: (String) -> Unit,
    onEditCharacter: (Character) -> Unit,
    onExportCharacter: (Character) -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    autoShowNewCharacterMenu: Boolean = false,
    onAutoShowMenuConsumed: () -> Unit = {},
    confirmBeforeDelete: Boolean = true
) {
    var query by remember { mutableStateOf("") }
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    var isSearchActive by remember { mutableStateOf(false) }
    var showCharacterMenu by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newCharacterName by remember { mutableStateOf("") }
    var importResult by remember { mutableStateOf<BatchImportResult?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    var characterToDelete by remember { mutableStateOf<Character?>(null) }
    var showMultiDeleteConfirm by remember { mutableStateOf(false) }

    // The picker accepts multiple PNG/JSON cards and ZIP archives; the app
    // expands and imports whatever it contains on its own. Parsing and the
    // database writes run inside onImportFiles with bounded memory (the
    // archive is streamed and each card is persisted as it is parsed), so
    // even a huge library never accumulates in memory on the calling side.
    // A mass import runs for minutes: the screen stays on so the OS power
    // optimizations cannot freeze the app mid-import, and the spinner below
    // shows the work is live. Failures are reported in the result dialog —
    // importing can never crash the app, no matter how many cards are picked.
    KeepScreenOn(isImporting)
    val pickCards = rememberCharacterCardPickerLauncher { pickedFiles ->
        scope.launch {
            isImporting = true
            try {
                val result = onImportFiles(pickedFiles)
                if (result.imported > 0 || result.failed.isNotEmpty() || result.error != null) {
                    importResult = result
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                importResult = BatchImportResult(0, emptyList(), e.message ?: "Import failed.")
            } finally {
                isImporting = false
            }
        }
    }

    LaunchedEffect(autoShowNewCharacterMenu) {
        if (autoShowNewCharacterMenu) {
            showCharacterMenu = true
            onAutoShowMenuConsumed()
        }
    }

    LaunchedEffect(characters) {
        val alive = characters.map { it.id }.toSet()
        selectedIds.removeAll { it !in alive }
        if (selectedIds.isEmpty()) selectionMode = false
    }

    val filtered = remember(characters, query) {
        if (query.isBlank()) characters
        else characters.filter { it.name.contains(query, ignoreCase = true) }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CharacterSearchBar(
                query = query,
                onQueryChange = { query = it },
                isSearchActive = isSearchActive,
                onSearchActiveChange = { isSearchActive = it },
                modifier = Modifier.weight(1f)
            )

            NewCharacterMenu(
                expanded = showCharacterMenu,
                onExpandedChange = { showCharacterMenu = it },
                onImport = {
                    showCharacterMenu = false
                    pickCards()
                },
                onCreate = {
                    showCharacterMenu = false
                    newCharacterName = ""
                    showCreateDialog = true
                }
            )

            actions()
        }

        if (isImporting) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    "Importing characters… keep the app open.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (selectionMode) {
            CharacterSelectionBar(
                selectedCount = selectedIds.size,
                allSelected = filtered.isNotEmpty() && selectedIds.size == filtered.size,
                onToggleSelectAll = {
                    if (selectedIds.size == filtered.size) {
                        selectedIds.clear()
                        selectionMode = false
                    } else {
                        selectedIds.clear()
                        filtered.forEach { selectedIds.add(it.id) }
                    }
                },
                onCancel = {
                    selectedIds.clear()
                    selectionMode = false
                },
                onExport = {
                    onExportSelected(selectedIds.toSet())
                },
                onDelete = {
                    if (confirmBeforeDelete) {
                        showMultiDeleteConfirm = true
                    } else {
                        onDeleteSelected(selectedIds.toSet())
                        selectedIds.clear()
                        selectionMode = false
                    }
                }
            )
        }

        // LazyColumn so search keystrokes and selection toggles only compose
        // the visible rows instead of the whole list on every recomposition.
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(filtered, key = { it.id }) { char ->
                val isSelected = selectedIds.contains(char.id)
                CharacterItem(
                    id = char.id,
                    name = char.name,
                    description = char.personality,
                    avatarData = char.avatarData,
                    selected = isSelected,
                    selectionMode = selectionMode,
                    onClick = {
                        if (selectionMode) {
                            if (isSelected) selectedIds.remove(char.id) else selectedIds.add(char.id)
                            if (selectedIds.isEmpty()) selectionMode = false
                        } else {
                            onSelect(char)
                        }
                    },
                    onLongClick = {
                        if (!selectionMode) selectionMode = true
                        if (!isSelected) selectedIds.add(char.id)
                    },
                    onEditClick = { onEditCharacter(char) },
                    onExportClick = { onExportCharacter(char) },
                    onDeleteClick = {
                        if (confirmBeforeDelete) {
                            characterToDelete = char
                        } else {
                            onDeleteSelected(setOf(char.id))
                        }
                    }
                )
            }
        }
    }

    if (showCreateDialog) {
        CreateCharacterDialog(
            name = newCharacterName,
            onNameChange = { newCharacterName = it },
            onCreate = { name ->
                onCreateCharacter(name)
                newCharacterName = ""
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false; newCharacterName = "" }
        )
    }

    if (characterToDelete != null) {
        DeleteCharacterDialog(
            characterName = characterToDelete!!.name,
            onConfirm = {
                onDeleteSelected(setOf(characterToDelete!!.id))
                characterToDelete = null
            },
            onDismiss = { characterToDelete = null }
        )
    }

    if (showMultiDeleteConfirm) {
        DeleteCharactersDialog(
            count = selectedIds.size,
            onConfirm = {
                onDeleteSelected(selectedIds.toSet())
                selectedIds.clear()
                selectionMode = false
                showMultiDeleteConfirm = false
            },
            onDismiss = { showMultiDeleteConfirm = false }
        )
    }

    importResult?.let { result ->
        ImportResultDialog(
            imported = result.imported,
            failed = result.failed,
            error = result.error,
            onDismiss = { importResult = null }
        )
    }
}

@Composable
private fun NewCharacterMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onImport: () -> Unit,
    onCreate: () -> Unit
) {
    Box {
        Button(
            onClick = { onExpandedChange(true) },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("New")
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            DropdownMenuItem(
                text = { Text("Import") },
                leadingIcon = { Icon(Icons.Default.FileOpen, contentDescription = null) },
                onClick = onImport
            )
            DropdownMenuItem(
                text = { Text("Create") },
                leadingIcon = { Icon(Icons.Default.Create, contentDescription = null) },
                onClick = onCreate
            )
        }
    }
}

@Composable
private fun CharacterSelectionBar(
    selectedCount: Int,
    allSelected: Boolean,
    onToggleSelectAll: () -> Unit,
    onCancel: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    // The side panel is only ~300dp wide: one row cannot hold the count text
    // plus four buttons without squeezing them, which wraps their labels and
    // renders the icon buttons vertically. Two rows keep every button at its
    // natural (horizontal) size.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "$selectedCount selected",
                style = MaterialTheme.typography.labelLarge
            )
            Row {
                TextButton(onClick = onCancel) { Text("Cancel") }
                TextButton(onClick = onToggleSelectAll) {
                    Text(if (allSelected) "Clear all" else "Select all")
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                enabled = selectedCount > 0,
                onClick = onExport
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Export")
            }

            TextButton(
                enabled = selectedCount > 0,
                onClick = onDelete
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Delete")
            }
        }
    }
}
