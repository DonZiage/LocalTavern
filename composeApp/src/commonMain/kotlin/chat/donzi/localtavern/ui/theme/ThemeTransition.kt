package chat.donzi.localtavern.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds

class CircularRevealShape(private val progress: Float, private val center: Offset) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: androidx.compose.ui.unit.Density
    ): Outline {
        // Safe fallback in case the click offset is ever invalid
        val safeCenter = if (center.isSpecified) center else Offset(size.width / 2f, size.height / 2f)
        val maxRadius = max(
            max(hypot(safeCenter.x, safeCenter.y), hypot(size.width - safeCenter.x, safeCenter.y)),
            max(hypot(safeCenter.x, size.height - safeCenter.y), hypot(size.width - safeCenter.x, size.height - safeCenter.y))
        )
        val radius = maxRadius * progress
        val path = Path().apply { addOval(Rect(center = safeCenter, radius = radius)) }
        return Outline.Generic(path)
    }
}

@Composable
fun ThemeTransition(
    initialThemeIsDark: Boolean,
    onThemeSaved: (Boolean) -> Unit,
    content: @Composable (syncedDarkTheme: Boolean, triggerTransition: (Offset) -> Unit) -> Unit
) {
    val graphicsLayer = rememberGraphicsLayer()
    val coroutineScope = rememberCoroutineScope()

    var isDark by remember { mutableStateOf(initialThemeIsDark) }
    var snapshot by remember { mutableStateOf<ImageBitmap?>(null) }

    val revealProgress = remember { Animatable(1f) }
    var animationCenter by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(initialThemeIsDark) {
        isDark = initialThemeIsDark
    }

    val triggerTransition: (Offset) -> Unit = { center ->
        if (revealProgress.value == 1f) {
            animationCenter = center
            coroutineScope.launch {
                snapshot = try {
                    graphicsLayer.toImageBitmap()
                } catch (_: Exception) {
                    null
                }
                revealProgress.snapTo(0f)
                isDark = !isDark
                delay(50.milliseconds)
                revealProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(500, easing = FastOutSlowInEasing)
                )
                snapshot = null
                onThemeSaved(isDark)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        snapshot?.let { bmp ->
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawImage(bmp)
            }
        }

        LocalTavernTheme(darkTheme = isDark) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val progress = revealProgress.value
                        if (progress < 1f) {
                            clip = true
                            shape = CircularRevealShape(progress, animationCenter)
                        } else {
                            clip = false
                        }
                    }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (revealProgress.value < 1f) {
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                    .drawWithContent {
                        graphicsLayer.record {
                            this@drawWithContent.drawContent()
                        }
                        drawContent()
                    }
            ) {
                content(isDark, triggerTransition)
            }
        }
    }
}