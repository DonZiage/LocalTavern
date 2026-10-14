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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
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

    // Syncs an externally changed theme (the async DB read at startup) into
    // the transition state, but never while a transition is in flight: the DB
    // write triggered by the toggle itself completes during the reveal and
    // would otherwise snap the theme back to a stale value.
    LaunchedEffect(initialThemeIsDark) {
        if (transitionJob?.isActive != true) {
            isDark = initialThemeIsDark
        }
    }

    val triggerTransition: (Offset) -> Unit = { center ->
        // A toggle during an in-flight reveal restarts the transition instead
        // of being silently dropped. Only clear the previous snapshot when no
        // transition is running: a cancelled mid-reveal must not flash the raw
        // window background while the new frame is captured.
        val wasTransitionActive = transitionJob?.isActive == true
        transitionJob?.cancel()
        transitionGeneration++
        val generation = transitionGeneration
        if (!wasTransitionActive) snapshot = null
        animationCenter = center
        val targetDark = !isDark
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
                // Wait until the recording layer has drawn at least one frame of
                // the CURRENT theme. Frame callbacks resume before the draw of
                // the same frame, so a single frame is not enough for the
                // capture to be non-empty.
                withFrameNanos { }
                withFrameNanos { }
                val captured = try {
                    graphicsLayer.toImageBitmap()
                } catch (_: Exception) {
                    null
                }
                if (generation != transitionGeneration) return@launch
                if (captured == null) {
                    // No snapshot: fall back to a plain flip without animation.
                    isDark = targetDark
                    return@launch
                }
                // Flip the theme only AFTER capturing: the snapshot must show
                // the PREVIOUS look, so the reveal genuinely animates from it.
                // Elements inside the growing circle update immediately while
                // everything else keeps the frozen old-theme image until the
                // circle reaches it. The clip and the theme flip land in the
                // same recomposition, so the new theme never flashes
                // full-screen before the reveal starts.
                isCapturing = false
                snapshot = captured
                revealProgress.snapTo(0f)
                isDark = targetDark
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
                // Scale the capture to the canvas: drawImage's default renders
                // the bitmap at its pixel size, which overshoots the canvas on
                // HiDPI screens and leaves the snapshot misaligned.
                drawImage(bmp, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
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