package chat.donzi.localtavern.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
fun AnimatedEllipsis(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "ellipsis")

    // Helper component to avoid repeating the animation code for all three dots
    @Composable
    fun Dot(delayMillis: Int) {
        val offset by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = -6f,
            animationSpec = infiniteRepeatable(
                animation = tween(300, delayMillis = delayMillis, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dotOffset"
        )

        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(300, delayMillis = delayMillis, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dotAlpha"
        )

        Box(
            modifier = Modifier
                .size(6.dp)
                .graphicsLayer {
                    translationY = offset
                    this.alpha = alpha
                }
                .background(color, CircleShape)
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        Dot(delayMillis = 0)
        Dot(delayMillis = 150)
        Dot(delayMillis = 300)
    }
}