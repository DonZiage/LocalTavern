package chat.donzi.localtavern.ui.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EllipsisStateTest {

    @Test
    fun placeholderShowsEllipsisWhileGenerating() {
        assertTrue(shouldShowEllipsis(isUserMessage = false, isGenerating = true, content = "..."))
    }

    @Test
    fun blankContentDuringReasoningShowsEllipsis() {
        // Reasoning-only deltas empty the visible content before the
        // first character: dots must keep animating, not stop blank.
        assertTrue(shouldShowEllipsis(isUserMessage = false, isGenerating = true, content = ""))
        assertTrue(shouldShowEllipsis(isUserMessage = false, isGenerating = true, content = "   "))
    }

    @Test
    fun firstVisibleCharacterStopsEllipsis() {
        assertFalse(shouldShowEllipsis(isUserMessage = false, isGenerating = true, content = "H"))
        assertFalse(shouldShowEllipsis(isUserMessage = false, isGenerating = true, content = "Hello"))
    }

    @Test
    fun notGeneratingNeverShowsEllipsis() {
        assertFalse(shouldShowEllipsis(isUserMessage = false, isGenerating = false, content = "..."))
        assertFalse(shouldShowEllipsis(isUserMessage = false, isGenerating = false, content = ""))
    }

    @Test
    fun userMessagesNeverShowEllipsis() {
        assertFalse(shouldShowEllipsis(isUserMessage = true, isGenerating = true, content = "..."))
    }
}
