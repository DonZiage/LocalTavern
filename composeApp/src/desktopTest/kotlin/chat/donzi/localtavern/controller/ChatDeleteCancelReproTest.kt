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
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatDeleteCancelReproTest {

    private class TestDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { LocalTavernDB.Schema.create(it) }
        val database = LocalTavernDB(driver)
    }

    private companion object {
        val CHARACTER = Character(
            id = "char1", name = "Alice", description = "A curious explorer",
            personality = "Brave", scenario = "Deep forest", firstMes = "Hello!",
            mesExample = emptyList(), creatorNotes = null, altGreetings = emptyList(), avatarData = null
        )
        val PERSONA = Persona(id = "persona1", name = "Bob", description = "Scholar", avatarData = null)
    }

    private fun newRepos(db: TestDb, dispatcher: kotlinx.coroutines.CoroutineDispatcher): Triple<SessionRepository, MessageRepository, ApiSettingsRepository> {
        val sessionRepository = SessionRepository(db.database, dispatcher)
        val messageRepository = MessageRepository(db.database, dispatcher, sessionRepository = sessionRepository)
        val apiRepo = ApiSettingsRepository(db.database, chat.donzi.localtavern.data.security.ApiKeyCipher(chat.donzi.localtavern.data.security.TestSecretCrypto()), dispatcher)
        return Triple(sessionRepository, messageRepository, apiRepo)
    }

    /**
     * #4: cancelling a new variation must restore the previous swipe,
     * not delete the whole turn. Deleting the in-flight placeholder
     * (new sibling) must re-activate the previous sibling.
     */
    @Test
    fun cancelVariation_restoresPreviousSwipe() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val db = TestDb()
        val (sessionRepository, messageRepository, _) = newRepos(db, testDispatcher)

        val sessionId = sessionRepository.createNewSession(CHARACTER.id, PERSONA.id)
        val greetingId = messageRepository.insertMessageRaw(sessionId, "assistant", "Hello!", null, true)
        sessionRepository.updateSessionCurrentMessage(sessionId, greetingId)
        val userId = messageRepository.insertMessage(sessionId, "user", "Hi", greetingId)
        val replyA = messageRepository.insertMessage(sessionId, "assistant", "Reply A", userId)
        // Simulate generating a new variation: inserting new sibling
        // deactivates replyA and makes the placeholder active.
        val placeholderId = messageRepository.insertMessage(sessionId, "assistant", "...", userId)
        assertEquals(placeholderId, sessionRepository.getSessionById(sessionId)?.currentMessageId)

        // Cancel: delete the empty placeholder the way GenerationRunner does.
        messageRepository.deleteMessage(placeholderId, restoreSibling = true)
        testScheduler.advanceUntilIdle()

        val timeline = messageRepository.getMessagesForSession(sessionId)
        assertEquals(listOf(greetingId, userId, replyA), timeline.map { it.id },
            "Cancelling a variation must restore the previous swipe, not erase the whole turn")
        assertEquals(replyA, sessionRepository.getSessionById(sessionId)?.currentMessageId)
    }

    /**
     * #9: select-mode (delete mode) bulk delete must purge the deleted
     * messages including their inactive siblings, so a later regenerate
     * does not resurrect the deleted turn as a swipe.
     */
    @Test
    fun deleteMessagesRaw_purgesSiblingsSoRegenerateDoesNotResurrect() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val db = TestDb()
        val (sessionRepository, messageRepository, apiRepo) = newRepos(db, testDispatcher)
        apiRepo.insertApiConnection(
            provider = "test", name = "Test", baseUrl = "https://example.com",
            apiKey = "key", model = "model", isActive = true
        )

        val controller = ChatController(
            sessionRepository, messageRepository, apiRepo,
            ChatClient(
                HttpClient(
                    MockEngine(
                        MockEngineConfig().apply {
                            dispatcher = testDispatcher
                            addHandler {
                                respond(
                                    content = ByteReadChannel("data: {\"choices\":[{\"delta\":{\"content\":\"New reply\"}}]}\n\ndata: [DONE]\n\n"),
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
            ),
            CoroutineScope(testDispatcher + SupervisorJob()),
            payloadDispatcher = testDispatcher
        )

        val sessionId = sessionRepository.createNewSession(CHARACTER.id, PERSONA.id)
        val greetingId = messageRepository.insertMessageRaw(sessionId, "assistant", "Hello!", null, true)
        sessionRepository.updateSessionCurrentMessage(sessionId, greetingId)
        val userId = messageRepository.insertMessage(sessionId, "user", "Hi", greetingId)
        val replyA = messageRepository.insertMessage(sessionId, "assistant", "Reply A", userId)
        val replyB = messageRepository.insertMessageRaw(sessionId, "assistant", "Reply B", userId, false)

        controller.refresh(sessionId)
        testScheduler.advanceUntilIdle()

        // Delete mode: select the active reply and bulk-delete it.
        controller.deleteMessagesRaw(sessionId, listOf(replyA))
        testScheduler.advanceUntilIdle()

        val allAfterDelete = messageRepository.getAllMessagesForSession(sessionId)
        assertTrue(allAfterDelete.none { it.id == replyA }, "Deleted message must be gone from DB read model")
        assertTrue(allAfterDelete.none { it.id == replyB }, "Inactive sibling of a deleted message must also be purged")

        // Regenerate from the surviving tail must not bring the deleted
        // turn back as a swipe.
        controller.regenerate(sessionId, CHARACTER, PERSONA)
        testScheduler.advanceUntilIdle()

        val allAfterRegen = messageRepository.getAllMessagesForSession(sessionId)
        assertTrue(allAfterRegen.none { it.id == replyA } && allAfterRegen.none { it.id == replyB },
            "Deleted turn must not reappear after regenerate")
        assertTrue(allAfterRegen.any { it.content == "New reply" }, "Regenerate must produce a new response")
    }
}
