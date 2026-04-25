package chat.donzi.localtavern.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
fun AnimatedEllipsis() {
    val infiniteTransition = rememberInfiniteTransition()
    
    val dot1Offset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -5f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    val dot2Offset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -5f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, delayMillis = 150, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    val dot3Offset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -5f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, delayMillis = 300, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)
    ) {
        Text("•", modifier = Modifier.graphicsLayer(translationY = dot1Offset))
        Text("•", modifier = Modifier.graphicsLayer(translationY = dot2Offset))
        Text("•", modifier = Modifier.graphicsLayer(translationY = dot3Offset))
    }
}
