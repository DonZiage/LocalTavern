package chat.donzi.localtavern.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.openDirectory
import kotlin.math.abs
import kotlin.math.roundToInt
@Composable
fun ExportNotificationBubble(
    visible: Boolean,
    exportedDir: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // A second export while the bubble is already visible must not keep the
    // previous notification's partial swipe offset (the pointer handler below
    // is keyed the same way so a stale drag cannot survive into the new one).
    var swipeOffsetX by remember(visible, exportedDir) { mutableStateOf(0f) }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val animatedSwipeOffsetX by animateFloatAsState(
        targetValue = swipeOffsetX,
        animationSpec = tween(durationMillis = 180),
        label = "exportSwipe"
    )

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier
            .padding(16.dp)
            .fillMaxWidth(0.95f)
    ) {
        Card(
            modifier = Modifier
                .offset { IntOffset(animatedSwipeOffsetX.roundToInt(), 0) }
                .pointerInput(visible, exportedDir) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (abs(swipeOffsetX) > 150f) {
                                currentOnDismiss()
                            } else {
                                swipeOffsetX = 0f
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            // Only track the drag while the pointer is actually
                            // pressed: stale drag events after release would
                            // otherwise push the bubble off-screen.
                            if (change.pressed) {
                                change.consume()
                                swipeOffsetX += dragAmount
                            }
                        }
                    )
                }
                .clickable {
                    openDirectory(exportedDir)
                    currentOnDismiss()
                },
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.inverseSurface),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Character exported. Press here to locate",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = exportedDir,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = currentOnDismiss,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}