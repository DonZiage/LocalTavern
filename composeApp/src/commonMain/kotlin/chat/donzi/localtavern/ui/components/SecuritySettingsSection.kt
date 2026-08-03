package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.data.database.ApiSettingsRepository
import chat.donzi.localtavern.data.security.ApiKeyCipher
import kotlinx.coroutines.launch

// Security status for API keys at rest. Android/iOS use the OS keystore /
// keychain automatically (always protected); the desktop build supports an
// opt-in passphrase with unlock/protect/remove flows.
@Composable
fun SecuritySettingsSection(
    apiKeyCipher: ApiKeyCipher,
    apiSettingsRepository: ApiSettingsRepository,
    onKeysChanged: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    // Re-read state after every action; the passphrase key can be set or
    // forgotten at runtime, and the UI must follow.
    val isProtected = apiKeyCipher.isProtected
    val isUnlocked = apiKeyCipher.isAvailable

    var showProtectDialog by remember { mutableStateOf(false) }
    var showUnlockDialog by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val backend = apiKeyCipher.backendName

    fun runAction(block: suspend () -> Unit, success: String) {
        scope.launch {
            try {
                block()
                statusMessage = success
            } catch (e: Exception) {
                statusMessage = e.message ?: "Operation failed."
            }
            refresh++
            onKeysChanged()
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = when {
                isProtected && isUnlocked -> "API keys are encrypted ($backend)."
                isProtected -> "API keys are encrypted ($backend). Locked: enter the passphrase to use them."
                backend.contains("passphrase", ignoreCase = true) ->
                    "API keys are stored as plaintext. Protect them with a passphrase."
                else -> "API keys are encrypted by the operating system ($backend)."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                isProtected && isUnlocked -> {
                    if (backend.contains("passphrase", ignoreCase = true)) {
                        OutlinedButton(onClick = { showRemoveDialog = true }) {
                            Text("Remove Protection")
                        }
                    }
                }
                isProtected -> {
                    if (backend.contains("passphrase", ignoreCase = true)) {
                        OutlinedButton(onClick = { showUnlockDialog = true }) {
                            Text("Unlock")
                        }
                    }
                }
                else -> {
                    if (backend.contains("passphrase", ignoreCase = true)) {
                        OutlinedButton(onClick = { showProtectDialog = true }) {
                            Text("Protect with Passphrase…")
                        }
                    }
                }
            }
        }

        statusMessage?.let { message ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (message.contains("failed", ignoreCase = true) || message.contains("does not match", ignoreCase = true)) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
        }
    }

    if (showProtectDialog) {
        PassphraseDialog(
            title = "Protect API Keys",
            message = "Keys will be encrypted with a passphrase-derived key. Remember the passphrase — without it the keys cannot be recovered.",
            confirmLabel = "Protect",
            requireConfirmation = true,
            onDismiss = { showProtectDialog = false },
            onConfirm = { passphrase ->
                runAction(
                    block = {
                        apiKeyCipher.protect(passphrase)
                        apiSettingsRepository.reencryptAllApiKeys()
                    },
                    success = "API keys are now encrypted."
                )
                showProtectDialog = false
            }
        )
    }

    if (showUnlockDialog) {
        PassphraseDialog(
            title = "Unlock API Keys",
            message = "Enter the passphrase to decrypt the stored API keys.",
            confirmLabel = "Unlock",
            requireConfirmation = false,
            onDismiss = { showUnlockDialog = false },
            onConfirm = { passphrase ->
                if (apiKeyCipher.unlock(passphrase)) {
                    runAction(
                        block = { apiSettingsRepository.reencryptAllApiKeys() },
                        success = "API keys unlocked."
                    )
                } else {
                    statusMessage = "Wrong passphrase."
                }
                refresh++
                showUnlockDialog = false
            }
        )
    }

    if (showRemoveDialog) {
        PassphraseDialog(
            title = "Remove Protection",
            message = "Enter the passphrase. All API keys will be stored as plaintext again.",
            confirmLabel = "Remove",
            requireConfirmation = false,
            onDismiss = { showRemoveDialog = false },
            onConfirm = { passphrase ->
                if (apiKeyCipher.unlock(passphrase)) {
                    runAction(
                        block = {
                            apiSettingsRepository.decryptAllApiKeysToPlaintext()
                            apiKeyCipher.removeProtection()
                        },
                        success = "Protection removed."
                    )
                } else {
                    statusMessage = "Wrong passphrase."
                }
                refresh++
                showRemoveDialog = false
            }
        )
    }
}
