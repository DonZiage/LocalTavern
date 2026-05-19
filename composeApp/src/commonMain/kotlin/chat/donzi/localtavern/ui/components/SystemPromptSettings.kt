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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import chat.donzi.localtavern.utils.PromptBlock
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
    val rowHeightPx = with(density) { 52.dp.toPx() }

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
                        .pointerInput(block.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggedBlockId = block.id
                                    dragDisplacement = 0f
                                },
                                onDragEnd = {
                                    if (currentIdx != -1 && targetIdx != -1 && targetIdx != currentIdx) {
                                        val newList = blocks.toMutableList()
                                        val movedItem = newList.removeAt(currentIdx)
                                        newList.add(targetIdx, movedItem)
                                        onBlocksChange(newList)
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