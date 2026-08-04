package chat.donzi.localtavern.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.data.database.ApiSettingsRepository
import chat.donzi.localtavern.data.security.ApiKeyCipher
import kotlinx.coroutines.launch

// Security status for API keys at rest. Android/iOS use the OS keystore /
// keychain automatically (always protected). On desktop a passphrase is
// MANDATORY: it is set (and unlocked) through the startup gate, never here.
// This section offers the remaining maintenance flows: changing the
// passphrase, removing protection (explicit opt-out), and the idle auto-lock
// timeout.
@Composable
fun SecuritySettingsSection(
    apiKeyCipher: ApiKeyCipher,
    apiSettingsRepository: ApiSettingsRepository,
    onKeysChanged: () -> Unit,
    autoLockIdleMinutes: Int = 10,
    onAutoLockIdleMinutesChange: (Int) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    // Re-read state after every action; the passphrase key can be set or
    // forgotten at runtime, and the UI must follow.
    val isProtected = apiKeyCipher.isProtected
    val isUnlocked = apiKeyCipher.isAvailable

    var showChangeDialog by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val backend = apiKeyCipher.backendName
    val isPassphrase = backend.contains("passphrase", ignoreCase = true)

    val autoLockOptions = listOf(0, 5, 10, 30, 60)

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
                isProtected -> "API keys are encrypted ($backend). Locked: enter the passphrase at the unlock screen to use them."
                isPassphrase ->
                    "API keys are stored as plaintext. LocalTavern will ask you to set a passphrase on the next launch."
                else -> "API keys are encrypted by the operating system ($backend)."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (isPassphrase) {
            Spacer(modifier = Modifier.height(10.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isProtected && isUnlocked) {
                    OutlinedButton(onClick = { showChangeDialog = true }) {
                        Text("Change Passphrase…")
                    }
                    OutlinedButton(onClick = { showRemoveDialog = true }) {
                        Text("Remove Protection")
                    }
                }
            }
        }

        if (isPassphrase) {
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "Auto-lock after inactivity",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "The passphrase key is forgotten after this much idle time and the app locks. This protects your keys if you step away.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            val currentIndex = autoLockOptions.indexOf(autoLockIdleMinutes)
                .coerceAtLeast(0)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { onAutoLockIdleMinutesChange(autoLockOptions[(currentIndex - 1).coerceAtLeast(0)]) },
                    enabled = currentIndex > 0
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Previous",
                        modifier = Modifier.size(28.dp)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = autoLockLabel(autoLockIdleMinutes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp)
                    )
                }
                IconButton(
                    onClick = { onAutoLockIdleMinutesChange(autoLockOptions[(currentIndex + 1).coerceAtMost(autoLockOptions.size - 1)]) },
                    enabled = currentIndex < autoLockOptions.size - 1
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Next",
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        statusMessage?.let { message ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (message.contains("failed", ignoreCase = true) || message.contains("does not match", ignoreCase = true) || message.contains("Wrong", ignoreCase = true)) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
        }
    }

    if (showChangeDialog) {
        PassphraseDialog(
            title = "Change Passphrase",
            message = "Enter your current passphrase, then choose a new one. All API keys are re-encrypted with the new key; the old passphrase stops working immediately.",
            confirmLabel = "Change",
            requireConfirmation = true,
            requireCurrent = true,
            onDismiss = { showChangeDialog = false },
            onConfirm = { input ->
                val current = input.current ?: ""
                val new = input.value
                scope.launch {
                    if (apiSettingsRepository.changePassphrase(current, new)) {
                        statusMessage = "Passphrase changed."
                    } else {
                        statusMessage = "Wrong current passphrase. Passphrase unchanged."
                    }
                    refresh++
                    showChangeDialog = false
                }
            }
        )
    }

    if (showRemoveDialog) {
        PassphraseDialog(
            title = "Remove Protection",
            message = "Enter the passphrase. All API keys will be stored as plaintext again, and LocalTavern will require a new passphrase on the next launch.",
            confirmLabel = "Remove",
            requireConfirmation = false,
            onDismiss = { showRemoveDialog = false },
            onConfirm = { input ->
                if (apiKeyCipher.unlock(input.value)) {
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

private fun autoLockLabel(minutes: Int): String = when (minutes) {
    0 -> "Off"
    60 -> "60 min"
    else -> "$minutes min"
}
