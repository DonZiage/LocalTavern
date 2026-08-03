package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import chat.donzi.localtavern.domain.Persona

@Composable
fun PersonaManagementSection(
    personas: List<Persona>,
    activePersonaId: String?,
    onSelect: (String) -> Unit,
    onAdd: (String, String?, ByteArray?) -> Unit,
    onUpdate: (String, String, String?, ByteArray?) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
    autoEditDefaultPersona: Boolean = false,
    onAutoEditConsumed: () -> Unit = {},
    confirmBeforeDelete: Boolean = true
) {
    var personaToDelete by remember { mutableStateOf<Persona?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        PersonaManagement(
            personas = personas,
            activePersonaId = activePersonaId,
            onPersonaSelect = onSelect,
            onPersonaAdd = onAdd,
            onPersonaUpdate = onUpdate,
            onPersonaDelete = { personaId ->
                if (confirmBeforeDelete) {
                    personaToDelete = personas.find { it.id == personaId }
                } else {
                    onDelete(personaId)
                }
            },
            autoEditDefaultPersona = autoEditDefaultPersona,
            onAutoEditConsumed = onAutoEditConsumed
        )
    }

    personaToDelete?.let { persona ->
        AlertDialog(
            onDismissRequest = { personaToDelete = null },
            title = { Text("Delete Persona?") },
            text = {
                Text(
                    text = "\"${persona.name}\" and all chats bound to it will be deleted. This cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        personaToDelete = null
                        onDelete(persona.id)
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { personaToDelete = null }) { Text("Cancel") }
            }
        )
    }
}
