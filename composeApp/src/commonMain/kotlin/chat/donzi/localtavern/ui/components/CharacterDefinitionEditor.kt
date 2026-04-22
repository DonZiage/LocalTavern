
package chat.donzi.localtavern.ui.components

import chat.donzi.localtavern.utils.CharacterManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.database.CharacterEntity
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

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
        avatarPath: String?
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
    var avatarPath by remember(character.id) { mutableStateOf(character.avatarPath) }

    var confirmDelete by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    
    var showImageMenu by remember { mutableStateOf(false) }

    // Consistent Red Color for Delete Button (Material 700 Red)
    val deleteRed = Color(0xFFD32F2F)

    fun persist() = onSave(name, description, personality, scenario, firstMes, systemPrompt, altGreetings, avatarPath)

    val exportCharacter = {
        val original = character.avatarPath
            ?.let { File(it).takeIf(File::exists)?.readBytes() }
            ?: ByteArray(0)
        val exportedBytes = CharacterManager.exportToPng(
            originalImage = original,
            character = character
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
            val copiedPath = CharacterManager.copyAvatar(file, name)
            if (copiedPath != null) {
                avatarPath = copiedPath
                persist()
            }
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
                        if (avatarPath != null) {
                            AsyncImage(
                                model = avatarPath,
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
                        if (avatarPath == null) {
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
                                    avatarPath = null
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