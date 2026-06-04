package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage

@Composable
fun FullscreenImageViewer(
    avatarData: ByteArray,
    onDismiss: () -> Unit
) {
    FullscreenImageViewer(
        images = listOf(avatarData),
        initialIndex = 0,
        onDismiss = onDismiss
    )
}

@Composable
fun FullscreenImageViewer(
    images: List<ByteArray>,
    initialIndex: Int,
    onDismiss: () -> Unit
) {
    var currentIndex by remember(initialIndex) { mutableStateOf(initialIndex.coerceIn(0, images.size - 1)) }
    val thumbnailScrollState = rememberScrollState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.98f))
        ) {
            if (images.isNotEmpty()) {
                AsyncImage(
                    model = images[currentIndex],
                    contentDescription = "Full view display",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 120.dp, top = 80.dp, start = 16.dp, end = 16.dp)
                        .align(Alignment.Center)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 28.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (images.size > 1) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            text = "${currentIndex + 1} / ${images.size}",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                IconButton(
                    onClick = onDismiss,
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.6f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close gallery",
                        tint = Color.White
                    )
                }
            }

            if (images.size > 1 && currentIndex > 0) {
                IconButton(
                    onClick = { currentIndex-- },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 16.dp),
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.6f))
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
            }

            if (images.size > 1 && currentIndex < images.size - 1) {
                IconButton(
                    onClick = { currentIndex++ },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp),
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.6f))
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Forward",
                        tint = Color.White
                    )
                }
            }

            if (images.size > 1) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp, start = 24.dp, end = 24.dp),
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(thumbnailScrollState)
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        images.forEachIndexed { index, bytes ->
                            val isActiveIndex = index == currentIndex

                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.DarkGray)
                                    .border(
                                        width = if (isActiveIndex) 3.dp else 0.dp,
                                        color = if (isActiveIndex) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable { currentIndex = index }
                            ) {
                                AsyncImage(
                                    model = bytes,
                                    contentDescription = "Gallery Thumbnail Shortcut",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                    alpha = if (isActiveIndex) 1.0f else 0.45f
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}