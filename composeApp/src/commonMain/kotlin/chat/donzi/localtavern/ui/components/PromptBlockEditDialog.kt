package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.utils.PromptBlock

@Composable
fun PromptBlockEditDialog(
    block: PromptBlock,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var blockName by remember { mutableStateOf(block.name) }
    var templateText by remember { mutableStateOf(block.template) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (block.id.isEmpty()) "Add Custom Block" else "Edit ${block.name}") },
        text = {
            Column {
                if (block.isCustom || block.id.isEmpty()) {
                    OutlinedTextField(
                        value = blockName,
                        onValueChange = { blockName = it },
                        label = { Text("Block Name") },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                Text(
                    text = "Modify how this layer segment handles text formatting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = templateText,
                    onValueChange = { templateText = it },
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    textStyle = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (blockName.isNotBlank()) onSave(blockName, templateText) },
                enabled = blockName.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                // Renders destructive button solely on user-instantiated blocks
                if (block.isCustom && onDelete != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete")
                    }
                }
            }
        }
    )
}