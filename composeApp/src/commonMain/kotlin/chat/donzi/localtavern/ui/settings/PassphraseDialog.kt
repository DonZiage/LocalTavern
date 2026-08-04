package chat.donzi.localtavern.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

// Values collected by PassphraseDialog. `current` is non-null only when the
// dialog was opened with requireCurrent (change-passphrase flow); `value` is
// the new passphrase (or the plain unlock/remove passphrase when the dialog
// has a single field).
data class PassphraseDialogInput(val current: String?, val value: String)

@Composable
fun PassphraseDialog(
    title: String,
    message: String,
    confirmLabel: String,
    requireConfirmation: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (PassphraseDialogInput) -> Unit,
    requireCurrent: Boolean = false
) {
    var current by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val mismatch = requireConfirmation && confirmation.isNotEmpty() && confirmation != passphrase
    val canConfirm = passphrase.length >= 6 &&
        (!requireConfirmation || (confirmation == passphrase)) &&
        (!requireCurrent || current.isNotBlank())
    val submit = { onConfirm(PassphraseDialogInput(current.ifBlank { null }, passphrase)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.widthIn(max = 280.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (requireCurrent) {
                    OutlinedTextField(
                        value = current,
                        onValueChange = { current = it },
                        label = { Text("Current passphrase") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (canConfirm) submit() }),
                        modifier = Modifier
                            .widthIn(max = 260.dp).fillMaxWidth()
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown &&
                                    (event.key == Key.Enter || event.key == Key.NumPadEnter)
                                ) {
                                    if (canConfirm) submit()
                                    true
                                } else {
                                    false
                                }
                            }
                    )
                }
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(if (requireCurrent) "New passphrase" else "Passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (canConfirm) submit() }),
                    modifier = Modifier
                        .widthIn(max = 260.dp).fillMaxWidth()
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown &&
                                (event.key == Key.Enter || event.key == Key.NumPadEnter)
                            ) {
                                if (canConfirm) submit()
                                true
                            } else {
                                false
                            }
                        }
                )
                if (requireConfirmation) {
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        label = { Text("Confirm passphrase") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        isError = mismatch,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (canConfirm) submit() }),
                        modifier = Modifier
                            .widthIn(max = 260.dp).fillMaxWidth()
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown &&
                                    (event.key == Key.Enter || event.key == Key.NumPadEnter)
                                ) {
                                    if (canConfirm) submit()
                                    true
                                } else {
                                    false
                                }
                            }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (canConfirm) submit() },
                enabled = canConfirm
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
