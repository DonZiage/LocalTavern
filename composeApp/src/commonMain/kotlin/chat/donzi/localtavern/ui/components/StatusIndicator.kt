package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun StatusIndicator(isAlive: Boolean?, modifier: Modifier = Modifier, size: Dp = 12.dp) {
    val color = when (isAlive) {
        true -> Color(0xFF4CAF50)
        false -> Color(0xFFE53935)
        null -> MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = modifier
            .size(size)
            .background(color, shape = CircleShape)
    )
}
