package chat.donzi.localtavern.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.domain.PromptBlock
import chat.donzi.localtavern.ui.common.nextSuppressAutoCap
import chat.donzi.localtavern.ui.common.trackTextFieldFocus

@Composable
fun PromptBlockEditDialog(
    block: PromptBlock,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var blockName by remember(block.id, block.name, block.template) { mutableStateOf(block.name) }
    var templateText by remember(block.id, block.name, block.template) { mutableStateOf(block.template) }
    var blockNameSuppressAutoCap by remember(block.id, block.name, block.template) { mutableStateOf(false) }
    var templateSuppressAutoCap by remember(block.id, block.name, block.template) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (block.id.isEmpty()) "Add Custom Block" else "Edit $blockName") },
        text = {
            Column {
                if (block.isCustom || block.id.isEmpty()) {
                    OutlinedTextField(
                        value = blockName,
                        onValueChange = { nextValue ->
                            blockNameSuppressAutoCap = nextSuppressAutoCap(blockName, nextValue, blockNameSuppressAutoCap)
                            blockName = nextValue
                        },
                        label = { Text("Block Name") },
                        modifier = Modifier.fillMaxWidth().trackTextFieldFocus(),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        keyboardOptions = KeyboardOptions(
                            capitalization = if (blockNameSuppressAutoCap) KeyboardCapitalization.None else KeyboardCapitalization.Words
                        )
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
                    onValueChange = { nextValue ->
                        templateSuppressAutoCap = nextSuppressAutoCap(templateText, nextValue, templateSuppressAutoCap)
                        templateText = nextValue
                    },
                    modifier = Modifier.fillMaxWidth().height(140.dp).trackTextFieldFocus(),
                    textStyle = MaterialTheme.typography.bodySmall,
                    keyboardOptions = KeyboardOptions(
                        capitalization = if (templateSuppressAutoCap) KeyboardCapitalization.None else KeyboardCapitalization.Sentences
                    )
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