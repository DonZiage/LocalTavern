package chat.donzi.localtavern.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.domain.Message

// The bar's action partition: exactly four mains, the rest in More. Kept as
// plain data so the layout contract is unit-testable without a Compose
// window (the overflow items are plain text, the mains icon-over-label).
internal val MESSAGE_MAIN_ACTION_LABELS = listOf("Copy", "Branch", "Edit", "More")
internal val MESSAGE_OVERFLOW_ACTION_LABELS = listOf("Add Image", "Delete")

// One of the four main actions: icon on top, name as subtitle below, in a
// big (56 dp+) hitbox. Exactly four of these fill the bar (Copy, Branch,
// Edit, More), so nothing ever goes out of bounds or needs scrolling.
@Composable
private fun OptionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    enabled: Boolean = true
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 56.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp, horizontal = 4.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = tint)
            Spacer(modifier = Modifier.size(2.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
        }
    }
}

// One overflow entry: plain text only (no icon) with a big hitbox.
// [tint] overrides the text colour (Delete keeps its error colour).
@Composable
private fun MoreMenuItem(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tint: Color? = null
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        tint != null -> tint
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            // Merge the label into the clickable so tapping the text
            // hits the same action (needed for semantics/test lookups).
            .semantics(mergeDescendants = true) {}
            .let {
                if (enabled) it.clickable(onClick = onClick) else it
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

// Bottom bar shown in place of the input field while a message's options
// are open. Tapping a message opens this bar; the X closes it and
// restores the input field (and any existing draft).
//
// The bar holds exactly four actions — Copy, Branch, Edit and More —
// each icon-over-subtitle, evenly weighted so they always fit on screen
// without scrolling. More deploys a text-only dropdown ABOVE the bar
// (Add Image, Delete) with large hitboxes.
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
    var showMore by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        if (showMore) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp,
                    shadowElevation = 4.dp,
                    modifier = Modifier.widthIn(min = 180.dp, max = 260.dp)
                ) {
                    Column {
                        MoreMenuItem(
                            label = "Add Image",
                            enabled = true,
                            onClick = {
                                showMore = false
                                onAddImage()
                            }
                        )
                        MoreMenuItem(
                            label = "Delete",
                            enabled = true,
                            tint = MaterialTheme.colorScheme.error,
                            onClick = {
                                showMore = false
                                onDelete()
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.size(4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close options", modifier = Modifier.size(20.dp))
            }
            OptionButton(
                icon = Icons.Default.ContentCopy,
                label = "Copy",
                onClick = onCopy,
                modifier = Modifier.weight(1f)
            )
            OptionButton(
                icon = Icons.Default.AccountTree,
                label = "Branch",
                onClick = onBranch,
                modifier = Modifier.weight(1f)
            )
            OptionButton(
                icon = Icons.Default.Edit,
                label = "Edit",
                onClick = onEdit,
                enabled = canEdit,
                modifier = Modifier.weight(1f)
            )
            OptionButton(
                icon = Icons.Default.MoreHoriz,
                label = "More",
                onClick = { showMore = !showMore },
                modifier = Modifier.weight(1f)
            )
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
