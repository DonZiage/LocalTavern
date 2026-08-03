package chat.donzi.localtavern.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import chat.donzi.localtavern.data.database.CharacterRepository
import chat.donzi.localtavern.data.database.SessionRepository
import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.domain.Message
import chat.donzi.localtavern.ui.components.CharacterDefinitionEditor
import kotlinx.coroutines.launch

// Full-screen character editor overlay with save/delete/export/lorebook
// handling. Extracted so MainScreen keeps the session/drawer orchestration
// readable.
@Composable
internal fun CharacterEditorOverlay(
    editingCharacter: Character?,
    lastEditingCharacter: Character?,
    activeCharacter: Character?,
    activeSessionId: String?,
    messages: List<Message>,
    characterRepository: CharacterRepository,
    sessionRepository: SessionRepository,
    onSetEditingCharacter: (Character?) -> Unit,
    onSetActiveCharacter: (Character?) -> Unit,
    onExportCharacter: (Character) -> Unit,
    onRefreshMessages: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    // The onSave/onLorebookSave callbacks below are invoked from inside
    // coroutines launched with the lambda captured at composition time. A
    // close-time flush (DisposableEffect in the editor) can therefore run a
    // stale lambda that still sees a non-null editingCharacter; reading the
    // latest value through rememberUpdatedState keeps the "still editing?"
    // guard accurate and stops the editor from reopening right after close.
    val currentEditingCharacter = rememberUpdatedState(editingCharacter)

    AnimatedVisibility(visible = editingCharacter != null, enter = slideInVertically(initialOffsetY = { it }), exit = slideOutVertically(targetOffsetY = { it })) {
        lastEditingCharacter?.let { targetCharacter ->
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                key(targetCharacter.id) {
                    CharacterDefinitionEditor(
                        character = targetCharacter, onClose = { onSetEditingCharacter(null) },
                        onSave = { name, desc, personality, scenario, firstMes, mesExample, altGreetings, avatarData ->
                            coroutineScope.launch {
                                characterRepository.updateCharacter(targetCharacter.id, name, personality, scenario, desc, firstMes, mesExample, altGreetings, avatarData)
                                val freshCharacter = characterRepository.getCharacterById(targetCharacter.id)
                                if (freshCharacter != null && currentEditingCharacter.value?.id == targetCharacter.id) {
                                    onSetEditingCharacter(freshCharacter)
                                }

                                if (activeCharacter?.id == targetCharacter.id) onSetActiveCharacter(freshCharacter)

                                val greetingsChanged = targetCharacter.firstMes != firstMes || targetCharacter.altGreetings != altGreetings
                                if (greetingsChanged) {
                                    val textList = mutableListOf<String>()
                                    if (firstMes.isNotBlank()) textList.add(firstMes)
                                    altGreetings.filter { it.isNotBlank() }.forEach { textList.add(it) }

                                    // Sync the greeting roots across ALL of the
                                    // character's sessions, not just the first one.
                                    // The per-session read-modify-insert runs inside
                                    // one DB transaction so two concurrent saves
                                    // (debounced autosave + close-time flush) cannot
                                    // insert the same greeting root twice.
                                    val targetSessions = sessionRepository.getSessionsForCharacter(targetCharacter.id)
                                    targetSessions.forEach { session ->
                                        sessionRepository.syncGreetingRoots(session.id, textList)
                                    }

                                    // Only the active session's view may be re-seeded.
                                    val currentActiveSessionId = activeSessionId
                                    if (currentActiveSessionId != null && activeCharacter?.id == targetCharacter.id) {
                                        val finalRoots = sessionRepository.getMessageSiblings(currentActiveSessionId, null)
                                        if (finalRoots.isNotEmpty() && finalRoots.none { it.id == messages.firstOrNull()?.id }) {
                                            finalRoots.firstOrNull()?.let { sessionRepository.selectVariation(currentActiveSessionId, it.id, null) }
                                        }
                                    }
                                }
                                onRefreshMessages()
                            }
                        },
                        onDelete = { coroutineScope.launch { characterRepository.deleteCharacters(setOf(targetCharacter.id)); if (activeCharacter?.id == targetCharacter.id) onSetActiveCharacter(null); onSetEditingCharacter(null) } },
                        onExport = onExportCharacter,
                        onLorebookSave = { bookJson ->
                            coroutineScope.launch {
                                characterRepository.updateCharacterLorebook(targetCharacter.id, bookJson)
                                val freshCharacter = characterRepository.getCharacterById(targetCharacter.id)
                                if (freshCharacter != null && currentEditingCharacter.value?.id == targetCharacter.id) {
                                    onSetEditingCharacter(freshCharacter)
                                }
                                if (activeCharacter?.id == targetCharacter.id) onSetActiveCharacter(freshCharacter)
                            }
                        }
                    )
                }
            }
        }
    }
}
