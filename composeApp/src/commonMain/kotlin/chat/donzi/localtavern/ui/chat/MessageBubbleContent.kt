package chat.donzi.localtavern.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The inner column of a message bubble: the streaming placeholder, the inline
// editor, or the rendered (markdown + selectable) text, followed by the
// reasoning disclosure, the image strip and the cost readout. Kept separate
// from MessageBubble so the row layout and the gesture handling stay readable.
@Composable
internal fun MessageBubbleContent(
    content: String,
    isUser: Boolean,
    isGenerating: Boolean,
    isEditing: Boolean,
    textColor: Color,
    editedTextValue: TextFieldValue,
    onEditedTextValueChange: (TextFieldValue) -> Unit,
    editedImages: List<ByteArray>,
    onRemoveEditedImage: (Int) -> Unit,
    focusRequester: FocusRequester,
    bringIntoViewRequester: BringIntoViewRequester,
    onSubmitEdit: () -> Unit,
    messageImages: List<ByteArray>,
    pendingImageCount: Int = 0,
    reasoningText: String?,
    costText: String?
) {
    Column {
        if (!isUser && isGenerating && content == "...") {
            AnimatedEllipsis(color = textColor)
        } else if (isEditing) {
            MessageEditTextField(
                value = editedTextValue,
                textColor = textColor,
                focusRequester = focusRequester,
                bringIntoViewRequester = bringIntoViewRequester,
                onValueChange = onEditedTextValueChange,
                onSubmit = onSubmitEdit
            )
        } else if (content.isNotBlank()) {
            val markdownBlocks = rememberMarkdown(text = content, defaultColor = textColor)
            SelectionContainer {
                Column {
                    markdownBlocks.forEachIndexed { index, block ->
                        if (block.text.isNotEmpty()) {
                            if (index > 0) {
                                Spacer(Modifier.height(6.dp))
                            }
                            Text(
                                text = block.text,
                                color = textColor,
                                fontSize = 16.sp,
                                lineHeight = 22.sp,
                                modifier = if (block.isCodeBlock) {
                                    Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                                } else {
                                    Modifier
                                }
                            )
                        }
                    }
                }
            }
        }

        val reasoning = reasoningText
        if (!isUser && !reasoning.isNullOrBlank()) {
            ReasoningSection(text = reasoning, accentColor = textColor)
        }

        if (isEditing) {
            MessageImages(
                images = editedImages,
                isEditing = true,
                onRemoveImage = onRemoveEditedImage
            )
        } else {
            MessageImages(
                images = messageImages,
                pendingCount = pendingImageCount,
                topPadding = if (content.isNotBlank() && content != "...") 8.dp else 0.dp
            )
        }

        // Estimated cost of this message (heuristic, tokenizer-based).
        if (!isUser && costText != null) {
            Text(
                text = "~$costText",
                fontSize = 11.sp,
                color = textColor.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}
