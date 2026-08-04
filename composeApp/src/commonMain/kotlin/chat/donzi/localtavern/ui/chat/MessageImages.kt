package chat.donzi.localtavern.ui.chat
import chat.donzi.localtavern.ui.common.FullscreenImageViewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

@Composable
fun MessageImages(
    images: List<ByteArray>,
    // References whose bytes have not arrived yet (pending blob sync). When
    // non-zero and no bytes are hydrated, a placeholder box is shown so the
    // message does not look bare while the transfer is in flight.
    pendingCount: Int = 0,
    isEditing: Boolean = false,
    topPadding: Dp = 8.dp,
    onRemoveImage: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (images.isEmpty() && pendingCount <= 0) return

    if (images.isEmpty() && pendingCount > 0) {
        Box(
            modifier = modifier
                .padding(top = topPadding)
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (pendingCount == 1) "image pending sync" else "$pendingCount images pending sync",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    if (isEditing) {
        Row(
            modifier = modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            images.forEachIndexed { index, bytes ->
                Box(modifier = Modifier.size(72.dp)) {
                    AsyncImage(
                        model = bytes,
                        contentDescription = "Editing Image Preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp))
                    )
                    if (onRemoveImage != null) {
                        IconButton(
                            onClick = { onRemoveImage(index) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 6.dp, y = (-6).dp)
                                .size(20.dp)
                                .background(MaterialTheme.colorScheme.error, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove Image",
                                tint = MaterialTheme.colorScheme.onError,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }
        }
        return
    }

    // Key the gallery state on the image CONTENT, not the list reference:
    // a message edit that reuses the same byte-array instances must still
    // reset a stale index or close an open viewer when the pixels change.
    val imagesContentKey = remember(images) {
        images.joinToString(":") { it.contentHashCode().toString() }
    }
    var galleryInitialIndex by remember(imagesContentKey) { mutableStateOf(0) }
    var showGallery by remember(imagesContentKey) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .padding(top = topPadding)
            .fillMaxWidth()
            .wrapContentHeight()
    ) {
        when (images.size) {
            1 -> {
                AsyncImage(
                    model = images[0],
                    contentDescription = "Message Attached Image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            galleryInitialIndex = 0
                            showGallery = true
                        }
                )
            }
            2 -> {
                Row(
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    images.forEachIndexed { index, bytes ->
                        AsyncImage(
                            model = bytes,
                            contentDescription = "Message Attached Image Split",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    galleryInitialIndex = index
                                    showGallery = true
                                }
                        )
                    }
                }
            }
            else -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AsyncImage(
                        model = images[0],
                        contentDescription = "Message Attached Image Collage Left",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .weight(1.2f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                            .clickable {
                                galleryInitialIndex = 0
                                showGallery = true
                            }
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AsyncImage(
                            model = images[1],
                            contentDescription = "Message Attached Image Collage Top Right",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(topEnd = 12.dp))
                                .clickable {
                                    galleryInitialIndex = 1
                                    showGallery = true
                                }
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            AsyncImage(
                                model = images[2],
                                contentDescription = "Message Attached Image Collage Bottom Right",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(bottomEnd = 12.dp))
                                    .clickable {
                                        galleryInitialIndex = 2
                                        showGallery = true
                                    }
                            )
                            if (images.size > 3) {
                                val hiddenCount = images.size - 3
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(bottomEnd = 12.dp))
                                        .background(Color.Black.copy(alpha = 0.55f))
                                        .clickable {
                                            galleryInitialIndex = 2
                                            showGallery = true
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "+$hiddenCount",
                                        color = Color.White,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showGallery && images.isNotEmpty()) {
        FullscreenImageViewer(images = images, initialIndex = galleryInitialIndex, onDismiss = { showGallery = false })
    }
}
