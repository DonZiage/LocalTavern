package chat.donzi.localtavern.ui.chat

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.donzi.localtavern.controller.ChatController
import chat.donzi.localtavern.data.database.ApiSettingsRepository
import chat.donzi.localtavern.data.database.CharacterRepository
import chat.donzi.localtavern.data.database.LocalTavernDB
import chat.donzi.localtavern.data.database.LogicalClock
import chat.donzi.localtavern.data.database.MessageRepository
import chat.donzi.localtavern.data.database.SessionRepository
import chat.donzi.localtavern.data.network.ChatClient
import chat.donzi.localtavern.data.security.ApiKeyCipher
import chat.donzi.localtavern.data.security.TestSecretCrypto
import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.domain.Persona
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Repro: swiping a greeting past the last one (generate-new-variation at
// a root) must not spawn an LLM reply as a new root / orphan the greetings.
class GreetingSwipeReproTest {

    private val CHARACTER = Character(
        id = "charGreet", name = "GreetChar", description = null, personality = "",
        scenario = "", firstMes = "Hello!", mesExample = emptyList(), creatorNotes = null,
        altGreetings = listOf("Alt greeting one", "Alt greeting two"), avatarData = null
    )
    private val PERSONA = Persona(id = "persona1", name = "User", description = null, avatarData = null)

    private class TestDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { LocalTavernDB.Schema.create(it) }
        val database = LocalTavernDB(driver)
    }

    private data class Harness(
        val state: ChatScreenState,
        val chatController: ChatController,
        val sessionRepository: SessionRepository,
        val messageRepository: MessageRepository,
        val characterRepository: CharacterRepository,
        val activeCharacterProvider: () -> Character?,
        val activeSessionIdProvider: () -> String?,
        val onActiveCharacterChange: (Character) -> Unit,
        val onActiveSessionIdChange: (String?) -> Unit
    ) {
        val activeCharacter: Character? get() = activeCharacterProvider()
        val activeSessionId: String? get() = activeSessionIdProvider()
    }

    private suspend fun TestScope.newHarness(): Harness {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val db = TestDb()
        val characterRepository = CharacterRepository(db.database, clock = LogicalClock(db.database), ioDispatcher = testDispatcher)
        val sessionRepository = SessionRepository(db.database, ioDispatcher = testDispatcher, clock = LogicalClock(db.database))
        val messageRepository = MessageRepository(db.database, ioDispatcher = testDispatcher, clock = LogicalClock(db.database), sessionRepository = sessionRepository)
        val apiSettingsRepository = ApiSettingsRepository(db.database, ApiKeyCipher(TestSecretCrypto()), testDispatcher)
        apiSettingsRepository.insertApiConnection(
            provider = "test", name = "Test", baseUrl = "https://example.com",
            apiKey = "key", model = "model", isActive = true
        )
        val chatClient = ChatClient(
            HttpClient(
                MockEngine(
                    MockEngineConfig().apply {
                        dispatcher = testDispatcher
                        addHandler {
                            val body = "data: {\"choices\":[{\"delta\":{\"content\":\"Reply\"}}]}" + "\n\n" + "data: [DONE]" + "\n\n"
                            respond(
                                content = ByteReadChannel(body),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
                            )
                        }
                    }
                )
            ) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; isLenient = true })
                }
            }
        )
        val chatController = ChatController(
            sessionRepository = sessionRepository,
            messageRepository = messageRepository,
            apiSettingsRepository = apiSettingsRepository,
            chatClient = chatClient,
            scope = CoroutineScope(testDispatcher + SupervisorJob()),
            payloadDispatcher = testDispatcher
        )
        var activeCharacter: Character? = null
        var activeSessionId: String? = null
        val state = ChatScreenState(
            characterRepository = characterRepository,
            sessionRepository = sessionRepository,
            messageRepository = messageRepository,
            apiSettingsRepository = apiSettingsRepository,
            chatController = chatController,
            scope = CoroutineScope(testDispatcher + SupervisorJob()),
            activeSessionIdProvider = { activeSessionId },
            activeCharacterProvider = { activeCharacter },
            onActiveSessionIdChange = { activeSessionId = it },
            onActiveCharacterChange = { activeCharacter = it }
        )
        return Harness(
            state = state,
            chatController = chatController,
            sessionRepository = sessionRepository,
            messageRepository = messageRepository,
            characterRepository = characterRepository,
            activeCharacterProvider = { activeCharacter },
            activeSessionIdProvider = { activeSessionId },
            onActiveCharacterChange = { activeCharacter = it },
            onActiveSessionIdChange = { activeSessionId = it }
        )
    }

    @Test
    fun swipe_past_last_greeting_doesNotGenerateNewRoot() = runTest {
        val h = newHarness()
        val charId = h.characterRepository.createCharacter(CHARACTER.name)
        h.characterRepository.updateCharacter(
            id = charId,
            name = CHARACTER.name,
            personality = "",
            scenario = "",
            description = null,
            firstMes = CHARACTER.firstMes,
            altGreetings = CHARACTER.altGreetings
        )
        testScheduler.advanceUntilIdle()
        val char = h.characterRepository.getAllCharacters().single()
        // Sync seeds the greeting roots and points the UI at that session.
        h.state.syncActiveSession(char, PERSONA.id)
        testScheduler.advanceUntilIdle()

        val sessionId = h.activeSessionId
        assertNotNull(sessionId, "sync must seed a session")
        val beforeTimeline = h.messageRepository.getMessagesForSession(sessionId)
        assertEquals(1, beforeTimeline.size, "fresh chat shows one active greeting")
        assertEquals("Hello!", beforeTimeline.single().content)
        val beforeAll = h.messageRepository.getAllMessagesForSession(sessionId)
        assertEquals(3, beforeAll.size, "three greeting roots must be seeded")
        val lastGreeting = beforeAll.last { it.parentId == null }
        // UI swipe-left past the last greeting calls this (ChatMessageItem).
        val actions = h.state.buildChatActions(
            activePersona = PERSONA,
            messages = h.chatController.state.value.messages,
            siblingsMap = h.chatController.state.value.siblingsMap,
            isGenerating = false,
            currentSessionDetails = null,
            scope = CoroutineScope(testScheduler + SupervisorJob()),
            isDesktop = true,
            onSendMessage = { _, _ -> true },
            onManageChats = {},
            onRequestPersonaEdit = {},
            onRequestCharacterMenu = {},
            onActiveDrawerChange = {}
        )
        actions.onGenerateNewVariation(lastGreeting.id)
        testScheduler.advanceUntilIdle()

        // No new session, no LLM reply as a new root, greeting still active.
        assertEquals(sessionId, h.activeSessionId, "swiping a greeting must not switch sessions")
        assertEquals(1, h.sessionRepository.getSessionsForCharacter(char.id).size, "swiping a greeting must not create a new session")
        val afterTimeline = h.messageRepository.getMessagesForSession(sessionId)
        assertEquals(1, afterTimeline.size, "swiping past last greeting must stay on a greeting, not generate")
        assertTrue(beforeAll.any { it.content == afterTimeline.single().content }, "active message must still be a greeting, got: ${afterTimeline.single().content}")
        assertEquals(3, h.messageRepository.getAllMessagesForSession(sessionId).size, "no new root message may be inserted")
    }
}
