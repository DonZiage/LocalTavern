package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Horizontally-scrollable strip of "alternate greeting" pills.
 * Tap a pill to open a dialog where you can edit or delete the greeting.
 * Tap "+" to add a new one.
 */
@Composable
fun AlternateGreetingsStrip(
    greetings: List<String>,
    onChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    val scrollState = rememberScrollState()

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "Alternate Greetings",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    // Handle Mouse Wheel scrolling (Desktop)
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Scroll) {
                                val delta = event.changes.first().scrollDelta
                                    val scrollAmount = (delta.y + delta.x) * 40f
                                    
                                    if (scrollAmount != 0f) {
                                        scrollState.dispatchRawDelta(scrollAmount)
                                        // Consume the event so the parent vertical scroll doesn't fight the horizontal movement
                                        event.changes.forEach { it.consume() }
                                    }
                            }
                        }
                    }
                }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState)
                    .pointerInput(Unit) {
                        // Handle Mouse Drag (Swipe on Desktop)
                        detectHorizontalDragGestures { change, dragAmount ->
                            if (change.type == PointerType.Mouse) {
                                change.consume()
                                scrollState.dispatchRawDelta(-dragAmount)
                            }
                            // Touch is handled automatically by horizontalScroll for native fling
                        }
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                greetings.forEachIndexed { index, text ->
                    GreetingPill(
                        text = text,
                        index = index + 1,
                        onClick = { editingIndex = index }
                    )
                    Spacer(Modifier.width(8.dp))
                }

                // "+" pill
                Surface(
                    onClick = {
                        val newList = greetings + ""
                        onChange(newList)
                        editingIndex = newList.size - 1
                    },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.height(36.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add greeting")
                        Spacer(Modifier.width(4.dp))
                        Text("Add", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        if (greetings.isEmpty()) {
            Text(
                "No alternate greetings yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }

    editingIndex?.let { idx ->
        if (idx in greetings.indices) {
            GreetingEditDialog(
                initial = greetings[idx],
                indexLabel = idx + 1,
                onDismiss = { editingIndex = null },
                onSave = { newValue ->
                    onChange(greetings.toMutableList().also { it[idx] = newValue })
                    editingIndex = null
                },
                onDelete = {
                    onChange(greetings.toMutableList().also { it.removeAt(idx) })
                    editingIndex = null
                }
            )
        } else {
            editingIndex = null
        }
    }
}

@Composable
private fun GreetingPill(text: String, index: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.height(36.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            Text(
                text = "#$index  " + text.ifBlank { "(empty)" }.replace('\n', ' '),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.widthIn(max = 220.dp)
            )
        }
    }
}

@Composable
private fun GreetingEditDialog(
    initial: String,
    indexLabel: Int,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit
) {
    var text by remember(initial) { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Greeting #$indexLabel") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 5,
                label = { Text("Greeting text") }
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Delete")
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
