package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp

@Composable
fun ThemeToggle(
    isDarkMode: Boolean,
    onToggleDarkMode: (Boolean, Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    var toggleOffset by remember { mutableStateOf(Offset.Zero) }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Dark Mode",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(end = 8.dp).alpha(0.7f)
        )
        Switch(
            checked = isDarkMode,
            onCheckedChange = { onToggleDarkMode(it, toggleOffset) },
            thumbContent = {
                // Custom thumb content to keep the size constant
                Box(modifier = Modifier.size(24.dp).background(Color.Transparent))
            },
            modifier = Modifier.onGloballyPositioned {
                val pos = it.positionInRoot()
                toggleOffset = Offset(pos.x + it.size.width / 2, pos.y + it.size.height / 2)
            }
        )
    }
}
