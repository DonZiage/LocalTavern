package chat.donzi.localtavern.controller

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.donzi.localtavern.data.database.ApiSettingsRepository
import chat.donzi.localtavern.data.database.LocalTavernDB
import chat.donzi.localtavern.data.database.MessageRepository
import chat.donzi.localtavern.data.database.SessionRepository
import chat.donzi.localtavern.data.network.ChatClient
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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Red team: a runaway model (repetition loop, non-enforcing endpoint) must
// not grow the response without bound.
//
// The stream builders accumulated every token with no ceiling while the UI
// re-encoded the full string every 50 ms and the database persisted it every
// 250 ms: a looping model pins gigabytes in the builder, freezes the UI with
// O(n^2) re-renders, and lands a row so large sync refuses it forever (wedged
// sync + reload OOM at launch). Generation must stop at a generous ceiling
// and persist a truncated reply with a visible notice instead.
class GenerationRunawayRedTeamTest {

    private class TestDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { LocalTavernDB.Schema.create(it) }
        val database = LocalTavernDB(driver)
    }

    private val CHARACTER = Character(
        id = "char1", name = "Alice", description = "A curious explorer",
        personality = "Brave", scenario = "Deep forest", firstMes = "Hello!",
        mesExample = emptyList(), creatorNotes = null, altGreetings = emptyList(), avatarData = null
    )
    private val PERSONA = Persona(id = "persona1", name = "Bob", description = "Scholar", avatarData = null)

    @Test
    fun runawayStream_isStoppedAndTruncatedWithNotice() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val db = TestDb()
        val sessionRepository = SessionRepository(db.database, testDispatcher)
        val messageRepository = MessageRepository(db.database, testDispatcher, sessionRepository = sessionRepository)
        val apiSettingsRepository = ApiSettingsRepository(
            db.database,
            chat.donzi.localtavern.data.security.ApiKeyCipher(
                chat.donzi.localtavern.data.security.TestSecretCrypto()
            ),
            testDispatcher
        )
        apiSettingsRepository.insertApiConnection(
            provider = "test", name = "Test", baseUrl = "https://example.com",
            apiKey = "key", model = "model", isActive = true
        )
        // 700 chunks x 1 KiB: far beyond any legitimate reply.
        val chunks = List(700) { """{"choices":[{"delta":{"content":"${"x".repeat(1024)}"}}]}""" }
        val client = ChatClient(
            HttpClient(
                MockEngine(
                    MockEngineConfig().apply {
                        dispatcher = testDispatcher
                        addHandler {
                            respond(
                                content = ByteReadChannel(chunks.joinToString("\n\n") { "data: $it" } + "\n\ndata: [DONE]\n\n"),
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
        val controller = ChatController(
            sessionRepository = sessionRepository,
            messageRepository = messageRepository,
            apiSettingsRepository = apiSettingsRepository,
            chatClient = client,
            scope = CoroutineScope(testDispatcher + SupervisorJob()),
            payloadDispatcher = testDispatcher
        )
        val sessionId = sessionRepository.createNewSession(CHARACTER.id, PERSONA.id)
        val greetingId = messageRepository.insertMessageRaw(sessionId, "assistant", "Hello!", null, true)
        sessionRepository.updateSessionCurrentMessage(sessionId, greetingId)
        val userId = messageRepository.insertMessage(sessionId, "user", "Hi", greetingId)

        controller.refresh(sessionId)
        testScheduler.advanceUntilIdle()
        controller.regenerate(sessionId, CHARACTER, PERSONA)
        testScheduler.advanceUntilIdle()

        val timeline = messageRepository.getMessagesForSession(sessionId)
        val reply = timeline.lastOrNull { it.role == "assistant" && it.id != greetingId }
        assertNotNull(reply, "The runaway reply must still land (truncated), not vanish")
        assertTrue(
            reply.content.length < 700 * 1024,
            "The reply must be capped, but it held ${reply.content.length} chars"
        )
        assertTrue(
            reply.content.contains("truncat", ignoreCase = true),
            "The truncated reply must say so in its own text"
        )
        val error = controller.state.value.errorMessage
        assertTrue(
            error != null && error.contains("truncat", ignoreCase = true),
            "The UI must surface the truncation, got: $error"
        )
        // The persisted row must stay forwardable over sync.
        val stored = db.database.localTavernDBQueries.selectMessageByIdAny(reply.id).executeAsOneOrNull()
        assertNotNull(stored, "The truncated reply must be persisted")
        assertTrue((stored.content?.length ?: 0) < 700 * 1024, "The persisted row must stay bounded")
    }
}
