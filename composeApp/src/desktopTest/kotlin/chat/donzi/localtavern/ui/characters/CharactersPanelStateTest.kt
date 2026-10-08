package chat.donzi.localtavern.ui.characters

import chat.donzi.localtavern.domain.Character
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CharactersPanelStateTest {

    private val CHARACTER = Character(
        id = "c1", name = "Alice", description = null, personality = "",
        scenario = "", firstMes = null, mesExample = emptyList(), creatorNotes = null,
        altGreetings = emptyList(), avatarData = null
    )

    @Test
    fun openEditor_setsEditorAndRemembersLastTarget() = runTest {
        val state = CharactersPanelState(this)
        assertNull(state.editingCharacter)

        state.openEditor(CHARACTER)
        assertEquals(CHARACTER, state.editingCharacter)
        assertEquals(CHARACTER, state.lastEditingCharacter, "The last-edited character must be remembered")

        state.openEditor(null)
        assertNull(state.editingCharacter, "Opening no target must clear the editor")
        assertEquals(CHARACTER, state.lastEditingCharacter, "Clearing must not forget the last character")
    }

    @Test
    fun openEditor_replacesLastTargetOnNewCharacter() = runTest {
        val state = CharactersPanelState(this)
        val second = CHARACTER.copy(id = "c2", name = "Bob")

        state.openEditor(CHARACTER)
        state.openEditor(second)

        assertEquals(second, state.lastEditingCharacter, "The most recent target must win")
    }

    @Test
    fun pendingCreationName_isSetAndCleared() = runTest {
        val state = CharactersPanelState(this)
        assertNull(state.pendingCreationName)

        state.pendingCreationName = "New Character"
        assertEquals("New Character", state.pendingCreationName)

        state.pendingCreationName = null
        assertNull(state.pendingCreationName)
    }
}
