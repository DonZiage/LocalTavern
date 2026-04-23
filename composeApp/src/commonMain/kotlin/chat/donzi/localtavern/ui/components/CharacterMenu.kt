package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.data.models.SillyTavernCardV2
import chat.donzi.localtavern.utils.CharacterManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterMenu(
    onDismissRequest: () -> Unit,
    onImportCharacter: (SillyTavernCardV2, ByteArray?) -> Unit,
    onCreateCharacter: (String) -> Unit,
    onManageApiConnections: () -> Unit,
    onManagePersonas: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var newCharacterName by remember { mutableStateOf("") }

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
                            onDismissRequest()
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

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            ListItem(
                headlineContent = { Text("Create New Character") },
                leadingContent = { Icon(Icons.Default.Create, contentDescription = null) },
                modifier = Modifier.padding(horizontal = 8.dp).clickable {
                    showCreateDialog = true
                }
            )

            ListItem(
                headlineContent = { Text("Import Character from PNG") },
                leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                modifier = Modifier.padding(horizontal = 8.dp).clickable {
                    CharacterManager.openImportDialog()?.let { imported ->
                        onImportCharacter(imported.card, imported.avatarData)
                    }
                    onDismissRequest()
                }
            )
            
            ListItem(
                headlineContent = { Text("Manage Personas") },
                leadingContent = { Icon(Icons.Default.Person, contentDescription = null) },
                modifier = Modifier.padding(horizontal = 8.dp).clickable { 
                    onManagePersonas()
                    onDismissRequest()
                }
            )

            ListItem(
                headlineContent = { Text("API Connections") },
                leadingContent = { Icon(Icons.Default.Settings, contentDescription = null) },
                modifier = Modifier.padding(horizontal = 8.dp).clickable { 
                    onManageApiConnections()
                    onDismissRequest()
                }
            )
        }
    }
}
