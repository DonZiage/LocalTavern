package chat.donzi.localtavern.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class InputValidationTest {

    @Test
    fun displayName_rejectsBlank() {
        assertNotNull(InputValidation.validateDisplayName(""))
        assertNotNull(InputValidation.validateDisplayName("   "))
    }

    @Test
    fun displayName_acceptsNormalNames() {
        assertNull(InputValidation.validateDisplayName("Alice"))
        assertNull(InputValidation.validateDisplayName("  Alice  "))
    }

    @Test
    fun displayName_rejectsOverlong() {
        assertNotNull(InputValidation.validateDisplayName("x".repeat(InputValidation.MAX_NAME_CHARS + 1)))
        assertNull(InputValidation.validateDisplayName("x".repeat(InputValidation.MAX_NAME_CHARS)))
    }

    @Test
    fun cleanDisplayName_trimsAndCollapses() {
        assertEquals("Alice Bob", InputValidation.cleanDisplayName("  Alice   Bob  "))
        assertEquals(InputValidation.MAX_NAME_CHARS, InputValidation.cleanDisplayName("x".repeat(500)).length)
    }

    @Test
    fun chatMessage_allowsBlankButCapsLength() {
        // Blank text is valid: image-only sends carry no text.
        assertNull(InputValidation.validateChatMessage(""))
        assertNull(InputValidation.validateChatMessage("x".repeat(InputValidation.MAX_MESSAGE_CHARS)))
        assertNotNull(InputValidation.validateChatMessage("x".repeat(InputValidation.MAX_MESSAGE_CHARS + 1)))
    }
}
