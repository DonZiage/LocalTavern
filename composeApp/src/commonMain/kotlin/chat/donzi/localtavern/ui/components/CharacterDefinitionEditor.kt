package chat.donzi.localtavern.ui.components

import chat.donzi.localtavern.utils.DefaultTokenizer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.utils.rememberImagePickerLauncher
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun CharacterDefinitionEditor(
    character: Character,
    onClose: () -> Unit,
    onSave: (
        name: String,
        description: String,
        personality: String,
        scenario: String,
        firstMes: String,
        mesExample: List<String>,
        altGreetings: List<String>,
        avatarData: ByteArray?
    ) -> Unit,
    onDelete: () -> Unit,
    onExport: (Character) -> Unit,
    onLorebookSave: (kotlinx.serialization.json.JsonObject?) -> Unit = {}
) {
    var name by remember(character.id) { mutableStateOf(character.name) }
    var description by remember(character.id) { mutableStateOf(character.description ?: "") }
    var personality by remember(character.id) { mutableStateOf(character.personality) }
    var scenario by remember(character.id) { mutableStateOf(character.scenario) }
    var firstMes by remember(character.id) { mutableStateOf(character.firstMes ?: "") }
    var mesExample by remember(character.id) {
        mutableStateOf(character.mesExample.filter { it.isNotBlank() })
    }
    var altGreetings by remember(character.id) {
        mutableStateOf(character.altGreetings.filter { it.isNotBlank() })
    }
    var avatarData by remember(character.id) { mutableStateOf(character.avatarData) }

    var confirmDelete by remember { mutableStateOf(false) }

    var showImageMenu by remember { mutableStateOf(false) }
    var showFullImage by remember { mutableStateOf(false) }
    var showLorebookEditor by remember { mutableStateOf(false) }

    fun persist() = onSave(
        name, description, personality, scenario, firstMes,
        mesExample.filter { it.isNotBlank() },
        altGreetings.filter { it.isNotBlank() },
        avatarData
    )

    var isFirstLoad by remember(character.id) { mutableStateOf(true) }
    LaunchedEffect(name, description, personality, scenario, firstMes, mesExample, altGreetings, avatarData) {
        if (isFirstLoad) {
            isFirstLoad = false
            return@LaunchedEffect
        }
        delay(600.milliseconds)
        persist()
    }

    // The 600 ms debounced autosave above is cancelled when the editor leaves
    // composition; flush the latest edits on dispose so closing the editor
    // through any path (scrim, window, navigation) never loses the last chunk.
    DisposableEffect(Unit) {
        onDispose {
            persist()
        }
    }

    val baseTokens = remember(name, description, personality, scenario, firstMes, mesExample) {
        DefaultTokenizer.countTokens(name) +
                DefaultTokenizer.countTokens(description) +
                DefaultTokenizer.countTokens(personality) +
                DefaultTokenizer.countTokens(scenario) +
                DefaultTokenizer.countTokens(firstMes) +
                mesExample.sumOf { DefaultTokenizer.countTokens(it) }
    }

    val altGreetingTokens = remember(altGreetings) {
        altGreetings.map { DefaultTokenizer.countTokens(it) }
    }

    val totalTokenDisplay = remember(baseTokens, altGreetingTokens) {
        if (altGreetingTokens.isEmpty()) {
            "$baseTokens Tokens"
        } else {
            val minAlt = altGreetingTokens.minOrNull() ?: 0
            val maxAlt = altGreetingTokens.maxOrNull() ?: 0
            if (minAlt == maxAlt) {
                "${baseTokens + minAlt} Tokens"
            } else {
                "${baseTokens + minAlt}-${baseTokens + maxAlt} Tokens"
            }
        }
    }

    val exportCharacter = {
        val currentCharacter = character.copy(
            name = name, description = description, personality = personality, scenario = scenario, firstMes = firstMes,
            mesExample = mesExample.filter { it.isNotBlank() },
            altGreetings = altGreetings.filter { it.isNotBlank() }, avatarData = avatarData
        )
        onExport(currentCharacter)
    }

    val pickImage = rememberImagePickerLauncher(
        onImagesPicked = { imagesList ->
            avatarData = imagesList.firstOrNull()
        }
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Edit Character", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))

                OutlinedButton(
                    onClick = { exportCharacter() },
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export", style = MaterialTheme.typography.labelLarge)
                }

                OutlinedButton(
                    onClick = { showLorebookEditor = true },
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Lorebook", style = MaterialTheme.typography.labelLarge)
                }

                Button(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.height(36.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Delete", style = MaterialTheme.typography.labelLarge)
                }

                IconButton(onClick = { onClose() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(text = "Total: $totalTokenDisplay", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))

            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Box {
                    Box(
                        modifier = Modifier
                            .size(120.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape)
                            .clickable { showImageMenu = true }
                    ) {
                        if (avatarData != null) {
                            // contentHashCode() is an O(n) scan over a
                            // potentially multi-MB avatar; it must not run on
                            // every recomposition (every keystroke recomposes
                            // the whole editor).
                            val platformContext = LocalPlatformContext.current
                            val avatarCacheKey = remember(avatarData) {
                                "char_edit_${character.id}_${avatarData.contentHashCode()}"
                            }
                            val imageRequest = remember(avatarCacheKey) {
                                ImageRequest.Builder(platformContext)
                                    .data(avatarData)
                                    .memoryCacheKey(avatarCacheKey)
                                    .build()
                            }

                            AsyncImage(model = imageRequest, contentDescription = "Character Avatar", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            Icon(imageVector = Icons.Default.AddAPhoto, contentDescription = "Add Photo", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp).align(Alignment.Center))
                        }
                    }
                    AvatarDropdownMenu(
                        expanded = showImageMenu,
                        onDismissRequest = { showImageMenu = false },
                        hasAvatar = avatarData != null,
                        onAddOrUpdate = { pickImage() },
                        onView = { showFullImage = true },
                        onRemove = { avatarData = null }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("${DefaultTokenizer.countTokens(name)} tokens", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("${DefaultTokenizer.countTokens(description)} tokens", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), minLines = 4)
            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("${DefaultTokenizer.countTokens(personality)} tokens", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(value = personality, onValueChange = { personality = it }, label = { Text("Personality") }, modifier = Modifier.fillMaxWidth(), minLines = 4)
            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("${DefaultTokenizer.countTokens(scenario)} tokens", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(value = scenario, onValueChange = { scenario = it }, label = { Text("Scenario") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("${DefaultTokenizer.countTokens(firstMes)} tokens", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(value = firstMes, onValueChange = { firstMes = it }, label = { Text("First Message") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            Spacer(Modifier.height(16.dp))

            MessageExamplesStrip(examples = mesExample, onChange = { mesExample = it })
            Spacer(Modifier.height(16.dp))
            AlternateGreetingsStrip(greetings = altGreetings, onChange = { altGreetings = it })
            Spacer(Modifier.height(24.dp))
        }
    }

    val currentAvatar = avatarData
    if (showFullImage && currentAvatar != null) {
        FullscreenImageViewer(avatarData = currentAvatar, onDismiss = { showFullImage = false })
    }

    if (showLorebookEditor) {
        LorebookEditorDialog(
            characterBook = character.characterBook,
            onSave = { bookJson -> onLorebookSave(bookJson) },
            onDismiss = { showLorebookEditor = false }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete Character?") },
            text = { Text("\"${character.name}\" will be permanently removed.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun AvatarDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    hasAvatar: Boolean,
    onAddOrUpdate: () -> Unit,
    onView: () -> Unit,
    onRemove: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest) {
        if (!hasAvatar) {
            DropdownMenuItem(text = { Text("Add") }, leadingIcon = { Icon(Icons.Default.AddAPhoto, null) }, onClick = { onDismissRequest(); onAddOrUpdate() })
        } else {
            DropdownMenuItem(text = { Text("View") }, leadingIcon = { Icon(Icons.Default.Visibility, null) }, onClick = { onDismissRequest(); onView() })
            DropdownMenuItem(text = { Text("Update") }, leadingIcon = { Icon(Icons.Default.Refresh, null) }, onClick = { onDismissRequest(); onAddOrUpdate() })
            DropdownMenuItem(text = { Text("Remove") }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { onDismissRequest(); onRemove() })
        }
    }
}

@Composable
fun MessageExamplesStrip(
    examples: List<String>,
    onChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var editingIndex by remember { mutableStateOf<Int?>(null) }

    // Reset a stale edit target after the list shrinks (deleted entries).
    LaunchedEffect(editingIndex, examples.size) {
        val idx = editingIndex
        if (idx != null && idx !in examples.indices) {
            editingIndex = null
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text("Message Examples", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 6.dp))

        Box(modifier = Modifier.fillMaxWidth().horizontalMouseWheelScroll(scrollState)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth().horizontalScroll(scrollState)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { change, dragAmount ->
                            if (change.type == PointerType.Mouse) {
                                change.consume()
                                scrollState.dispatchRawDelta(-dragAmount)
                            }
                        }
                    }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                examples.forEachIndexed { index, text ->
                    Surface(onClick = { editingIndex = index }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.height(36.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
                            Text(text = "#${index + 1}  " + text.ifBlank { "(empty)" }.replace('\n', ' '), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(max = 220.dp))
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Surface(onClick = { val newList = examples + ""; onChange(newList); editingIndex = newList.size - 1 }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.height(36.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
                        Icon(Icons.Default.Add, contentDescription = "Add example")
                        Spacer(Modifier.width(4.dp))
                        Text("Add", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }

    editingIndex?.let { idx ->
        if (idx in examples.indices) {
            var text by remember(examples[idx]) { mutableStateOf(examples[idx]) }
            AlertDialog(
                onDismissRequest = {
                    // Cancelling a freshly added empty entry must not leave a ghost pill.
                    if (idx in examples.indices && examples[idx].isBlank()) {
                        onChange(examples.toMutableList().also { it.removeAt(idx) })
                    }
                    editingIndex = null
                },
                title = { Text("Message Example #${idx + 1}") },
                text = {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Text("${DefaultTokenizer.countTokens(text)} tokens", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 5, label = { Text("Example message text") })
                    }
                },
                confirmButton = { TextButton(onClick = { onChange(examples.toMutableList().also { it[idx] = text }); editingIndex = null }) { Text("Save") } },
                dismissButton = {
                    Row {
                        TextButton(onClick = { onChange(examples.toMutableList().also { it.removeAt(idx) }); editingIndex = null }) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Delete")
                        }
                        TextButton(onClick = {
                            // Cancelling a freshly added empty entry must not leave a ghost pill.
                            if (idx in examples.indices && examples[idx].isBlank()) {
                                onChange(examples.toMutableList().also { it.removeAt(idx) })
                            }
                            editingIndex = null
                        }) { Text("Cancel") }
                    }
                }
            )
        }
    }
}