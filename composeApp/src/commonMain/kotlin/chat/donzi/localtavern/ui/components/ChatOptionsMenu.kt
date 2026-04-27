package chat.donzi.localtavern.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ChatOptionsMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onRegenerate: () -> Unit,
    canRegenerate: Boolean
) {
    val coroutineScope = rememberCoroutineScope()

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest
    ) {
        DropdownMenuItem(
            text = { Text("Regenerate") },
            onClick = {
                onDismissRequest()
                coroutineScope.launch {
                    delay(50) // Small delay to allow menu to dismiss visually
                    onRegenerate()
                }
            },
            enabled = canRegenerate,
            leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) }
        )
        // Future chat options can be added here
    }
}
