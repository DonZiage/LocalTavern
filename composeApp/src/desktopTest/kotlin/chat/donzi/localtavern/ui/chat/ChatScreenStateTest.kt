package chat.donzi.localtavern.ui.chat

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.donzi.localtavern.controller.ChatController
import chat.donzi.localtavern.data.database.ApiSettingsRepository
import chat.donzi.localtavern.data.database.CharacterRepository
import chat.donzi.localtavern.data.database.LocalTavernDB
import chat.donzi.localtavern.data.database.LogicalClock
import chat.donzi.localtavern.data.database.MessageRepository
import chat.donzi.localtavern.data.database.PricingRepository
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatScreenStateTest {

    private val CHARACTER_A = Character(
        id = "charA", name = "Alice", description = null, personality = "",
        scenario = "", firstMes = "Hello!", mesExample = emptyList(), creatorNotes = null,
        altGreetings = emptyList(), avatarData = null
    )
    private val CHARACTER_B = Character(
        id = "charB", name = "Bob", description = null, personality = "",
        scenario = "", firstMes = "Hi there!", mesExample = emptyList(), creatorNotes = null,
        altGreetings = emptyList(), avatarData = null
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
        val db: TestDb,
        val activeCharacterProvider: () -> Character?,
        val activeSessionIdProvider: () -> String?,
        val onActiveCharacterChange: (Character) -> Unit,
        val onActiveSessionIdChange: (String?) -> Unit
    ) {
        val activeCharacter: Character? get() = activeCharacterProvider()
        val activeSessionId: String? get() = activeSessionIdProvider()
    }

    private suspend fun TestScope.newHarness(
        streamChunks: Array<String> = arrayOf("""{"choices":[{"delta":{"content":"Reply"}}]}""", "[DONE]")
    ): Harness {
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
                            respond(
                                content = ByteReadChannel(streamChunks.joinToString("\n\n") { "data: $it" } + "\n\n"),
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
            pricingRepository = PricingRepository(db.database, testDispatcher),
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
            db = db,
            activeCharacterProvider = { activeCharacter },
            activeSessionIdProvider = { activeSessionId },
            onActiveCharacterChange = { activeCharacter = it },
            onActiveSessionIdChange = { activeSessionId = it }
        )
    }

    @Test
    fun send_refusedWithoutPersona_keepsDraftAndReportsError() = runTest {
        val h = newHarness()
        val accepted = h.state.trySendMessage(
            userMessage = "hi", imageList = emptyList(), activePersonaId = null,
            activePersona = null, activeApiConnection = null, isGenerating = false
        )
        assertFalse(accepted, "A send without a persona must be refused")
        assertNotNull(h.chatController.state.value.errorMessage)
        assertTrue(h.chatController.state.value.errorMessage!!.contains("persona"))
    }

    @Test
    fun send_refusedWithoutApiConnection_keepsDraft() = runTest {
        val h = newHarness()
        val accepted = h.state.trySendMessage(
            userMessage = "hi", imageList = emptyList(), activePersonaId = PERSONA.id,
            activePersona = PERSONA, activeApiConnection = null, isGenerating = false
        )
        assertFalse(accepted, "A send without an active API connection must be refused")
        assertTrue(h.chatController.state.value.errorMessage!!.contains("API connection"))
    }

    @Test
    fun send_accepted_commitsMessageAndStartsGeneration() = runTest {
        val h = newHarness()
        h.characterRepository.createCharacter(CHARACTER_A.name)
        val char = h.characterRepository.getAllCharacters().single()
        h.onActiveCharacterChange(char)

        val accepted = h.state.trySendMessage(
            userMessage = "Hello there", imageList = emptyList(), activePersonaId = PERSONA.id,
            activePersona = PERSONA, activeApiConnection = chat.donzi.localtavern.domain.ApiConfig(
                id = "c1", provider = "test", name = "Test", baseUrl = "https://example.com",
                apiKey = "key", model = "model", isActive = true, isChatCompletion = true,
                lastUsed = 0L, temperature = 1.0, topP = 1.0, topK = 0L,
                presencePenalty = 0.0, frequencyPenalty = 0.0, contextLimit = 4096L,
                responseLimit = 0L, displayOrder = 0L, timeoutLimit = 60L
            ),
            isGenerating = false
        )
        assertTrue(accepted)
        testScheduler.advanceUntilIdle()

        val sessionId = h.activeSessionId
        assertNotNull(sessionId, "A session must be created for the send")
        val messages = h.messageRepository.getMessagesForSession(sessionId)
        val userMsg = messages.find { it.role == "user" }
        assertNotNull(userMsg)
        assertEquals("Hello there", userMsg.content)
        assertEquals("Hello there", h.sessionRepository.getSessionById(sessionId)?.title, "First message auto-titles the session")
        assertTrue(messages.any { it.role == "assistant" && it.content == "Reply" }, "The AI response must be streamed into the session")
    }

    @Test
    fun send_secondTapWhileInFlight_isRefused() = runTest {
        val h = newHarness(streamChunks = arrayOf("""{"choices":[{"delta":{"content":"Slow"}}]}""", "[DONE]"))
        h.characterRepository.createCharacter(CHARACTER_A.name)
        val char = h.characterRepository.getAllCharacters().single()
        h.onActiveCharacterChange(char)

        val first = h.state.trySendMessage(
            userMessage = "one", imageList = emptyList(), activePersonaId = PERSONA.id,
            activePersona = PERSONA, activeApiConnection = chat.donzi.localtavern.domain.ApiConfig(
                id = "c1", provider = "test", name = "Test", baseUrl = "https://example.com",
                apiKey = "key", model = "model", isActive = true, isChatCompletion = true,
                lastUsed = 0L, temperature = 1.0, topP = 1.0, topK = 0L,
                presencePenalty = 0.0, frequencyPenalty = 0.0, contextLimit = 4096L,
                responseLimit = 0L, displayOrder = 0L, timeoutLimit = 60L
            ),
            isGenerating = false
        )
        assertTrue(first)
        // No advanceUntilIdle: the send is still in flight (sendInFlight is set
        // synchronously, before any suspend point), so a second tap must lose.
        val second = h.state.trySendMessage(
            userMessage = "two", imageList = emptyList(), activePersonaId = PERSONA.id,
            activePersona = PERSONA, activeApiConnection = chat.donzi.localtavern.domain.ApiConfig(
                id = "c1", provider = "test", name = "Test", baseUrl = "https://example.com",
                apiKey = "key", model = "model", isActive = true, isChatCompletion = true,
                lastUsed = 0L, temperature = 1.0, topP = 1.0, topK = 0L,
                presencePenalty = 0.0, frequencyPenalty = 0.0, contextLimit = 4096L,
                responseLimit = 0L, displayOrder = 0L, timeoutLimit = 60L
            ),
            isGenerating = false
        )
        assertFalse(second, "A second tap while the first send is in flight must be refused")
        testScheduler.advanceUntilIdle()
        val messages = h.messageRepository.getMessagesForSession(h.activeSessionId!!)
        assertEquals(1, messages.count { it.role == "user" }, "Only one user message may be committed")
    }

    @Test
    fun send_createsAssistantWhenNoActiveCharacter() = runTest {
        val h = newHarness()
        assertNull(h.characterRepository.getAssistant(), "No assistant before the send")

        val accepted = h.state.trySendMessage(
            userMessage = "hi", imageList = emptyList(), activePersonaId = PERSONA.id,
            activePersona = PERSONA, activeApiConnection = chat.donzi.localtavern.domain.ApiConfig(
                id = "c1", provider = "test", name = "Test", baseUrl = "https://example.com",
                apiKey = "key", model = "model", isActive = true, isChatCompletion = true,
                lastUsed = 0L, temperature = 1.0, topP = 1.0, topK = 0L,
                presencePenalty = 0.0, frequencyPenalty = 0.0, contextLimit = 4096L,
                responseLimit = 0L, displayOrder = 0L, timeoutLimit = 60L
            ),
            isGenerating = false
        )
        assertTrue(accepted)
        testScheduler.advanceUntilIdle()

        val assistant = h.characterRepository.getAssistant()
        assertNotNull(assistant, "Sending without a character must auto-create the Assistant")
        assertEquals(assistant.id, h.activeCharacter?.id, "The active character must be set to the Assistant")
        assertNotNull(h.activeSessionId)
    }

    @Test
    fun syncActiveSession_switchesAndReusesSessionsPerCharacter() = runTest {
        val h = newHarness()
        h.characterRepository.createCharacter(CHARACTER_A.name)
        h.characterRepository.createCharacter(CHARACTER_B.name)
        val charA = h.characterRepository.getAllCharacters().first { it.name == CHARACTER_A.name }
        val charB = h.characterRepository.getAllCharacters().first { it.name == CHARACTER_B.name }

        h.state.syncActiveSession(charA, PERSONA.id)
        val sessionA = h.activeSessionId
        assertNotNull(sessionA)

        h.state.syncActiveSession(charB, PERSONA.id)
        val sessionB = h.activeSessionId
        assertNotNull(sessionB)
        assertNotEquals(sessionA, sessionB, "Different characters must get different sessions")

        h.state.syncActiveSession(charA, PERSONA.id)
        assertEquals(sessionA, h.activeSessionId, "Switching back to the first character must reuse its session")
    }

    @Test
    fun syncActiveSession_clearsViewWhenNoCharacter() = runTest {
        val h = newHarness()
        h.characterRepository.createCharacter(CHARACTER_A.name)
        val charA = h.characterRepository.getAllCharacters().single()

        h.state.syncActiveSession(charA, PERSONA.id)
        assertNotNull(h.activeSessionId)

        h.state.syncActiveSession(null, null)
        assertNull(h.activeSessionId, "Clearing the view must clear the active session")
        assertFalse(h.chatController.state.value.isGenerating)
    }

    @Test
    fun selectMode_enterAndExitResetSelection() = runTest {
        val h = newHarness()
        h.state.enterSelectMode()
        assertTrue(h.state.isSelectMode)
        assertTrue(h.state.selectedMessageIds.isEmpty())

        h.state.selectedMessageIds = setOf("m1", "m2")
        h.state.exitSelectMode()
        assertFalse(h.state.isSelectMode)
        assertTrue(h.state.selectedMessageIds.isEmpty(), "Exiting select mode must clear the selection")
    }
}
