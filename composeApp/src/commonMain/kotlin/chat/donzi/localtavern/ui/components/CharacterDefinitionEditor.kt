package chat.donzi.localtavern.ui.components

import chat.donzi.localtavern.utils.CharacterManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
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
import kotlin.math.abs

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

            Spacer(Modifier.height(16.dp))

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

                    DropdownMenu(
                        expanded = showImageMenu,
                        onDismissRequest = { showImageMenu = false }
                    ) {
                        if (avatarData == null) {
                            DropdownMenuItem(
                                text = { Text("Add Photo") },
                                leadingIcon = { Icon(Icons.Default.AddAPhoto, null) },
                                onClick = {
                                    showImageMenu = false
                                    pickImage()
                                }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("View Photo") },
                                leadingIcon = { Icon(Icons.Default.Visibility, null) },
                                onClick = {
                                    showImageMenu = false
                                    showFullImage = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Update Photo") },
                                leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                onClick = {
                                    showImageMenu = false
                                    pickImage()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Remove Photo") },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showImageMenu = false
                                    avatarData = null
                                    persist()
                                }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it; persist() },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = description,
                onValueChange = { description = it; persist() },
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = personality,
                onValueChange = { personality = it; persist() },
                label = { Text("Personality") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = scenario,
                onValueChange = { scenario = it; persist() },
                label = { Text("Scenario") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
            Spacer(Modifier.height(12.dp))

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

    if (showFullImage && avatarData != null) {
        Dialog(
            onDismissRequest = { showFullImage = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            var isZoomed by remember { mutableStateOf(false) }
            var panOffset by remember { mutableStateOf(Offset.Zero) }
            var imageSize by remember { mutableStateOf(IntSize.Zero) }
            var transformOrigin by remember { mutableStateOf(TransformOrigin.Center) }

            val scale by animateFloatAsState(if (isZoomed) 3.5f else 1f)
            val displayOffset by animateOffsetAsState(if (isZoomed) panOffset else Offset.Zero)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable { showFullImage = false },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = avatarData,
                    contentDescription = "Full Image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize(0.7f)
                        .onGloballyPositioned { imageSize = it.size }
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = displayOffset.x,
                            translationY = displayOffset.y,
                            transformOrigin = transformOrigin
                        )
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { showFullImage = false })
                        }
                        .pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    val pivotX = if (imageSize.width > 0) offset.x / imageSize.width else 0.5f
                                    val pivotY = if (imageSize.height > 0) offset.y / imageSize.height else 0.5f
                                    transformOrigin = TransformOrigin(pivotX, pivotY)
                                    isZoomed = true
                                },
                                onDragEnd = {
                                    isZoomed = false
                                    panOffset = Offset.Zero
                                },
                                onDragCancel = {
                                    isZoomed = false
                                    panOffset = Offset.Zero
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val zoomFactor = 3.5f
                                    val maxX = (imageSize.width * zoomFactor - imageSize.width) / 2f
                                    val maxY = (imageSize.height * zoomFactor - imageSize.height) / 2f
                                    val distX = (abs(panOffset.x) / maxX).coerceIn(0f, 1f)
                                    val distY = (abs(panOffset.y) / maxY).coerceIn(0f, 1f)
                                    val speedMultiplierX = 4f * (1f - distX)
                                    val speedMultiplierY = 4f * (1f - distY)
                                    val newX = (panOffset.x - dragAmount.x * speedMultiplierX).coerceIn(-maxX, maxX)
                                    val newY = (panOffset.y - dragAmount.y * speedMultiplierY).coerceIn(-maxY, maxY)
                                    panOffset = Offset(newX, newY)
                                }
                            )
                        }
                )
            }
        }
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
                .horizontalMouseWheelScroll(scrollState) // Applied shared utility modifier here
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

        if (examples.isEmpty()) {
            Text(
                "No message examples yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }

    editingIndex?.let { idx ->
        if (idx in examples.indices) {
            var text by remember(examples[idx]) { mutableStateOf(examples[idx]) }
            AlertDialog(
                onDismissRequest = { editingIndex = null },
                title = { Text("Message Example #${idx + 1}") },
                text = {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 5,
                        label = { Text("Example message text") }
                    )
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