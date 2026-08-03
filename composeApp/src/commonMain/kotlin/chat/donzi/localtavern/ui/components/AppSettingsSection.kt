package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.appDatabasePath
import chat.donzi.localtavern.appVersionName
import chat.donzi.localtavern.data.sync.DeviceName

// General app preferences (all per-device, persisted in AppSettings) plus an
// informational About block.
@Composable
fun AppSettingsSection(
    isDarkMode: Boolean,
    onToggleDarkMode: (Boolean, Offset) -> Unit,
    autoSyncOnLaunch: Boolean,
    onAutoSyncOnLaunchChange: (Boolean) -> Unit,
    sendWithCtrlEnter: Boolean,
    onSendWithCtrlEnterChange: (Boolean) -> Unit,
    confirmBeforeDelete: Boolean,
    onConfirmBeforeDeleteChange: (Boolean) -> Unit,
    deviceName: String,
    onRenameDevice: (String) -> Unit
) {
    var showRenameDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Spacer(modifier = Modifier.height(8.dp))

        GroupLabel("Appearance")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Dark mode", style = MaterialTheme.typography.bodyLarge)
            }
            ThemeToggle(
                isDarkMode = isDarkMode,
                onToggleDarkMode = onToggleDarkMode
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        GroupLabel("Chat")
        SettingSwitchRow(
            label = "Send with Ctrl+Enter",
            description = "When off, Enter sends the message and Shift+Enter makes a new line. When on, Enter makes a new line and Ctrl+Enter sends.",
            checked = sendWithCtrlEnter,
            onCheckedChange = onSendWithCtrlEnterChange
        )

        Spacer(modifier = Modifier.height(12.dp))
        GroupLabel("Sync")
        SettingSwitchRow(
            label = "Sync on app launch",
            description = "Automatically exchange changes with paired devices when the app starts.",
            checked = autoSyncOnLaunch,
            onCheckedChange = onAutoSyncOnLaunchChange
        )

        Spacer(modifier = Modifier.height(12.dp))
        GroupLabel("Data")
        SettingSwitchRow(
            label = "Confirm before deleting",
            description = "Ask for confirmation before deleting characters and personas.",
            checked = confirmBeforeDelete,
            onCheckedChange = onConfirmBeforeDeleteChange
        )

        Spacer(modifier = Modifier.height(12.dp))
        GroupLabel("About")
        Text(
            text = "Version",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = appVersionName(),
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    text = "Device name",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Shown to other devices while pairing and in the paired-device list. Changing it never affects pairing or sync.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            TextButton(onClick = { showRenameDialog = true }) {
                Text("Rename")
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Storage",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = appDatabasePath().ifBlank { "Unknown" },
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(8.dp))
    }

    if (showRenameDialog) {
        RenameDeviceDialog(
            currentName = deviceName,
            onRename = { newName ->
                onRenameDevice(newName)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false }
        )
    }
}

@Composable
private fun RenameDeviceDialog(
    currentName: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "This name is only for your reference — pairing and synchronization are bound to the device key, not the name.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(DeviceName.MAX_LENGTH) },
                    label = { Text("Device name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onRename(name.trim()) },
                enabled = name.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
private fun SettingSwitchRow(
    label: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
