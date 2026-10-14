package chat.donzi.localtavern.ui.settings

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
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParameterSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    format: (Float) -> String = { ((it * 100).roundToInt() / 100.0).toString() },
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value) }
    var textValue by remember(value) { mutableStateOf(format(value)) }
    var isEditing by remember { mutableStateOf(false) }

    // Shared by the Enter handler and the focus-loss handler.
    fun commitOrRevert() {
        val parsed = textValue.toFloatOrNull()
        // toFloatOrNull() accepts "NaN"/"Infinity" as valid floats; a
        // non-finite value must be rejected or it poisons the Slider state
        // and is persisted into the API request (which then fails to
        // serialize).
        if (parsed != null && parsed.isFinite()) {
            val clamped = parsed.coerceIn(range.start, range.endInclusive)
            // Sync the local slider/text state so the display reflects the
            // committed value even when the clamped value equals the current one.
            sliderValue = clamped
            textValue = format(clamped)
            onValueChange(clamped)
        } else {
            // Invalid input: revert to the last valid value.
            textValue = format(sliderValue)
        }
    }
    
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(), 
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            
            if (isEditing && enabled) {
                BasicTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    modifier = Modifier
                        .width(60.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .onFocusChanged { focusState ->
                            // Commit (or revert) when the user clicks away:
                            // otherwise the raw uncommitted text keeps
                            // displaying next to a mismatched thumb.
                            if (!focusState.isFocused && isEditing) {
                                commitOrRevert()
                                isEditing = false
                            }
                        }
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyUp && event.key == Key.Escape) {
                                textValue = format(sliderValue)
                                isEditing = false
                                true
                            } else if (event.type == KeyEventType.KeyUp && event.key == Key.Enter) {
                                commitOrRevert()
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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            } else {
                Text(
                    format(sliderValue),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(enabled = enabled) { isEditing = true }
                )
            }
        }
        Slider(
            value = sliderValue,
            onValueChange = {
                sliderValue = it
                textValue = format(it)
            },
            onValueChangeFinished = { onValueChange(sliderValue) },
            valueRange = range,
            steps = steps,
            enabled = enabled,
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = remember { MutableInteractionSource() },
                    thumbSize = DpSize(12.dp, 12.dp)
                )
            },
            track = {
                val activeColor = MaterialTheme.colorScheme.primary
                val inactiveColor = MaterialTheme.colorScheme.surfaceVariant

                androidx.compose.foundation.Canvas(
                    modifier = Modifier.fillMaxWidth().height(4.dp)
                ) {
                    val strokeWidth = 2.dp.toPx()

                    drawLine(
                        color = inactiveColor,
                        start = androidx.compose.ui.geometry.Offset(0f, center.y),
                        end = androidx.compose.ui.geometry.Offset(size.width, center.y),
                        strokeWidth = strokeWidth,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )

                    val fraction = if (range.start == range.endInclusive) 0f else {
                        ((sliderValue - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                    }
                    val activeWidth = size.width * fraction

                    if (activeWidth > 0f) {
                        drawLine(
                            color = activeColor,
                            start = androidx.compose.ui.geometry.Offset(0f, center.y),
                            end = androidx.compose.ui.geometry.Offset(activeWidth, center.y),
                            strokeWidth = strokeWidth,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                    }
                }
            }
        )
    }
}
