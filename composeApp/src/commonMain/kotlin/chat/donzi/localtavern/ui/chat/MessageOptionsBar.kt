package chat.donzi.localtavern.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.domain.Message

// Bottom bar shown in place of the input field while a message's options
// are open. Tapping a message opens this bar; the X closes it and
// restores the input field (and any existing draft).
@Composable
fun MessageOptionsBar(
    message: Message,
    canEdit: Boolean,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onBranch: () -> Unit,
    onAddImage: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TextButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "Close options", modifier = Modifier.size(18.dp))
            Text("Close", modifier = Modifier.padding(start = 4.dp))
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TextButton(onClick = onEdit, enabled = canEdit) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Edit", modifier = Modifier.padding(start = 4.dp))
            }
            TextButton(onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Copy", modifier = Modifier.padding(start = 4.dp))
            }
            TextButton(onClick = onBranch) {
                Icon(Icons.Default.AccountTree, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Branch", modifier = Modifier.padding(start = 4.dp))
            }
            TextButton(onClick = onAddImage) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Add Image", modifier = Modifier.padding(start = 4.dp))
            }
            TextButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Text("Delete", modifier = Modifier.padding(start = 4.dp), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// Indicator shown above the input field while a message is being edited
// in the input field. Sending saves the edit; the X (or tapping outside
// the input/buttons) cancels it.
@Composable
fun EditingIndicator(
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "Editing message",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        TextButton(onClick = onCancel) {
            Icon(Icons.Default.Close, contentDescription = "Cancel editing", modifier = Modifier.size(16.dp))
            Text("Cancel", modifier = Modifier.padding(start = 4.dp))
        }
    }
}
