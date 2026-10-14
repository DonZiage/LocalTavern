package chat.donzi.localtavern.ui.chat

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

private const val ELLIPSIS_CYCLE_MS = 900
private const val ELLIPSIS_ACTIVE_MS = 300

@Composable
fun AnimatedEllipsis(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "ellipsis")

    // A single shared phase drives every dot, so the staggered dots can never
    // drift out of sync (per-dot tween delays re-applied on every repeat cycle).
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(ELLIPSIS_CYCLE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ellipsisPhase"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        EllipsisDot(color = color, phase = phase, offsetMillis = 0)
        EllipsisDot(color = color, phase = phase, offsetMillis = ELLIPSIS_ACTIVE_MS)
        EllipsisDot(color = color, phase = phase, offsetMillis = ELLIPSIS_ACTIVE_MS * 2)
    }
}

@Composable
private fun EllipsisDot(color: Color, phase: Float, offsetMillis: Int) {
    val localTime = (phase * ELLIPSIS_CYCLE_MS + offsetMillis) % ELLIPSIS_CYCLE_MS
    val bounce = when {
        localTime < ELLIPSIS_ACTIVE_MS -> localTime / ELLIPSIS_ACTIVE_MS
        localTime < ELLIPSIS_ACTIVE_MS * 2 -> (ELLIPSIS_ACTIVE_MS * 2 - localTime) / ELLIPSIS_ACTIVE_MS
        else -> 0f
    }

    Box(
        modifier = Modifier
            .size(6.dp)
            .graphicsLayer {
                translationY = -6f * bounce
                this.alpha = 0.4f + 0.6f * bounce
            }
            .background(color, CircleShape)
    )
}
