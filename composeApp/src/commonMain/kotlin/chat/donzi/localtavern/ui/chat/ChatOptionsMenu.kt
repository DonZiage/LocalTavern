package chat.donzi.localtavern.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable

@Composable
fun ChatOptionsMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onRegenerate: () -> Unit,
    canRegenerate: Boolean,
    onEnterSelectMode: () -> Unit,
    canDelete: Boolean,
    onManageChats: () -> Unit,
    canManageChats: Boolean,
    onGoToParent: (() -> Unit)? = null,
    onAttachImage: (() -> Unit)? = null,
    // Export Chat lives in the session options (Saved Chats) now; these
    // parameters are kept so existing callers keep compiling.
    onExportChat: (() -> Unit)? = null,
    canExportChat: Boolean = false
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest
    ) {
        if (onGoToParent != null) {
            DropdownMenuItem(
                text = { Text("Go to Parent Chat") },
                onClick = {
                    onDismissRequest()
                    onGoToParent()
                },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Go to Parent Chat") }
            )
            HorizontalDivider()
        }

        DropdownMenuItem(
            text = { Text("Chats") },
            onClick = {
                onDismissRequest()
                onManageChats()
            },
            enabled = canManageChats,
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Chats") }
        )

        if (onAttachImage != null) {
            DropdownMenuItem(
                text = { Text("Attach Image") },
                onClick = {
                    onDismissRequest()
                    onAttachImage()
                },
                leadingIcon = { Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Attach Image") }
            )
        }

        DropdownMenuItem(
            text = { Text("Delete Messages") },
            onClick = {
                onDismissRequest()
                onEnterSelectMode()
            },
            enabled = canDelete,
            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = "Delete Messages") }
        )

        DropdownMenuItem(
            text = { Text("Regenerate") },
            onClick = {
                onDismissRequest()
                onRegenerate()
            },
            enabled = canRegenerate,
            leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = "Regenerate") }
        )
    }
}
