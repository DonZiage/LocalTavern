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
    // Only record frames into the offscreen layer while a transition is in
    // flight; the layer is consumed by toImageBitmap() during the capture and
    // would otherwise re-render the whole UI offscreen on every frame.
    var isCapturing by remember { mutableStateOf(false) }
    // Monotonic counter identifying the newest transition. A superseded job
    // that finishes its cleanup after a newer one started must neither clear
    // isCapturing (the newer job is still capturing) nor publish its snapshot.
    var transitionGeneration by remember { mutableStateOf(0) }

    LaunchedEffect(initialThemeIsDark) {
        isDark = initialThemeIsDark
    }

    val triggerTransition: (Offset) -> Unit = { center ->
        // A toggle during an in-flight reveal restarts the transition instead
        // of being silently dropped. Clear the previous snapshot so the OLD
        // theme image cannot flash on screen while the new one is captured.
        transitionJob?.cancel()
        transitionGeneration++
        val generation = transitionGeneration
        snapshot = null
        animationCenter = center
        val targetDark = !isDark
        // Flip the theme synchronously: a rapid double-toggle must land on the
        // opposite theme, not compute "!isDark" twice from the stale flag.
        isDark = targetDark
        // Persist the new theme immediately so a transition cancelled mid-flight
        // (second toggle, window close) is not silently lost.
        onThemeSaved(targetDark)
        isCapturing = true
        transitionJob = coroutineScope.launch {
            try {
                // If a reveal is still in flight, finish it first so the snapshot
                // below is not a partially-clipped frame.
                if (revealProgress.value < 1f) {
                    revealProgress.animateTo(1f, animationSpec = tween(180))
                }
                // The freshly enabled recording layer has not drawn anything
                // yet; wait for the next frame so the capture is not empty
                // (which silently skips the very first toggle's animation).
                withFrameNanos { }
                val captured = try {
                    graphicsLayer.toImageBitmap()
                } catch (_: Exception) {
                    null
                }
                if (captured == null || generation != transitionGeneration) return@launch
                snapshot = captured
                revealProgress.snapTo(0f)
                delay(50.milliseconds)
                revealProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(500, easing = FastOutSlowInEasing)
                )
                snapshot = null
            } finally {
                // Only the newest transition may reset the capture flag; a
                // cancelled job's cleanup must not clobber a live capture.
                if (generation == transitionGeneration) {
                    isCapturing = false
                }
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
                    .drawWithContent {
                        if (isCapturing) {
                            graphicsLayer.record {
                                this@drawWithContent.drawContent()
                            }
                        }
                        drawContent()
                    }
            ) {
                content(isDark, triggerTransition)
            }
        }
    }
}