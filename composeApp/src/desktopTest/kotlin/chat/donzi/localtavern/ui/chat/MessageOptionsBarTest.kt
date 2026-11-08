package chat.donzi.localtavern.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import chat.donzi.localtavern.domain.Message
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class MessageOptionsBarTest {

    private fun sampleMessage() = Message(
        id = "m1",
        sessionId = "s1",
        role = "user",
        content = "hello",
        timestamp = 0L,
        parentId = null,
        isActivePath = true
    )

    @Composable
    private fun Bar(
        onEdit: () -> Unit = {},
        onCopy: () -> Unit = {},
        onBranch: () -> Unit = {},
        onAddImage: () -> Unit = {},
        onDelete: () -> Unit = {},
        onClose: () -> Unit = {}
    ) {
        MessageOptionsBar(
            message = sampleMessage(),
            canEdit = true,
            onEdit = onEdit,
            onCopy = onCopy,
            onBranch = onBranch,
            onAddImage = onAddImage,
            onDelete = onDelete,
            onClose = onClose
        )
    }

    @Test
    fun bar_showsExactlyFourMainActionsWithoutScrolling() = runComposeUiTest {
        setContent { Bar() }
        // The four main actions must be visible without scrolling.
        onNodeWithText("Copy").assertExists()
        onNodeWithText("Branch").assertExists()
        onNodeWithText("Edit").assertExists()
        onNodeWithText("More").assertExists()
        // The overflow actions must NOT be in the main row (they live in More).
        onNodeWithText("Add Image").assertDoesNotExist()
        onNodeWithText("Delete").assertDoesNotExist()
    }

    @Test
    fun more_deploysOverflowActionsAboveWithTextOnlyItems() = runComposeUiTest {
        var imageRequested = false
        var deleted = false
        setContent {
            Bar(
                onAddImage = { imageRequested = true },
                onDelete = { deleted = true }
            )
        }
        onNodeWithText("More").performClick()
        onNodeWithText("Add Image").assertExists()
        onNodeWithText("Delete").assertExists()
        // Overflow items are text-only: no icon content descriptions for them.
        onNodeWithContentDescription("Add Image", substring = false).assertDoesNotExist()
        onNodeWithContentDescription("Delete", substring = false).assertDoesNotExist()
        onNodeWithText("Delete").performClick()
        // The panel closes on selection; picking Delete delivers it.
        kotlin.test.assertTrue(deleted, "Delete inside More must deliver onDelete")
        onNodeWithText("Delete").assertDoesNotExist()
        kotlin.test.assertFalse(imageRequested)
    }

    @Test
    fun mainButtons_fireTheirCallbacks() = runComposeUiTest {
        var copied = false
        var branched = false
        var edited = false
        setContent {
            Bar(
                onCopy = { copied = true },
                onBranch = { branched = true },
                onEdit = { edited = true }
            )
        }
        onNodeWithText("Copy").performClick()
        onNodeWithText("Branch").performClick()
        onNodeWithText("Edit").performClick()
        kotlin.test.assertTrue(copied)
        kotlin.test.assertTrue(branched)
        kotlin.test.assertTrue(edited)
    }
}
