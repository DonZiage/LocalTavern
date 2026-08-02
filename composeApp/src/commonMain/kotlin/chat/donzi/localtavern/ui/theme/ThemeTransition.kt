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
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.Job
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
    var transitionJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(initialThemeIsDark) {
        isDark = initialThemeIsDark
    }

    val triggerTransition: (Offset) -> Unit = { center ->
        // A toggle during an in-flight reveal restarts the transition instead
        // of being silently dropped.
        transitionJob?.cancel()
        animationCenter = center
        val targetDark = !isDark
        // Persist the new theme immediately so a transition cancelled mid-flight
        // (second toggle, window close) is not silently lost.
        onThemeSaved(targetDark)
        transitionJob = coroutineScope.launch {
            // If a reveal is still in flight, finish it first so the snapshot
            // below is not a partially-clipped frame.
            if (revealProgress.value < 1f) {
                revealProgress.animateTo(1f, animationSpec = tween(180))
            }
            val captured = try {
                graphicsLayer.toImageBitmap()
            } catch (_: Exception) {
                null
            }
            isDark = targetDark
            if (captured == null) return@launch
            snapshot = captured
            revealProgress.snapTo(0f)
            delay(50.milliseconds)
            revealProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(500, easing = FastOutSlowInEasing)
            )
            snapshot = null
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