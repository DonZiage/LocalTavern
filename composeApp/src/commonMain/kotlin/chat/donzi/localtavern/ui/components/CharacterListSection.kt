package chat.donzi.localtavern.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.data.database.CharacterEntity
import chat.donzi.localtavern.data.models.SillyTavernCardV2
import chat.donzi.localtavern.utils.CharacterManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterListSection(
    characters: List<CharacterEntity>,
    modifier: Modifier = Modifier,
    onSelect: (CharacterEntity) -> Unit,
    onDeleteSelected: (Set<Long>) -> Unit,
    onImportCharacter: (SillyTavernCardV2, ByteArray?) -> Unit,
    onCreateCharacter: (String) -> Unit,
    onEditCharacter: (CharacterEntity) -> Unit,
    actions: @Composable RowScope.() -> Unit = {}
) {
    var query by remember { mutableStateOf("") }
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }
    var isSearchActive by remember { mutableStateOf(false) }
    val focusRequester = focusRequester()
    var showCharacterMenu by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newCharacterName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    var characterToDelete by remember { mutableStateOf<CharacterEntity?>(null) }
    var showMultiDeleteConfirm by remember { mutableStateOf(false) }

    // Drop ids that no longer exist (e.g. after deletion).
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

        // ---- Search box and Actions ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val interactionSource = remember { MutableInteractionSource() }

            AnimatedContent(
                targetState = isSearchActive,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    (fadeIn() + expandHorizontally(expandFrom = Alignment.Start))
                        .togetherWith(fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start))
                },
                label = "SearchTransition"
            ) { active ->
                if (active) {
                    // Expanded Search Bar
                    LaunchedEffect(Unit) {
                        focusRequester.requestFocus()
                    }

                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .focusRequester(focusRequester),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        singleLine = true,
                        interactionSource = interactionSource,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { innerTextField ->
                            TextFieldDefaults.DecorationBox(
                                value = query,
                                innerTextField = innerTextField,
                                enabled = true,
                                singleLine = true,
                                visualTransformation = VisualTransformation.None,
                                interactionSource = interactionSource,
                                leadingIcon = {
                                    IconButton(
                                        onClick = { isSearchActive = false },
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Search,
                                            null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                },
                                trailingIcon = {
                                    if (query.isNotEmpty()) {
                                        IconButton(onClick = { query = "" }, modifier = Modifier.size(24.dp)) {
                                            Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                },
                                container = {
                                OutlinedTextFieldDefaults.ContainerBox(
                                        enabled = true,
                                    isError = false,
                                    interactionSource = interactionSource,
                                    colors = OutlinedTextFieldDefaults.colors(),
                                    shape = CircleShape
                                )
                                },
                                contentPadding = PaddingValues(start = 0.dp, end = 12.dp, top = 0.dp, bottom = 0.dp),
                            )
                        }
                    )
                } else {
                    // Collapsed Icon
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        IconButton(
                            onClick = { isSearchActive = true },
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(40.dp)
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "Open Search",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            Box {
                Button(
                    onClick = { showCharacterMenu = true },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("New")
                }

                DropdownMenu(
                    expanded = showCharacterMenu,
                    onDismissRequest = { showCharacterMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Import") },
                        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                        onClick = {
                            showCharacterMenu = false
                            scope.launch {
                                val imported = withContext(Dispatchers.Default) {
                                    CharacterManager.openImportDialog()
                                }
                                imported?.let {
                                    onImportCharacter(it.card, it.avatarData)
                                }
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Create") },
                        leadingIcon = { Icon(Icons.Default.Create, contentDescription = null) },
                        onClick = {
                            showCharacterMenu = false
                            showCreateDialog = true
                        }
                    )
                }
            }

            actions()
        }

        // ---- Selection action bar (only shown in selection mode) ----
        if (selectionMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${selectedIds.size} selected",
                    style = MaterialTheme.typography.labelLarge
                )
                Row {
                    TextButton(onClick = {
                        selectedIds.clear()
                        selectionMode = false
                    }) { Text("Cancel") }

                    TextButton(
                        enabled = selectedIds.isNotEmpty(),
                        onClick = {
                            showMultiDeleteConfirm = true
                        }
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Delete")
                    }
                }
            }
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(filtered, key = { it.id }) { char ->
                val isSelected = selectedIds.contains(char.id)
                CharacterItem(
                    name = char.name,
                    description = char.personality ?: "No personality set.",
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
                    onDeleteClick = { characterToDelete = char }
                )
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create New Character") },
            text = {
                OutlinedTextField(
                    value = newCharacterName,
                    onValueChange = { newCharacterName = it },
                    label = { Text("Character Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newCharacterName.isNotBlank()) {
                            onCreateCharacter(newCharacterName)
                            newCharacterName = ""
                            showCreateDialog = false
                        }
                    },
                    enabled = newCharacterName.isNotBlank()
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (characterToDelete != null) {
        AlertDialog(
            onDismissRequest = { characterToDelete = null },
            title = { Text("Delete Character?") },
            text = { Text("\"${characterToDelete?.name}\" will be permanently removed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteSelected(setOf(characterToDelete!!.id))
                        characterToDelete = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { characterToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showMultiDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showMultiDeleteConfirm = false },
            title = { Text("Delete Characters?") },
            text = { Text("Are you sure you want to delete ${selectedIds.size} characters?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteSelected(selectedIds.toSet())
                        selectedIds.clear()
                        selectionMode = false
                        showMultiDeleteConfirm = false
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showMultiDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun focusRequester(): FocusRequester = remember { FocusRequester() }
