package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
fun StatusIndicator(isAlive: Boolean?, modifier: Modifier = Modifier) {
    val color = when (isAlive) {
        true -> Color(0xFF1B5E20) // Darker Green
        false -> Color(0xFFB71C1C) // Darker Red
        null -> Color(0xFF616161) // Darker Grey
    }
    Box(
        modifier = modifier
            .background(color, shape = CircleShape)
    )
}
