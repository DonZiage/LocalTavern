package chat.donzi.localtavern.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
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
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    val snapFlingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    LaunchedEffect(items) {
        val newlyAddedItemIndex = items.indexOfFirst { !reorderableItems.contains(it) }

        reorderableItems.clear()
        reorderableItems.addAll(items)

        if (newlyAddedItemIndex != -1) {
            delay(50.milliseconds)
            try {
                isCentering = true
                val layoutInfo = listState.layoutInfo
                val viewportWidth = layoutInfo.viewportSize.width
                val visibleItems = layoutInfo.visibleItemsInfo
                val itemSize = visibleItems.find { it.index == newlyAddedItemIndex }?.size
                    ?: (viewportWidth * itemWidthFactor).roundToInt()
                val centerOffset = -((viewportWidth - itemSize) / 2)
                listState.animateScrollToItem(newlyAddedItemIndex, centerOffset)
            } catch (_: Exception) {
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
                            listState.animateScrollToItem(item.index, -targetOffset)
                        } catch (_: Exception) {
                        } finally {
                            isCentering = false
                        }
                    }
                }
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
                        if (event.type == PointerEventType.Scroll) {
                            val delta = event.changes.first().scrollDelta
                            val scrollAmount = delta.y * 64f + delta.x * 64f
                            if (scrollAmount != 0f) {
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
        if (title.isNotBlank()) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            )
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val spacingDp = 20.dp
            val edgePadding = 24.dp

            val itemWidthDp = (this.maxWidth - (edgePadding * 2))
                .coerceAtMost(this.maxWidth * itemWidthFactor)
                .coerceAtLeast(150.dp)

            val horizontalPaddingDp = (this.maxWidth - itemWidthDp) / 2
            val itemWidthPx = with(density) { itemWidthDp.toPx() }

            LaunchedEffect(initialIndex, this.maxWidth) {
                val size = items.size
                if (size > 0) {
                    val targetIndex = initialIndex.coerceIn(0, size - 1)
                    val centerOffset = -((constraints.maxWidth - itemWidthPx) / 2).roundToInt()
                    if (!hasScrolledToInitial) {
                        listState.scrollToItem(targetIndex, centerOffset)
                        hasScrolledToInitial = true
                    } else {
                        if (!isCentering) {
                            try {
                                isCentering = true
                                listState.animateScrollToItem(targetIndex, centerOffset)
                            } catch (_: Exception) {
                            } finally {
                                isCentering = false
                            }
                        }
                    }
                }
            }

            suspend fun scrollToIndexCentered(index: Int) {
                if (isCentering) return
                try {
                    isCentering = true
                    val centerOffset = -((constraints.maxWidth - itemWidthPx) / 2).roundToInt()
                    listState.animateScrollToItem(index, centerOffset)
                } catch (_: Exception) {
                } finally {
                    isCentering = false
                }
            }

            LazyRow(
                state = listState,
                userScrollEnabled = !isCentering && draggedItemId == null,
                flingBehavior = snapFlingBehavior,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(carouselHeight)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val firstChange = event.changes.firstOrNull()

                                if (firstChange != null && firstChange.type == PointerType.Mouse && event.type == PointerEventType.Press) {
                                    val currentDragId = firstChange.id
                                    var lastX = firstChange.position.x

                                    while (true) {
                                        val dragEvent = awaitPointerEvent(PointerEventPass.Main)
                                        val dragChange = dragEvent.changes.find { it.id == currentDragId }

                                        if (dragChange == null || dragEvent.type == PointerEventType.Release) {
                                            scope.launch { performSnap() }
                                            break
                                        }

                                        if (draggedItemId == null && !isCentering) {
                                            val deltaX = dragChange.position.x - lastX
                                            scope.launch { listState.scrollBy(-deltaX) }
                                            dragChange.consume()
                                            lastX = dragChange.position.x
                                        }
                                    }
                                }
                            }
                        }
                    },
                contentPadding = PaddingValues(
                    start = horizontalPaddingDp,
                    end = horizontalPaddingDp + 4.dp
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
                        }
                    }

                    val handleDragEnd: () -> Unit = {
                        draggedItemId = null
                        dragDisplacement = 0f
                        onReorder(reorderableItems.toList())
                        scope.launch { performSnap() }
                    }

                    val handleDragCancel: () -> Unit = {
                        draggedItemId = null
                        dragDisplacement = 0f
                        scope.launch { performSnap() }
                    }

                    val handleDrag: (PointerInputChange, Offset) -> Unit = { change, dragAmount ->
                        if (draggedItemId == itemId) {
                            change.consume()
                            dragDisplacement += dragAmount.x

                            val currentIdx = reorderableItems.indexOfFirst { key(it) == itemId }
                            if (currentIdx != -1) {
                                val spacingPx = with(density) { spacingDp.toPx() }
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