
package chat.donzi.localtavern.ui.components

import chat.donzi.localtavern.utils.CharacterManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.database.CharacterEntity

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
        altGreetings: List<String>
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

    var confirmDelete by remember { mutableStateOf(false) }

    fun persist() = onSave(name, description, personality, scenario, firstMes, systemPrompt, altGreetings)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Edit Character", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onClose) { Text("Close") }
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

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = Color.White
                ),
                onClick = { confirmDelete = true }
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Delete character")
            }

            Spacer(Modifier.width(12.dp))

            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    val original = character.avatarPath
                        ?.let { java.io.File(it).takeIf(java.io.File::exists)?.readBytes() }
                        ?: ByteArray(0)
                    val exportedBytes = CharacterManager.exportToPng(
                        originalImage = original,
                        character = character
                    )
                    val file = java.io.File(
                        System.getProperty("user.home") + "/Desktop/${character.name}.png"
                    )
                    file.writeBytes(exportedBytes)
                }
            ) { Text("Export PNG") }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete character?") },
            text = { Text("\"${character.name}\" will be permanently removed.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}