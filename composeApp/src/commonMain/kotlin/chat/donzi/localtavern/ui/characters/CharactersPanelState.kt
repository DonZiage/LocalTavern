package chat.donzi.localtavern.ui.characters

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.utils.CharacterManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Character-editor targets, pending-creation bookkeeping and the character
// export flows (single and batch) plus their notification state. Pure UI
// state + orchestration: testable without Compose.
@Stable
class CharactersPanelState(
    private val scope: CoroutineScope
) {
    var editingCharacter by mutableStateOf<Character?>(null)
    var lastEditingCharacter by mutableStateOf<Character?>(null)

    var showExportNotification by mutableStateOf(false)
    var exportedDir by mutableStateOf("")
    var exportedCount by mutableStateOf(1)

    // Name of a character created through the panel while the list reloads;
    // consumed by the characters LaunchedEffect in MainScreen to open the
    // freshly-created character's editor.
    var pendingCreationName by mutableStateOf<String?>(null)

    // Sets the editor target synchronously: a LaunchedEffect would only run
    // after the first frame, briefly showing the previous character's editor
    // (with its callbacks) and playing the enter animation empty on first use.
    fun openEditor(character: Character?) {
        editingCharacter = character
        if (character != null) lastEditingCharacter = character
    }

    // Builds the character-export flow handler used by the character lists. PNG
    // re-encoding can be heavy, so it runs off the main thread; the actual save
    // (file dialog) must stay on the main thread.
    fun buildExportHandler(
        isDesktop: Boolean,
        onCloseDrawer: () -> Unit,
        onExportFailed: (message: String) -> Unit
    ): (Character) -> Unit = { targetChar ->
        if (!isDesktop) onCloseDrawer()
        scope.launch {
            try {
                val (fileName, bytes) = withContext(Dispatchers.Default) {
                    CharacterManager.prepareExportBytes(targetChar)
                }
                val parentDir = CharacterManager.saveExportedFile(fileName, bytes)
                if (parentDir != null) {
                    exportedDir = parentDir
                    exportedCount = 1
                    showExportNotification = true
                } else {
                    onExportFailed("Failed to export: Could not save file")
                }
            } catch (e: Exception) {
                onExportFailed("Failed to export: ${e.message}")
            }
        }
    }

    // Mass export: every selected character is prepared (PNG embed or JSON
    // fallback) and packed into a single stored ZIP archive, then saved through
    // the same save-file path as a single export.
    fun buildBatchExportHandler(
        isDesktop: Boolean,
        onCloseDrawer: () -> Unit,
        onExportFailed: (message: String) -> Unit
    ): (List<Character>) -> Unit = { characters ->
        if (!isDesktop) onCloseDrawer()
        scope.launch {
            try {
                val count = characters.size
                val bytes = withContext(Dispatchers.Default) {
                    CharacterManager.prepareBatchExportBytes(characters)
                }
                val parentDir = CharacterManager.saveExportedFile(
                    CharacterManager.batchExportFileName(),
                    bytes
                )
                if (parentDir != null) {
                    exportedCount = count
                    exportedDir = parentDir
                    showExportNotification = true
                } else {
                    onExportFailed("Failed to export: Could not save file")
                }
            } catch (e: Exception) {
                onExportFailed("Failed to export: ${e.message}")
            }
        }
    }
}
