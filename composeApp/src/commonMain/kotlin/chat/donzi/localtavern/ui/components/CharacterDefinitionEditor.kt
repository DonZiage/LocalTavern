package chat.donzi.localtavern.ui.components

import chat.donzi.localtavern.utils.CharacterManager
import chat.donzi.localtavern.utils.DefaultTokenizer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chat.donzi.localtavern.saveFile
import chat.donzi.localtavern.openDirectory
import chat.donzi.localtavern.data.database.CharacterEntity
import chat.donzi.localtavern.utils.rememberImagePickerLauncher
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import androidx.compose.ui.geometry.Offset

@Composable
fun CharacterDefinitionEditor(
    character: CharacterEntity,
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
    onDelete: () -> Unit
) {
    var name by remember(character.id) { mutableStateOf(character.name) }
    var description by remember(character.id) { mutableStateOf(character.description ?: "") }
    var personality by remember(character.id) { mutableStateOf(character.personality ?: "") }
    var scenario by remember(character.id) { mutableStateOf(character.scenario ?: "") }
    var firstMes by remember(character.id) { mutableStateOf(character.firstMes ?: "") }
    var mesExample by remember(character.id) {
        mutableStateOf(character.mesExample?.split("|||")?.filter { it.isNotBlank() } ?: emptyList())
    }
    var altGreetings by remember(character.id) {
        mutableStateOf(character.altGreetings?.split("|||")?.filter { it.isNotBlank() } ?: emptyList())
    }
    var avatarData by remember(character.id) { mutableStateOf(character.avatarData) }

    var confirmDelete by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showImageMenu by remember { mutableStateOf(false) }
    var showFullImage by remember { mutableStateOf(false) }

    val deleteRed = Color(0xFFD32F2F)

    fun persist() = onSave(name, description, personality, scenario, firstMes, mesExample, altGreetings, avatarData)

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
        val original = avatarData ?: ByteArray(0)
        val exportedBytes = CharacterManager.exportToPng(
            originalImage = original,
            character = character.copy(avatarData = avatarData)
        )

        try {
            val fileName = "${character.name}.png"
            val savedPath = saveFile(fileName, exportedBytes)
            if (savedPath != null) {
                scope.launch {
                    val result = snackbarHostState.showSnackbar(
                        message = "Exported to LocalTavern/ExportedCharacters",
                        actionLabel = "Show",
                        duration = SnackbarDuration.Long
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        openDirectory(savedPath)
                    }
                }
            } else {
                scope.launch {
                    snackbarHostState.showSnackbar("Failed to export: Could not save file")
                }
            }
        } catch (e: Exception) {
            scope.launch {
                snackbarHostState.showSnackbar("Failed to export: ${e.message}")
            }
        }
    }

    val pickImage = rememberImagePickerLauncher { bytes ->
        avatarData = bytes
        persist()
    }

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
                Text(
                    "Edit Character",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )

                OutlinedButton(
                    onClick = { exportCharacter() },
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export", style = MaterialTheme.typography.labelLarge)
                }

                Button(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.height(36.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = deleteRed,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Delete", style = MaterialTheme.typography.labelLarge)
                }

                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = "Total: $totalTokenDisplay",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Box {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape)
                            .clickable { showImageMenu = true }
                    ) {
                        if (avatarData != null) {
                            AsyncImage(
                                model = avatarData,
                                contentDescription = "Character Avatar",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.AddAPhoto,
                                contentDescription = "Add Photo",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(40.dp).align(Alignment.Center)
                            )
                        }
                    }

                    AvatarDropdownMenu(
                        expanded = showImageMenu,
                        onDismissRequest = { showImageMenu = false },
                        hasAvatar = avatarData != null,
                        onAddOrUpdate = { pickImage() },
                        onView = { showFullImage = true },
                        onRemove = {
                            avatarData = null
                            persist()
                        }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("${DefaultTokenizer.countTokens(name)} tokens", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; persist() },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("${DefaultTokenizer.countTokens(description)} tokens", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = description,
                onValueChange = { description = it; persist() },
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4
            )
            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("${DefaultTokenizer.countTokens(personality)} tokens", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = personality,
                onValueChange = { personality = it; persist() },
                label = { Text("Personality") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4
            )
            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("${DefaultTokenizer.countTokens(scenario)} tokens", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = scenario,
                onValueChange = { scenario = it; persist() },
                label = { Text("Scenario") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("${DefaultTokenizer.countTokens(firstMes)} tokens", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = firstMes,
                onValueChange = { firstMes = it; persist() },
                label = { Text("First Message") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
            Spacer(Modifier.height(16.dp))

            MessageExamplesStrip(
                examples = mesExample,
                onChange = { mesExample = it; persist() }
            )

            Spacer(Modifier.height(16.dp))

            AlternateGreetingsStrip(
                greetings = altGreetings,
                onChange = { altGreetings = it; persist() }
            )
            Spacer(Modifier.height(24.dp))
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
        )
    }

    val currentAvatar = avatarData
    if (showFullImage && currentAvatar != null) {
        FullscreenImageViewer(avatarData = currentAvatar, onDismiss = { showFullImage = false })
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete Character?") },
            text = { Text("\"${character.name}\" will be permanently removed.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Delete", color = deleteRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
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
            DropdownMenuItem(
                text = { Text("Add") },
                leadingIcon = { Icon(Icons.Default.AddAPhoto, null) },
                onClick = {
                    onDismissRequest()
                    onAddOrUpdate()
                }
            )
        } else {
            DropdownMenuItem(
                text = { Text("View") },
                leadingIcon = { Icon(Icons.Default.Visibility, null) },
                onClick = {
                    onDismissRequest()
                    onView()
                }
            )
            DropdownMenuItem(
                text = { Text("Update") },
                leadingIcon = { Icon(Icons.Default.Refresh, null) },
                onClick = {
                    onDismissRequest()
                    onAddOrUpdate()
                }
            )
            DropdownMenuItem(
                text = { Text("Remove") },
                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    onDismissRequest()
                    onRemove()
                }
            )
        }
    }
}

@Composable
fun FullscreenImageViewer(
    avatarData: ByteArray,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onDismiss() },
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 3f
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        if (scale > 1f) {
                            offset += pan
                        } else {
                            offset = Offset.Zero
                        }
                    }
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Scroll) {
                                val scrollDelta = event.changes.first().scrollDelta.y
                                val zoomFactor = if (scrollDelta < 0) 1.1f else 0.9f
                                scale = (scale * zoomFactor).coerceIn(1f, 5f)
                                if (scale == 1f) {
                                    offset = Offset.Zero
                                }
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = avatarData,
                contentDescription = "Full Screen Image",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize(0.85f)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
            )

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
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

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "Message Examples",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalMouseWheelScroll(scrollState)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { change, dragAmount ->
                            if (change.type == PointerType.Mouse) {
                                change.consume()
                                scrollState.dispatchRawDelta(-dragAmount)
                            }
                        }
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                examples.forEachIndexed { index, text ->
                    Surface(
                        onClick = { editingIndex = index },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.height(36.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        ) {
                            Text(
                                text = "#${index + 1}  " + text.ifBlank { "(empty)" }.replace('\n', ' '),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.widthIn(max = 220.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }

                Surface(
                    onClick = {
                        val newList = examples + ""
                        onChange(newList)
                        editingIndex = newList.size - 1
                    },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.height(36.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
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
                onDismissRequest = { editingIndex = null },
                title = { Text("Message Example #${idx + 1}") },
                text = {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Text("${DefaultTokenizer.countTokens(text)} tokens", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 5,
                            label = { Text("Example message text") }
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        onChange(examples.toMutableList().also { it[idx] = text })
                        editingIndex = null
                    }) { Text("Save") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            onChange(examples.toMutableList().also { it.removeAt(idx) })
                            editingIndex = null
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Delete")
                        }
                        TextButton(onClick = { editingIndex = null }) { Text("Cancel") }
                    }
                }
            )
        } else {
            editingIndex = null
        }
    }
}