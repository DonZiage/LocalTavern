package chat.donzi.localtavern.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.ui.characters.CharacterListSection
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SelectionBarLayoutTest {

    private val sampleCharacters = listOf(
        Character(id = "1", name = "Alice", description = null, personality = "A", scenario = "", firstMes = null, creatorNotes = null, avatarData = null),
        Character(id = "2", name = "Bob", description = null, personality = "B", scenario = "", firstMes = null, creatorNotes = null, avatarData = null)
    )

    private fun androidx.compose.ui.test.ComposeUiTest.render() = setContent {
        MaterialTheme {
            androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.width(300.dp)) {
                CharacterListSection(
                    characters = sampleCharacters,
                    onSelect = {},
                    onDeleteSelected = {},
                    onImportCharacters = {},
                    onExportSelected = {},
                    onCreateCharacter = {},
                    onEditCharacter = {},
                    onExportCharacter = {},
                    confirmBeforeDelete = false
                )
            }
        }
    }

    @Test
    fun selectionBarButtons_areHorizontallyArranged() = runComposeUiTest {
        render()
        onNodeWithText("Alice").performTouchInput { longClick() }

        val cancel = onNodeWithText("Cancel").getBoundsInRoot()
        val selectAll = onNodeWithText("Select all").getBoundsInRoot()
        val export = onNodeWithText("Export").getBoundsInRoot()
        val delete = onNodeWithText("Delete").getBoundsInRoot()

        println("CANCEL=${cancel.left.value},${cancel.top.value},${cancel.right.value - cancel.left.value}x${cancel.bottom.value - cancel.top.value}")
        println("SELECT_ALL=${selectAll.left.value},${selectAll.top.value},${selectAll.right.value - selectAll.left.value}x${selectAll.bottom.value - selectAll.top.value}")
        println("EXPORT=${export.left.value},${export.top.value},${export.right.value - export.left.value}x${export.bottom.value - export.top.value}")
        println("DELETE=${delete.left.value},${delete.top.value},${delete.right.value - delete.left.value}x${delete.bottom.value - delete.top.value}")

        assertTrue(
            abs(export.top.value - delete.top.value) < 5f,
            "Export must be on the same row as Delete (delete.top=${delete.top.value}, export.top=${export.top.value})"
        )
        assertTrue(
            abs(cancel.top.value - selectAll.top.value) < 5f,
            "Cancel must be on the same row as Select all (cancel.top=${cancel.top.value}, selectAll.top=${selectAll.top.value})"
        )
        assertTrue(
            cancel.left.value > export.left.value,
            "Cancel must sit to the right of Export (cancel.left=${cancel.left.value}, export.left=${export.left.value})"
        )
        assertTrue(
            export.bottom.value - export.top.value < 60f,
            "Export must not wrap its label vertically (height=${export.bottom.value - export.top.value}dp)"
        )
        assertTrue(
            delete.right.value <= 300f,
            "Delete must be visible inside the panel (right=${delete.right.value})"
        )
    }
}
