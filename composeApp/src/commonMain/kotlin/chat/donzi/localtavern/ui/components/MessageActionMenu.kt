package chat.donzi.localtavern.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun MessageActionMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onAddImage: () -> Unit,
    onBranch: () -> Unit,
    onGoToParent: (() -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest
    ) {
        if (onGoToParent != null) {
            DropdownMenuItem(
                text = { Text("Go to Parent Chat") },
                onClick = {
                    onDismissRequest()
                    coroutineScope.launch {
                        delay(50.milliseconds)
                        onGoToParent()
                    }
                },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Go back to parent chat session") }
            )
            HorizontalDivider()
        }
        DropdownMenuItem(
            text = { Text("Copy") },
            onClick = {
                onDismissRequest()
                coroutineScope.launch {
                    delay(50.milliseconds)
                    onCopy()
                }
            },
            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) }
        )
        DropdownMenuItem(
            text = { Text("Add Image") },
            onClick = {
                onDismissRequest()
                coroutineScope.launch {
                    delay(50.milliseconds)
                    onAddImage()
                }
            },
            leadingIcon = { Icon(Icons.Default.AddPhotoAlternate, contentDescription = null) }
        )
        DropdownMenuItem(
            text = { Text("Branch") },
            onClick = {
                onDismissRequest()
                coroutineScope.launch {
                    delay(50.milliseconds)
                    onBranch()
                }
            },
            leadingIcon = { Icon(Icons.Default.AccountTree, contentDescription = null) }
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
            onClick = {
                onDismissRequest()
                coroutineScope.launch {
                    delay(50.milliseconds)
                    onDelete()
                }
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            }
        )
    }
}