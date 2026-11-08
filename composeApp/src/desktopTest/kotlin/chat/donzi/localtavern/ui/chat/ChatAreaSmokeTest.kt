package chat.donzi.localtavern.ui.chat

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import chat.donzi.localtavern.controller.ChatUiState
import chat.donzi.localtavern.domain.Message
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class ChatAreaSmokeTest {

    private fun actions(): ChatActions = ChatActions(
        onSendMessage = { _, _ -> false },
        onEditMessage = { _, _, _ -> },
        onDeleteMessage = {},
        onDeleteMessages = {},
        onRegenerate = {},
        onSelectVariation = {},
        onGenerateNewVariation = {},
        onStopGeneration = {},
        onManageChats = {},
        onBranchMessage = {},
        onGoToParentChat = null,
        onAddImageToMessage = { _, _ -> },
        onNavigateToSettings = {},
        onNavigateToPersonas = {},
        onNavigateToCharacters = {},
        onExportChat = {}
    )

    private fun msg(id: String, role: String, content: String, parentId: String? = null): Message =
        Message(
            id = id, sessionId = "s1", role = role, content = content, timestamp = 1L,
            parentId = parentId, isActivePath = true, images = emptyList(),
            imageRefs = emptyList(), reasoningText = null, costEstimateUsd = null
        )

    @Test
    fun chatArea_withMessages_composesInput() = runComposeUiTest {
        val greeting = msg("g1", "assistant", "Hello!")
        val user = msg("u1", "user", "Hi", "g1")
        val assistant = msg("a1", "assistant", "Hello there", "u1")
        setContent {
            ChatArea(
                chatState = ChatUiState(messages = listOf(greeting, user, assistant)),
                activeCharacter = null,
                activePersonaName = "Bob",
                activePersonaAvatar = null,
                hasApiProfile = false,
                hasPersona = true,
                hasCharacter = true,
                actions = actions()
            )
        }
        onNodeWithText("Hello there").assertExists()
        onNodeWithText("Message...").assertExists()
    }
}
