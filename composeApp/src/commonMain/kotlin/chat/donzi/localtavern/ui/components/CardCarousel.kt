package chat.donzi.localtavern.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun <T> CardCarousel(
    title: String,
    items: List<T>,
    key: (T) -> Any,
    onReorder: (List<T>) -> Unit = {},
    onAddClick: () -> Unit,
    onDelete: ((T) -> Unit)? = null,
    addLabel: String = "Add New",
    cardHeight: Dp = 115.dp,
    carouselHeight: Dp = 140.dp,
    itemWidthFactor: Float = 0.7f,
    initialIndex: Int = 0,
    itemContent: @Composable (item: T, isDragging: Boolean, modifier: Modifier, requestCenter: () -> Unit, onDeleteRequest: () -> Unit) -> Unit
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val reorderableItems = remember { mutableStateListOf<T>() }
    var hasScrolledToInitial by remember { mutableStateOf(false) }
    var itemToDelete by remember { mutableStateOf<T?>(null) }

    var isCentering by remember { mutableStateOf(false) }
    var draggedItemId by remember { mutableStateOf<Any?>(null) }
    var dragDisplacement by remember { mutableFloatStateOf(0f) }
    var lastInteractionTime by remember { mutableLongStateOf(0L) }

    // Centering new items logic added here
    LaunchedEffect(items) {
        // Find if there is a new item that wasn't in the list before
        val newlyAddedItemIndex = items.indexOfFirst { !reorderableItems.contains(it) }

        reorderableItems.clear()
        reorderableItems.addAll(items)

        // If a new item was added, wait a moment for layout, then center it
        if (newlyAddedItemIndex != -1) {
            delay(50.milliseconds) // Give Compose a frame to render the new item
            try {
                isCentering = true
                listState.animateScrollToItem(newlyAddedItemIndex, 0)
            } finally {
                isCentering = false
            }
        }
    }

    if (itemToDelete != null) {
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text("Delete Profile?") },
            text = { Text("This profile will be permanently removed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        itemToDelete?.let { onDelete?.invoke(it) }
                        itemToDelete = null
                    }
                ) {
                    Text("Delete", color = Color(0xFFD32F2F))
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    val performSnap = suspend {
        if (draggedItemId == null && !isCentering) {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isNotEmpty()) {
                val viewportWidth = layoutInfo.viewportSize.width
                val viewportCenter = viewportWidth / 2
                val closestItem = visibleItems.minByOrNull { item ->
                    kotlin.math.abs((item.offset + item.size / 2).toFloat() - viewportCenter.toFloat())
                }
                closestItem?.let { item ->
                    val targetOffset = (viewportWidth - item.size) / 2
                    if (kotlin.math.abs(item.offset - targetOffset) > 1) {
                        try {
                            isCentering = true
                            listState.animateScrollToItem(item.index, 0)
                        } finally {
                            isCentering = false
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(listState.isScrollInProgress, lastInteractionTime, draggedItemId, isCentering) {
        if (isCentering || draggedItemId != null) return@LaunchedEffect

        if (!listState.isScrollInProgress) {
            val now = Clock.System.now().toEpochMilliseconds()
            val interactionAge = if (lastInteractionTime > 0) now - lastInteractionTime else 10000

            if (interactionAge < 400) {
                delay((400 - interactionAge).milliseconds)
            } else if (lastInteractionTime > 0) {
                delay(50.milliseconds)
            } else {
                return@LaunchedEffect
            }

            val finalNow = Clock.System.now().toEpochMilliseconds()
            if (!listState.isScrollInProgress && !isCentering && draggedItemId == null && (finalNow - lastInteractionTime) >= 400) {
                scope.launch { performSnap() }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (isCentering) {
                            event.changes.forEach { it.consume() }
                        } else if (event.type == PointerEventType.Scroll) {
                            val delta = event.changes.first().scrollDelta
                            val scrollAmount = delta.y * 64f + delta.x * 64f
                            if (scrollAmount != 0f) {
                                lastInteractionTime = Clock.System.now().toEpochMilliseconds()
                                scope.launch {
                                    listState.scrollBy(scrollAmount)
                                }
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
            }
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
        )

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val spacingDp = 20.dp
            val edgePadding = 24.dp

            val itemWidthDp = (this.maxWidth - (edgePadding * 2))
                .coerceAtMost(this.maxWidth * itemWidthFactor)
                .coerceAtLeast(150.dp)

            val horizontalPaddingDp = (this.maxWidth - itemWidthDp) / 2

            val itemWidthPx = with(density) { itemWidthDp.toPx() }
            val spacingPx = with(density) { spacingDp.toPx() }

            LaunchedEffect(initialIndex, this.maxWidth) {
                val size = items.size
                if (size > 0 || initialIndex == 0) {
                    val targetIndex = initialIndex.coerceIn(0, size)
                    if (!hasScrolledToInitial) {
                        listState.scrollToItem(targetIndex, 0)
                        hasScrolledToInitial = true
                    } else {
                        listState.animateScrollToItem(targetIndex, 0)
                    }
                }
            }

            suspend fun scrollToIndexCentered(index: Int) {
                try {
                    isCentering = true
                    listState.animateScrollToItem(index, 0)
                } finally {
                    isCentering = false
                }
            }

            LazyRow(
                state = listState,
                userScrollEnabled = !isCentering && draggedItemId == null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(carouselHeight)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, dragAmount ->
                                if (draggedItemId == null && !isCentering) {
                                    lastInteractionTime = Clock.System.now().toEpochMilliseconds()
                                    change.consume()
                                    scope.launch { listState.scrollBy(-dragAmount) }
                                }
                            },
                            onDragEnd = {
                                lastInteractionTime = Clock.System.now().toEpochMilliseconds()
                                scope.launch { performSnap() }
                            },
                            onDragCancel = {
                                lastInteractionTime = Clock.System.now().toEpochMilliseconds()
                                scope.launch { performSnap() }
                            }
                        )
                    },
                contentPadding = PaddingValues(
                    start = horizontalPaddingDp,
                    end = horizontalPaddingDp + 4.dp // Boundary fix
                ),
                horizontalArrangement = Arrangement.spacedBy(spacingDp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                itemsIndexed(reorderableItems, key = { _, item -> key(item) }) { index, item ->
                    val itemId = key(item)
                    val isDragging = itemId == draggedItemId
                    val scale by animateFloatAsState(if (isDragging) 1.05f else 1f)

                    val handleDragStart: (Offset) -> Unit = {
                        if (!isCentering) {
                            draggedItemId = itemId
                            dragDisplacement = 0f
                            lastInteractionTime = Clock.System.now().toEpochMilliseconds()
                        }
                    }

                    val handleDragEnd: () -> Unit = {
                        draggedItemId = null
                        dragDisplacement = 0f
                        onReorder(reorderableItems.toList())
                        lastInteractionTime = Clock.System.now().toEpochMilliseconds()
                        scope.launch { performSnap() }
                    }

                    val handleDragCancel: () -> Unit = {
                        draggedItemId = null
                        dragDisplacement = 0f
                        lastInteractionTime = Clock.System.now().toEpochMilliseconds()
                        scope.launch { performSnap() }
                    }

                    val handleDrag: (PointerInputChange, Offset) -> Unit = { change, dragAmount ->
                        if (draggedItemId == itemId) {
                            lastInteractionTime = Clock.System.now().toEpochMilliseconds()
                            change.consume()
                            dragDisplacement += dragAmount.x

                            val currentIdx = reorderableItems.indexOfFirst { key(it) == itemId }
                            if (currentIdx != -1) {
                                val threshold = itemWidthPx + spacingPx
                                val shift = (dragDisplacement / threshold).roundToInt()
                                if (shift != 0) {
                                    val targetIdx = (currentIdx + shift).coerceIn(0, reorderableItems.size - 1)
                                    if (targetIdx != currentIdx) {
                                        reorderableItems.add(targetIdx, reorderableItems.removeAt(currentIdx))
                                        dragDisplacement -= shift * threshold
                                    }
                                }
                            }
                        }
                    }

                    val onRequestCenter: () -> Unit = {
                        scope.launch { scrollToIndexCentered(index) }
                    }

                    // FIX: Lambda moved out of parentheses
                    itemContent(
                        item,
                        isDragging,
                        Modifier
                            .width(itemWidthDp)
                            .height(cardHeight)
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                translationX = if (isDragging) dragDisplacement else 0f
                                scaleX = scale
                                scaleY = scale
                                alpha = if (isDragging) 0.8f else 1f
                            }
                            .pointerInput(itemId) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = handleDragStart,
                                    onDragEnd = handleDragEnd,
                                    onDragCancel = handleDragCancel,
                                    onDrag = handleDrag
                                )
                            },
                        onRequestCenter
                    ) {
                        itemToDelete = item
                    }
                }

                item(key = "carousel_add_card") {
                    OutlinedCard(
                        onClick = {
                            if (!isCentering) {
                                // Just fire the callback. The LaunchedEffect(items) will handle
                                // the scroll once the parent provides the updated list.
                                onAddClick()
                            }
                        },
                        modifier = Modifier
                            .width(itemWidthDp)
                            .height(cardHeight)
                            .graphicsLayer { },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                            Text(addLabel, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}