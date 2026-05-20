package chat.donzi.localtavern.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun ChatOptionsMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onRegenerate: () -> Unit,
    canRegenerate: Boolean,
    onEnterSelectMode: () -> Unit,
    canDelete: Boolean
) {
    val coroutineScope = rememberCoroutineScope()

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest
    ) {
        DropdownMenuItem(
            text = { Text("Delete") },
            onClick = {
                onDismissRequest()
                coroutineScope.launch {
                    delay(50.milliseconds)
                    onEnterSelectMode()
                }
            },
            enabled = canDelete,
            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) }
        )
        DropdownMenuItem(
            text = { Text("Regenerate") },
            onClick = {
                onDismissRequest()
                coroutineScope.launch {
                    delay(50.milliseconds)
                    onRegenerate()
                }
            },
            enabled = canRegenerate,
            leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) }
        )
    }
}