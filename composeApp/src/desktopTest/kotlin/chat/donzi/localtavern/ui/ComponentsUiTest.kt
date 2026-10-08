package chat.donzi.localtavern.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.input.TextFieldValue
import chat.donzi.localtavern.ui.chat.ChatInputBar
import chat.donzi.localtavern.ui.chat.MessageBubble
import chat.donzi.localtavern.ui.settings.ParameterSlider
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ParameterSliderTest {

    private fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.sliderNode() = onAllNodes(
        androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)
    )[0]

    // The slider track overflows the screen bounds in the test scene (padding
    // -10..1034px), so swipes must use explicit in-bounds coordinates.
    private fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.dragSliderTo(x: Float) = sliderNode().performTouchInput {
        swipe(start = androidx.compose.ui.geometry.Offset(20f, centerY), end = androidx.compose.ui.geometry.Offset(x, centerY), durationMillis = 300)
    }

    @Test
    fun slider_dragToEnd_commitsHighValue() = runComposeUiTest {
        var committed: Float? = null
        setContent {
            ParameterSlider(
                label = "Temperature",
                value = 0.5f,
                range = 0f..2f,
                onValueChange = { committed = it }
            )
        }
        onNodeWithText("Temperature").assertExists()
        onNodeWithText("0.5").assertExists()

        dragSliderTo(1000f)

        assertTrue(committed != null && committed!! > 1.8f, "Dragging to the right edge must commit a value near the max")
    }

    @Test
    fun slider_dragToStart_commitsLowValue() = runComposeUiTest {
        var committed: Float? = null
        setContent {
            ParameterSlider(
                label = "Temperature",
                value = 0.5f,
                range = 0f..2f,
                onValueChange = { committed = it }
            )
        }
        dragSliderTo(20f)

        assertTrue(committed != null && committed!! < 0.2f, "Dragging to the left edge must commit a value near the min")
    }
}

@OptIn(ExperimentalTestApi::class)
class ChatInputBarTest {

    @Composable
    private fun Bar(onSendMessage: (String, List<ByteArray>) -> Boolean) {
        var text by remember { mutableStateOf(TextFieldValue("")) }
        ChatInputBar(
            textValue = text,
            onTextValueChange = { text = it },
            attachedImages = emptyList(),
            onAttachedImagesChange = {},
            onSendMessage = onSendMessage,
            onRegenerate = {},
            canRegenerate = true,
            onEnterSelectMode = {},
            canDelete = false,
            onManageChats = {},
            canManageChats = true
        )
    }

    @Test
    fun send_clearsDraftWhenAccepted() = runComposeUiTest {
        var sent: String? = null
        setContent {
            Bar(onSendMessage = { text, _ -> sent = text; true })
        }
        onNodeWithContentDescription("Chat Options").assertExists()

        onAllNodes(hasSetTextAction())[0].performTextInput("Hello, world!")
        onNodeWithContentDescription("Send").performClick()

        assertEquals("Hello, world!", sent, "The typed draft must be delivered to onSendMessage")
    }

    @Test
    fun send_keepsDraftWhenRefused() = runComposeUiTest {
        var sent: String? = null
        setContent {
            Bar(onSendMessage = { text, _ -> sent = text; false })
        }
        val input = onAllNodes(hasSetTextAction())[0]
        input.performTextInput("Don't lose me")
        onNodeWithContentDescription("Send").performClick()

        assertEquals("Don't lose me", sent)
        input.assertTextEquals("Don't lose me")
    }
}

@OptIn(ExperimentalTestApi::class)
class MessageBubbleTest {

    @Test
    fun userMessage_rendersContent() = runComposeUiTest {
        setContent {
            MessageBubble(
                content = "A bold move",
                isUser = true,
                onEdit = { _, _ -> }
            )
        }
        onNodeWithText("A bold move").assertExists()
    }

    @Test
    fun assistantMessage_withReasoning_showsCollapsibleReasoning() = runComposeUiTest {
        setContent {
            MessageBubble(
                content = "Here is my answer",
                isUser = false,
                onEdit = { _, _ -> },
                reasoningText = "Let me think step by step"
            )
        }
        onNodeWithText("Here is my answer").assertExists()
        // The reasoning block is a collapsible section; expanding it reveals
        // the chain of thought.
        onNodeWithContentDescription("Expand reasoning").performClick()
        onNodeWithText("Let me think step by step").assertExists()
    }
}
