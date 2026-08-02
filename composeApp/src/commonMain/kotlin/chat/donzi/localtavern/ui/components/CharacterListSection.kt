package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.data.models.SillyTavernCardV2
import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.utils.CharacterManager
import chat.donzi.localtavern.utils.rememberImagePickerLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CharacterListSection(
    characters: List<Character>,
    modifier: Modifier = Modifier,
    onSelect: (Character) -> Unit,
    onDeleteSelected: (Set<String>) -> Unit,
    onImportCharacter: (SillyTavernCardV2, ByteArray?) -> Unit,
    onCreateCharacter: (String) -> Unit,
    onEditCharacter: (Character) -> Unit,
    onExportCharacter: (Character) -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    autoShowNewCharacterMenu: Boolean = false,
    onAutoShowMenuConsumed: () -> Unit = {}
) {
    var query by remember { mutableStateOf("") }
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    var isSearchActive by remember { mutableStateOf(false) }
    var showCharacterMenu by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newCharacterName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    var characterToDelete by remember { mutableStateOf<Character?>(null) }
    var showMultiDeleteConfirm by remember { mutableStateOf(false) }

    // PNG cards must keep their original bytes so the embedded metadata and
    // avatar survive unchanged; the picker default (downscaled re-encode) is
    // only for chat attachments and avatars.
    val pickImage = rememberImagePickerLauncher({ imagesList ->
        imagesList.firstOrNull()?.let { bytes ->
            scope.launch {
                val imported = withContext(Dispatchers.Default) {
                    CharacterManager.processImport(bytes)
                }
                imported?.let {
                    onImportCharacter(it.card, it.avatarData)
                }
            }
        }
    }, preserveOriginal = true)

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
                    pickImage()
                },
                onCreate = {
                    showCharacterMenu = false
                    newCharacterName = ""
                    showCreateDialog = true
                }
            )

            actions()
        }

        if (selectionMode) {
            CharacterSelectionBar(
                selectedCount = selectedIds.size,
                onCancel = {
                    selectedIds.clear()
                    selectionMode = false
                },
                onDelete = { showMultiDeleteConfirm = true }
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
                    onDeleteClick = { characterToDelete = char }
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
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
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
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            "$selectedCount selected",
            style = MaterialTheme.typography.labelLarge
        )
        Row {
            TextButton(onClick = onCancel) { Text("Cancel") }

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
