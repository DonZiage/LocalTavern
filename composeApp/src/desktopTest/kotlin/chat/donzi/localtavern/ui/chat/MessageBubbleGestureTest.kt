package chat.donzi.localtavern.ui.chat

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class MessageBubbleGestureTest {

    @Test
    fun tap_doesNotTriggerLongPress() = runComposeUiTest {
        var tapped = false
        var longPressed = false
        setContent {
            MessageBubble(
                content = "Hold me",
                isUser = true,
                onEdit = { _, _ -> },
                onMessageTap = { tapped = true },
                onMessageLongPress = { longPressed = true }
            )
        }
        onNodeWithText("Hold me").performClick()
        kotlin.test.assertTrue(tapped, "Tap must still fire onMessageTap (cancel/dismiss path)")
        kotlin.test.assertFalse(longPressed, "Tap must NOT open options (only holding opens them)")
    }

    @Test
    fun desktopMenu_expanded_showsAllActions() = runComposeUiTest {
        setContent {
            MessageBubble(
                content = "Menu me",
                isUser = false,
                onEdit = { _, _ -> },
                desktopMenuExpanded = true,
                onDesktopMenuChange = {}
            )
        }
        onNodeWithText("Copy").assertExists()
        onNodeWithText("Branch").assertExists()
        onNodeWithText("Edit").assertExists()
        onNodeWithText("Add Image").assertExists()
        onNodeWithText("Delete").assertExists()
    }

    @Test
    fun longPress_triggersLongPress() = runComposeUiTest {
        var longPressed = false
        setContent {
            MessageBubble(
                content = "Hold me",
                isUser = true,
                onEdit = { _, _ -> },
                onMessageTap = {},
                onMessageLongPress = { longPressed = true }
            )
        }
        onNodeWithText("Hold me").performTouchInput { longClick() }
        kotlin.test.assertTrue(longPressed, "Holding must fire onMessageLongPress")
    }
}
