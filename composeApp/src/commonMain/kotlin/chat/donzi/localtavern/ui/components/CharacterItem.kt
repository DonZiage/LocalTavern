package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CharacterItem(
    id: String,
    name: String,
    description: String,
    avatarData: ByteArray? = null,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onEditClick: (() -> Unit)? = null,
    onExportClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null
) {
    val containerColor =
        if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)

    var showMenu by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(50.dp)) {
                if (avatarData != null) {
                    // Hash once per avatar instance; contentHashCode() is an
                    // O(n) scan that should not run on every recomposition.
                    val avatarCacheKey = remember(id, avatarData) { avatarData.contentHashCode() }
                    val imageRequest = ImageRequest.Builder(LocalPlatformContext.current)
                        .data(avatarData)
                        .memoryCacheKey("char_item_${id}_$avatarCacheKey")
                        .build()

                    AsyncImage(
                        model = imageRequest,
                        contentDescription = name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .matchParentSize()
                            .clip(CircleShape)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color.Gray, CircleShape)
                    )
                }
                if (selectionMode && selected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(20.dp)
                            .background(MaterialTheme.colorScheme.surface, CircleShape)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Text(
                    text = name.ifBlank { "Unnamed" },
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = description,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!selectionMode && (onEditClick != null || onExportClick != null || onDeleteClick != null)) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Character Menu",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        if (onEditClick != null) {
                            DropdownMenuItem(
                                text = { Text("Edit") },
                                leadingIcon = { Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp)) },
                                onClick = {
                                    showMenu = false
                                    onEditClick()
                                }
                            )
                        }
                        if (onExportClick != null) {
                            DropdownMenuItem(
                                text = { Text("Export") },
                                leadingIcon = { Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp)) },
                                onClick = {
                                    showMenu = false
                                    onExportClick()
                                }
                            )
                        }
                        if (onDeleteClick != null) {
                            DropdownMenuItem(
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showMenu = false
                                    onDeleteClick()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}