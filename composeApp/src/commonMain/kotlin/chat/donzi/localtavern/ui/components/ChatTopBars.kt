package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.domain.Character

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTopBar(
    activeCharacter: Character?,
    isDesktop: Boolean,
    onEditCharacter: () -> Unit,
    onCloseChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCharacters: () -> Unit
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(activeCharacter?.name ?: "LocalTavern")
                if (activeCharacter != null) {
                    IconButton(onClick = onEditCharacter) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
                }
            }
        },
        navigationIcon = {
            if (!isDesktop) {
                IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Menu, contentDescription = "Settings") }
            }
        },
        actions = {
            if (activeCharacter != null) { IconButton(onClick = onCloseChat) { Icon(Icons.Filled.Close, contentDescription = "Close Chat") } }
            if (!isDesktop) {
                IconButton(onClick = onOpenCharacters) { Icon(Icons.Filled.Person, contentDescription = "Characters") }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageSelectTopBar(
    selectedMessageIds: Set<String>,
    onCancel: () -> Unit,
    onDeleteSelected: () -> Unit
) {
    TopAppBar(
        title = { Text(if (selectedMessageIds.isEmpty()) "Select Messages" else "${selectedMessageIds.size} Selected") },
        actions = {
            OutlinedButton(onClick = onCancel) {
                Icon(Icons.Default.Clear, contentDescription = null)
                Text("Cancel")
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = onDeleteSelected,
                enabled = selectedMessageIds.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onError)
                Text("Delete", color = MaterialTheme.colorScheme.onError)
            }
        }
    )
}
