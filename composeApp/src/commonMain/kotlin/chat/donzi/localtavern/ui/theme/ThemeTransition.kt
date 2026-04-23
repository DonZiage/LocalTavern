package chat.donzi.localtavern.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.hypot
import kotlin.math.max

class CircularRevealShape(private val progress: Float, private val center: Offset) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: androidx.compose.ui.unit.Density
    ): Outline {
        val maxRadius = max(
            max(hypot(center.x, center.y), hypot(size.width - center.x, center.y)),
            max(hypot(center.x, size.height - center.y), hypot(size.width - center.x, size.height - center.y))
        )
        val radius = maxRadius * progress
        val path = Path().apply {
            addOval(Rect(center = center, radius = radius))
        }
        return Outline.Generic(path)
    }
}

@Composable
fun ThemeTransitionContainer(
    isDarkMode: Boolean,
    baseLayerDarkMode: Boolean,
    isAnimatingTheme: Boolean,
    animationProgress: Float,
    animationCenter: Offset,
    content: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Base Layer (Shows the "old" theme during animation, then switches to "new")
        LocalTavernTheme(darkTheme = baseLayerDarkMode) {
            Surface(modifier = Modifier.fillMaxSize()) {
                content()
            }
        }

        // Overlay Layer (Reveals the "new" theme)
        if (isAnimatingTheme) {
            LocalTavernTheme(darkTheme = isDarkMode) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            clip = true
                            shape = CircularRevealShape(animationProgress, animationCenter)
                        }
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                ) {
                    content()
                }
            }
        }
    }
}
