package chat.donzi.localtavern.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import chat.donzi.localtavern.domain.PromptBlock
import kotlin.math.roundToInt

@Composable
fun SystemPromptSettings(
    blocks: List<PromptBlock>,
    onBlocksChange: (List<PromptBlock>) -> Unit,
    onBlockMutate: (PromptBlock) -> Unit,
    onBlockAdd: (String, String) -> Unit,
    onBlockDelete: (String) -> Unit
) {
    var editingBlock by remember { mutableStateOf<PromptBlock?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    var draggedBlockId by remember { mutableStateOf<String?>(null) }
    var dragDisplacement by remember { mutableFloatStateOf(0f) }

    val density = LocalDensity.current
    // Measure the real row pitch instead of guessing a fixed height, so the
    // drop-index math stays correct across densities and font scales.
    var measuredItemHeight by remember { mutableStateOf<Float?>(null) }
    val spacingPx = with(density) { 8.dp.toPx() }
    val rowHeightPx = (measuredItemHeight ?: with(density) { 52.dp.toPx() }) + spacingPx

    // Keep the latest blocks list visible to the pointerInput coroutine, which
    // only captures its lambdas once (keyed on block.id).
    val latestBlocks by rememberUpdatedState(blocks)
    val latestOnBlocksChange by rememberUpdatedState(onBlocksChange)

    val currentIdx = blocks.indexOfFirst { it.id == draggedBlockId }
    val targetIdx = if (currentIdx != -1) {
        (currentIdx + (dragDisplacement / rowHeightPx).roundToInt()).coerceIn(0, blocks.size - 1)
    } else -1

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "System Prompt Structure",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            IconButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add custom prompt layer segment",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            blocks.forEachIndexed { index, block ->
                // Key by id so the per-row animation/drag state follows the
                // block after a drop reorders the list.
                key(block.id) {
                val isDragging = block.id == draggedBlockId

                val targetTranslationY = when {
                    isDragging -> dragDisplacement
                    currentIdx != -1 && targetIdx != -1 -> {
                        if (targetIdx > currentIdx && index in (currentIdx + 1)..targetIdx) {
                            -rowHeightPx
                        } else if (targetIdx < currentIdx && index in targetIdx..<currentIdx) {
                            rowHeightPx
                        } else {
                            0f
                        }
                    }
                    else -> 0f
                }

                val animatedTranslationY by animateFloatAsState(
                    targetValue = targetTranslationY,
                    label = "BlockShiftAnimation"
                )
                val displayTranslationY = if (isDragging) dragDisplacement else animatedTranslationY
                val scale by animateFloatAsState(if (isDragging) 1.04f else 1f)

                PromptBlockItem(
                    block = block,
                    isDragging = isDragging,
                    modifier = Modifier
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = displayTranslationY
                            scaleX = scale
                            scaleY = scale
                            alpha = if (isDragging) 0.9f else 1f
                        }
                        .then(
                            if (index == 0) {
                                Modifier.onGloballyPositioned { measuredItemHeight = it.size.height.toFloat() }
                            } else {
                                Modifier
                            }
                        )
                        // rowHeightPx is a key so the drag/drop math tracks the
                        // measured row height instead of the initial default.
                        .pointerInput(block.id, rowHeightPx) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggedBlockId = block.id
                                    dragDisplacement = 0f
                                },
                                onDragEnd = {
                                    val currentBlockList = latestBlocks
                                    val curIdx = currentBlockList.indexOfFirst { it.id == block.id }
                                    val tgtIdx = if (curIdx != -1) {
                                        (curIdx + (dragDisplacement / rowHeightPx).roundToInt()).coerceIn(0, currentBlockList.size - 1)
                                    } else -1
                                    if (curIdx != -1 && tgtIdx != -1 && tgtIdx != curIdx) {
                                        val newList = currentBlockList.toMutableList()
                                        val movedItem = newList.removeAt(curIdx)
                                        newList.add(tgtIdx, movedItem)
                                        latestOnBlocksChange(newList)
                                    }
                                    draggedBlockId = null
                                    dragDisplacement = 0f
                                },
                                onDragCancel = {
                                    draggedBlockId = null
                                    dragDisplacement = 0f
                                },
                                onDrag = { change, dragAmount ->
                                    if (draggedBlockId == block.id) {
                                        change.consume()
                                        dragDisplacement += dragAmount.y
                                    }
                                }
                            )
                        },
                    onEdit = { editingBlock = block },
                    onToggle = { isEnabled ->
                        onBlockMutate(block.copy(isEnabled = isEnabled))
                    }
                )
                }
            }
        }
    }

    if (showAddDialog) {
        PromptBlockEditDialog(
            block = PromptBlock(id = "", name = "", template = "", isCustom = true),
            onDismiss = { showAddDialog = false },
            onSave = { name, template ->
                onBlockAdd(name, template)
                showAddDialog = false
            }
        )
    }

    editingBlock?.let { block ->
        PromptBlockEditDialog(
            block = block,
            onDismiss = { editingBlock = null },
            onSave = { name, newTemplate ->
                onBlockMutate(block.copy(name = name, template = newTemplate))
                editingBlock = null
            },
            onDelete = if (block.isCustom) {
                {
                    onBlockDelete(block.id)
                    editingBlock = null
                }
            } else null
        )
    }
}