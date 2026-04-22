
package chat.donzi.localtavern.ui.components

import chat.donzi.localtavern.utils.CharacterManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chat.donzi.localtavern.database.CharacterEntity
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
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
        systemPrompt: String,
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
    var systemPrompt by remember(character.id) { mutableStateOf(character.systemPrompt ?: "") }
    var altGreetings by remember(character.id) {
        mutableStateOf(
            character.altGreetings
                ?.split("|||")
                ?: emptyList()
        )
    }
    var avatarData by remember(character.id) { mutableStateOf(character.avatarData) }

    var confirmDelete by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    
    var showImageMenu by remember { mutableStateOf(false) }
    var showFullImage by remember { mutableStateOf(false) }

    // Consistent Red Color for Delete Button (Material 700 Red)
    val deleteRed = Color(0xFFD32F2F)

    fun persist() = onSave(name, description, personality, scenario, firstMes, systemPrompt, altGreetings, avatarData)

    val exportCharacter = {
        val original = avatarData ?: ByteArray(0)
        val exportedBytes = CharacterManager.exportToPng(
            originalImage = original,
            character = character.copy(avatarData = avatarData)
        )
        val osName = System.getProperty("os.name") ?: ""
        val baseFolder = if (osName.contains("Android", ignoreCase = true)) {
            "/storage/emulated/0/Download"
        } else {
            System.getProperty("user.home") + "/Documents"
        }
        
        val file = File(baseFolder, "${character.name}.png")
        file.writeBytes(exportedBytes)

        scope.launch {
            snackbarHostState.showSnackbar(
                message = "Exported ${file.name} to ${file.parent}",
                duration = SnackbarDuration.Long
            )
        }
    }

    val pickImage = {
        val dialog = FileDialog(Frame(), "Select Character Image", FileDialog.LOAD)
        dialog.setFilenameFilter { _, filename -> 
            val ext = filename.lowercase()
            ext.endsWith(".png") || ext.endsWith(".jpg") || ext.endsWith(".jpeg") || ext.endsWith(".webp")
        }
        dialog.isVisible = true
        if (dialog.file != null) {
            val file = File(dialog.directory, dialog.file)
            avatarData = file.readBytes()
            persist()
        }
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
                
                // Export Button
                OutlinedButton(
                    onClick = { exportCharacter() },
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export", style = MaterialTheme.typography.labelLarge)
                }

                // Delete Button
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

                // Close Button
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(36.dp)
                ) { 
                    Icon(Icons.Default.Close, contentDescription = "Close") 
                }
            }

            Spacer(Modifier.height(16.dp))

            // Character Picture Section
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
            Spacer(Modifier.height(12.dp))

            AlternateGreetingsStrip(
                greetings = altGreetings,
                onChange = { altGreetings = it; persist() }
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it; persist() },
                label = { Text("System Prompt") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
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
                        .fillMaxSize(0.7f) // 15% margin on each side (total 30% reduction)
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
                                    // The limit is reached when the zoomed image edge hits the 0.7f box boundaries
                                    // imageSize is already 70% of screen. Zoomed size is imageSize * 3.5
                                    // The pan limit relative to the box center is:
                                    val maxX = (imageSize.width * zoomFactor - imageSize.width) / 2f
                                    val maxY = (imageSize.height * zoomFactor - imageSize.height) / 2f
                                    
                                    val distX = (abs(panOffset.x) / maxX).coerceIn(0f, 1f)
                                    val distY = (abs(panOffset.y) / maxY).coerceIn(0f, 1f)
                                    
                                    // Speed drops to 0 at the border (dist=1) and is 4.0 at the center (dist=0)
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