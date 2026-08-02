package chat.donzi.localtavern.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextLimitSlider(
    currentLimit: Long,
    onValueChange: (Long) -> Unit
) {
    val presets = remember { listOf(1024L, 2048L, 4096L, 8192L, 16384L, 32768L, 65536L, 0L) }
    val labels = remember {
        mapOf(
            1024L to "1k",
            2048L to "2k",
            4096L to "4k",
            8192L to "8k",
            16384L to "16k",
            32768L to "32k",
            65536L to "64k",
            0L to "Unlimited"
        )
    }

    var sliderValue by remember(currentLimit) {
        val presetIdx = presets.indexOf(currentLimit)
        val idx = if (presetIdx != -1) {
            presetIdx
        } else {
            (presets.indices).minByOrNull { kotlin.math.abs(presets[it] - currentLimit) } ?: 0
        }
        mutableFloatStateOf(idx.toFloat())
    }
    
    var isEditing by remember { mutableStateOf(false) }
    var textValue by remember(currentLimit) { mutableStateOf(currentLimit.toString()) }

    // Commits the typed value, syncing the local thumb/label so the display
    // matches even when the parent keeps the same currentLimit (or normalizes
    // it); reverts on invalid input.
    fun commitOrRevert() {
        val parsed = textValue.toLongOrNull()
        if (parsed != null) {
            val clamped = parsed.coerceIn(0, 1_000_000)
            val presetIdx = presets.indexOf(clamped)
            sliderValue = if (presetIdx != -1) {
                presetIdx.toFloat()
            } else {
                (presets.indices).minByOrNull { kotlin.math.abs(presets[it] - clamped) }?.toFloat() ?: 0f
            }
            textValue = clamped.toString()
            onValueChange(clamped)
        } else {
            textValue = currentLimit.toString()
        }
    }
    
    val interactionSource = remember { MutableInteractionSource() }

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(), 
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Context Limit", style = MaterialTheme.typography.labelMedium)
            
            if (isEditing) {
                BasicTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    modifier = Modifier
                        .width(80.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .onFocusChanged { focusState ->
                            if (!focusState.isFocused && isEditing) {
                                commitOrRevert()
                                isEditing = false
                            }
                        }
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyUp && event.key == Key.Enter) {
                                commitOrRevert()
                                isEditing = false
                                true
                            } else if (event.type == KeyEventType.KeyUp && event.key == Key.Escape) {
                                textValue = currentLimit.toString()
                                isEditing = false
                                true
                            } else false
                        },
                    textStyle = TextStyle(
                        textAlign = TextAlign.End, 
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            } else {
                val presetAtThumb = presets[sliderValue.toInt().coerceIn(0, presets.size - 1)]
                val showCustom = currentLimit !in presets && presetAtThumb != currentLimit
                Text(
                    if (showCustom) "Custom (${currentLimit})" else labels[presetAtThumb] ?: "Custom (${currentLimit})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { isEditing = true }
                )
            }
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = {
                val finalIdx = sliderValue.toInt().coerceIn(0, presets.size - 1)
                onValueChange(presets[finalIdx])
            },
            valueRange = 0f..(presets.size - 1).toFloat(),
            steps = presets.size - 2,
            interactionSource = interactionSource,
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interactionSource,
                    thumbSize = DpSize(12.dp, 12.dp)
                )
            },
            track = {
                val fraction = sliderValue / (presets.size - 1).toFloat()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(1.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp))
                    )
                }
            }
        )
    }
}
