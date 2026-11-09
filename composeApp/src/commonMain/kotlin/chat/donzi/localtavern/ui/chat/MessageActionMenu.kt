package chat.donzi.localtavern.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

@Composable
fun MessageActionMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onAddImage: () -> Unit,
    onBranch: () -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(expanded) {
        if (expanded) {
            keyboardController?.hide()
            focusManager.clearFocus()
        }
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest
    ) {
        DropdownMenuItem(
            text = { Text("Copy") },
            onClick = {
                onDismissRequest()
                onCopy()
            },
            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = "Copy") }
        )
        DropdownMenuItem(
            text = { Text("Add Image") },
            onClick = {
                onDismissRequest()
                onAddImage()
            },
            leadingIcon = { Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Add Image") }
        )
        DropdownMenuItem(
            text = { Text("Branch") },
            onClick = {
                onDismissRequest()
                onBranch()
            },
            leadingIcon = { Icon(Icons.Default.AccountTree, contentDescription = "Branch") }
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
            onClick = {
                onDismissRequest()
                onDelete()
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        )
    }
}
