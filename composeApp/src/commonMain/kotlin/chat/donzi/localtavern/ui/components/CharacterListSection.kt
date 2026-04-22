package chat.donzi.localtavern.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterListSection(
    characters: List<CharacterEntity>,
    modifier: Modifier = Modifier,
    onSelect: (CharacterEntity) -> Unit,
    onDeleteSelected: (Set<Long>) -> Unit,
    actions: @Composable RowScope.() -> Unit = {}
) {
    var query by remember { mutableStateOf("") }
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }
    var isSearchActive by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

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
                                    // Match the size and ensure no extra internal padding
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
                                // Reduced start padding because the leadingIcon already 
                                // provides spacing, preventing the text from being too far right.
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
                                .padding(start = 4.dp) // Offset to match the TextField's leadingIcon position
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
                            onDeleteSelected(selectedIds.toSet())
                            selectedIds.clear()
                            selectionMode = false
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
                    }
                )
            }
        }
    }
}