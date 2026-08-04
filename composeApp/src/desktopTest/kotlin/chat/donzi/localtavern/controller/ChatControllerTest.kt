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
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatControllerTest {

    private suspend fun TestScope.newController(vararg streamChunks: String): Pair<ChatController, TestDb> {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val db = TestDb()
        val (sessionRepository, messageRepository) = newRepos(db, testDispatcher)
        val apiSettingsRepository = ApiSettingsRepository(db.database, chat.donzi.localtavern.data.security.ApiKeyCipher(chat.donzi.localtavern.data.security.TestSecretCrypto()), testDispatcher)
        apiSettingsRepository.insertApiConnection(
            provider = "test", name = "Test", baseUrl = "https://example.com",
            apiKey = "key", model = "model", isActive = true
        )
        val controller = ChatController(
            sessionRepository = sessionRepository,
            messageRepository = messageRepository,
            apiSettingsRepository = apiSettingsRepository,
            chatClient = ChatClient(
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
                        json(Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        })
                    }
                }
            ),
            scope = CoroutineScope(testDispatcher + SupervisorJob()),
            payloadDispatcher = testDispatcher
        )
        return controller to db
    }

    // Like newController, but with a fully custom MockEngine handler (timing,
    // multi-chunk bodies, per-request behavior) and configurable connection
    // model/timeout.
    private suspend fun TestScope.newControllerWithEngine(
        model: String = "model",
        timeoutLimit: Long = 60L,
        handler: MockRequestHandler
    ): Pair<ChatController, TestDb> {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val db = TestDb()
        val (sessionRepository, messageRepository) = newRepos(db, testDispatcher)
        val apiSettingsRepository = ApiSettingsRepository(db.database, chat.donzi.localtavern.data.security.ApiKeyCipher(chat.donzi.localtavern.data.security.TestSecretCrypto()), testDispatcher)
        apiSettingsRepository.insertApiConnection(
            provider = "test", name = "Test", baseUrl = "https://example.com",
            apiKey = "key", model = model, isActive = true, timeoutLimit = timeoutLimit
        )
        val controller = ChatController(
            sessionRepository = sessionRepository,
            messageRepository = messageRepository,
            apiSettingsRepository = apiSettingsRepository,
            chatClient = ChatClient(
                HttpClient(
                    MockEngine(
                        MockEngineConfig().apply {
                            dispatcher = testDispatcher
                            addHandler(handler)
                        }
                    )
                ) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true; isLenient = true })
                    }
                }
            ),
            scope = CoroutineScope(testDispatcher + SupervisorJob()),
            payloadDispatcher = testDispatcher
        )
        return controller to db
    }

    private suspend fun TestScope.seedSession(db: TestDb): Seed {
        val (sessionRepository, messageRepository) = newRepos(db, StandardTestDispatcher(testScheduler))
        val sessionId = sessionRepository.createNewSession(CHARACTER.id, PERSONA.id)
        val greetingId = messageRepository.insertMessageRaw(sessionId, "assistant", "Hello!", null, true)
        sessionRepository.updateSessionCurrentMessage(sessionId, greetingId)
        val userId = messageRepository.insertMessage(sessionId, "user", "Hi", greetingId)
        val assistantId = messageRepository.insertMessage(sessionId, "assistant", "Old reply", userId)
        return Seed(sessionRepository, messageRepository, sessionId, greetingId, userId, assistantId)
    }

    private data class Seed(
        val sessionRepository: SessionRepository,
        val messageRepository: MessageRepository,
        val sessionId: String,
        val greetingId: String,
        val userId: String,
        val assistantId: String
    )

    private companion object {
        val CHARACTER = Character(
            id = "char1", name = "Alice", description = "A curious explorer",
            personality = "Brave", scenario = "Deep forest", firstMes = "Hello!",
            mesExample = emptyList(), creatorNotes = null, altGreetings = emptyList(), avatarData = null
        )
        val PERSONA = Persona(id = "persona1", name = "Bob", description = "Scholar", avatarData = null)
    }

    private class TestDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { LocalTavernDB.Schema.create(it) }
        val database = LocalTavernDB(driver)
    }

    private data class Repos(val session: SessionRepository, val message: MessageRepository)

    private fun newRepos(db: TestDb, dispatcher: CoroutineDispatcher): Repos {
        val sessionRepository = SessionRepository(db.database, dispatcher)
        return Repos(sessionRepository, MessageRepository(db.database, dispatcher, sessionRepository = sessionRepository))
    }

    @Test
    fun streamChatRequest_deliversTokens() = runTest {
        val client = ChatClient(
            HttpClient(MockEngine {
                respond(
                    content = ByteReadChannel("""data: {"choices":[{"delta":{"content":"New reply"}}]}

data: [DONE]
"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
                )
            }) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; isLenient = true })
                }
            }
        )
        val tokens = mutableListOf<String>()
        client.streamChatRequest(
            baseUrl = "https://example.com", apiKey = "k", model = "m",
            messages = listOf(chat.donzi.localtavern.utils.ChatMessage(role = "user", content = "hi"))
        ).collect { chunk -> chunk.content?.let { tokens.add(it) } }
        assertEquals(listOf("New reply"), tokens)
    }

    @Test
    fun regenerate_attachesNewResponseToLastUserMessage() = runTest {
        val (controller, db) = newController("""{"choices":[{"delta":{"content":"New reply"}}]}""", "[DONE]")
        val seed = seedSession(db)
        val (sessionRepository, messageRepository) = seed.sessionRepository to seed.messageRepository

        controller.refresh(seed.sessionId)
        testScheduler.advanceUntilIdle()

        controller.regenerate(seed.sessionId, CHARACTER, PERSONA)
        testScheduler.advanceUntilIdle()

        val timeline = messageRepository.getMessagesForSession(seed.sessionId)
        assertEquals(3, timeline.size)
        assertTrue(timeline.none { it.id == seed.assistantId }, "Old assistant message should be deleted")
        val newAssistant = timeline.last()
        assertEquals("assistant", newAssistant.role)
        assertNotEquals(seed.assistantId, newAssistant.id)
        assertEquals("New reply", newAssistant.content)
        assertEquals(seed.userId, newAssistant.parentId, "New response must be attached to the last user message")
        assertEquals(newAssistant.id, sessionRepository.getSessionById(seed.sessionId)?.currentMessageId)
    }

    @Test
    fun streamChatRequest_deliversReasoningContent() = runTest {
        val client = ChatClient(
            HttpClient(MockEngine {
                respond(
                    content = ByteReadChannel("""data: {"choices":[{"delta":{"reasoning_content":"Hmm, let me think"}}]}

data: {"choices":[{"delta":{"content":"Answer"}}]}

data: [DONE]
"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
                )
            }) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; isLenient = true })
                }
            }
        )
        val contents = mutableListOf<String?>()
        val reasonings = mutableListOf<String?>()
        client.streamChatRequest(
            baseUrl = "https://example.com", apiKey = "k", model = "deepseek-reasoner",
            messages = listOf(chat.donzi.localtavern.utils.ChatMessage(role = "user", content = "hi"))
        ).collect { chunk ->
            contents.add(chunk.content)
            reasonings.add(chunk.reasoning)
        }
        assertEquals(listOf(null, "Answer"), contents)
        assertEquals(listOf("Hmm, let me think", null), reasonings)
    }

    @Test
    fun anthropicStream_deliversThinkingDeltas() = runTest {
        val client = ChatClient(
            HttpClient(MockEngine {
                respond(
                    content = ByteReadChannel("""data: {"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"Consider the user"}}

data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"Hi"}}

data: {"type":"message_stop"}
"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
                )
            }) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; isLenient = true })
                }
            }
        )
        val contents = mutableListOf<String?>()
        val reasonings = mutableListOf<String?>()
        client.streamChatRequest(
            baseUrl = "https://api.anthropic.com/v1", apiKey = "k", model = "claude-3-7-sonnet",
            messages = listOf(chat.donzi.localtavern.utils.ChatMessage(role = "user", content = "hi")),
            params = chat.donzi.localtavern.data.network.GenerationParams(thinkingBudgetTokens = 2048),
            provider = "Anthropic"
        ).collect { chunk ->
            contents.add(chunk.content)
            reasonings.add(chunk.reasoning)
        }
        assertEquals(listOf(null, "Hi"), contents)
        assertEquals(listOf("Consider the user", null), reasonings)
    }

    @Test
    fun anthropicThinkingRequest_includesThinkingBlockAndBetaHeader() = runTest {
        var capturedBody = ""
        var capturedBetaHeader = ""
        val client = ChatClient(
            HttpClient(MockEngine { request ->
                capturedBody = (request.body as TextContent).text
                capturedBetaHeader = request.headers["anthropic-beta"] ?: ""
                respond(
                    content = ByteReadChannel("""data: {"type":"message_stop"}"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
                )
            }) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; isLenient = true })
                }
            }
        )
        client.streamChatRequest(
            baseUrl = "https://api.anthropic.com/v1", apiKey = "k", model = "claude-3-7-sonnet",
            messages = listOf(chat.donzi.localtavern.utils.ChatMessage(role = "user", content = "hi")),
            params = chat.donzi.localtavern.data.network.GenerationParams(thinkingBudgetTokens = 2048),
            provider = "Anthropic"
        ).collect { }

        val body = Json.parseToJsonElement(capturedBody).jsonObject
        val thinking = body["thinking"]!!.jsonObject
        assertEquals("enabled", thinking["type"]?.jsonPrimitive?.content)
        assertEquals(2048L, thinking["budget_tokens"]?.jsonPrimitive?.content?.toLong())
        assertEquals("extended-thinking-2025-02-19", capturedBetaHeader)
    }

    @Test
    fun reasoningEffort_sentForOSeriesModels() = runTest {
        var capturedBody = ""
        val client = ChatClient(
            HttpClient(MockEngine { request ->
                capturedBody = (request.body as TextContent).text
                respond(
                    content = ByteReadChannel("""data: {"choices":[{"delta":{"content":"hi"}}]}

data: [DONE]
"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
                )
            }) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; isLenient = true })
                }
            }
        )
        client.streamChatRequest(
            baseUrl = "https://example.com", apiKey = "k", model = "o1",
            messages = listOf(chat.donzi.localtavern.utils.ChatMessage(role = "user", content = "hi")),
            params = chat.donzi.localtavern.data.network.GenerationParams(reasoningEffort = "medium")
        ).collect { }

        val body = Json.parseToJsonElement(capturedBody).jsonObject
        assertEquals("medium", body["reasoning_effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun emptyStreamResponse_deletesPlaceholderMessage() = runTest {
        val (controller, db) = newController("[DONE]")
        val seed = seedSession(db)
        val (sessionRepository, messageRepository) = seed.sessionRepository to seed.messageRepository

        controller.refresh(seed.sessionId)
        testScheduler.advanceUntilIdle()

        controller.requestAiResponse(seed.sessionId, CHARACTER, PERSONA, seed.userId)
        testScheduler.advanceUntilIdle()

        val timeline = messageRepository.getMessagesForSession(seed.sessionId)
        assertEquals(2, timeline.size, "Empty response placeholder must be removed")
        assertEquals(seed.userId, sessionRepository.getSessionById(seed.sessionId)?.currentMessageId)
    }

    @Test
    fun deadStream_surfacesErrorInsteadOfSilentlyDropping() = runTest {
        // A 200 with an SSE content type but zero data lines must raise an
        // error, not silently remove the placeholder.
        val (controller, db) = newController()
        val seed = seedSession(db)
        val (sessionRepository, messageRepository) = seed.sessionRepository to seed.messageRepository

        controller.refresh(seed.sessionId)
        testScheduler.advanceUntilIdle()

        controller.requestAiResponse(seed.sessionId, CHARACTER, PERSONA, seed.userId)
        testScheduler.advanceUntilIdle()

        val timeline = messageRepository.getMessagesForSession(seed.sessionId)
        assertEquals(2, timeline.size, "Placeholder must be removed on a dead stream")
        assertEquals("Empty response from API.", controller.state.value.errorMessage)
        assertFalse(controller.state.value.errorIsWarning)
    }

    @Test
    fun staleSessionLoad_doesNotOverwriteNewerView() = runTest {
        val (controller, db) = newController()
        val seed = seedSession(db)
        val otherSessionId = seed.sessionRepository.createNewSession(CHARACTER.id, PERSONA.id)

        // Session A has messages; session B is empty. A slow load of A must
        // not overwrite the view after the user switched to B.
        controller.refresh(seed.sessionId)
        controller.refresh(otherSessionId)
        testScheduler.advanceUntilIdle()

        assertEquals(0, controller.state.value.messages.size,
            "A stale load of the previous session must not overwrite the new view")
        assertEquals(otherSessionId, controller.state.value.currentSession?.id)

        controller.refresh(seed.sessionId)
        testScheduler.advanceUntilIdle()
        assertEquals(3, controller.state.value.messages.size)
    }

    @Test
    fun insertApiConnection_derivesActiveFlagAndOrderFromDb() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val db = TestDb()
        val apiSettingsRepository = ApiSettingsRepository(db.database, chat.donzi.localtavern.data.security.ApiKeyCipher(chat.donzi.localtavern.data.security.TestSecretCrypto()), testDispatcher)

        val firstId = apiSettingsRepository.insertApiConnection(
            provider = "a", name = "A", baseUrl = "https://a.example", apiKey = "k", model = "m"
        )
        testScheduler.advanceUntilIdle()
        val afterFirst = apiSettingsRepository.getAllApiConnections()
        assertEquals(1, afterFirst.size)
        assertTrue(afterFirst.first().isActive, "First connection must be auto-activated")
        assertEquals(0L, afterFirst.first().displayOrder)

        val secondId = apiSettingsRepository.insertApiConnection(
            provider = "b", name = "B", baseUrl = "https://b.example", apiKey = "k", model = "m"
        )
        testScheduler.advanceUntilIdle()
        val afterSecond = apiSettingsRepository.getAllApiConnections()
        assertEquals(2, afterSecond.size)
        assertFalse(afterSecond.first { it.id == secondId }.isActive,
            "A new connection must not steal the active flag from an existing one")
        assertTrue(afterSecond.first { it.id == firstId }.isActive)
        assertEquals(1L, afterSecond.first { it.id == secondId }.displayOrder,
            "displayOrder must not collide with an existing connection")
    }

    @Test
    fun deletingCurrentMessage_movesCurrentToParent() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val db = TestDb()
        val (sessionRepository, messageRepository) = newRepos(db, testDispatcher)

        val sessionId = sessionRepository.createNewSession(CHARACTER.id, PERSONA.id)
        val greetingId = messageRepository.insertMessageRaw(sessionId, "assistant", "Hello!", null, true)
        sessionRepository.updateSessionCurrentMessage(sessionId, greetingId)
        val userId = messageRepository.insertMessage(sessionId, "user", "Hi", greetingId)

        assertEquals(userId, sessionRepository.getSessionById(sessionId)?.currentMessageId)
        messageRepository.deleteMessage(userId)
        testScheduler.advanceUntilIdle()

        assertEquals(greetingId, sessionRepository.getSessionById(sessionId)?.currentMessageId, "currentMessageId must not point to a deleted message")
        val timeline = messageRepository.getMessagesForSession(sessionId)
        assertEquals(1, timeline.size)
        assertEquals(greetingId, timeline.first().id)
    }

    @Test
    fun deleteMessage_doesNotMoveCurrentWhenDeletingNonCurrentMessage() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val db = TestDb()
        val (sessionRepository, messageRepository) = newRepos(db, testDispatcher)

        val sessionId = sessionRepository.createNewSession(CHARACTER.id, PERSONA.id)
        val greetingId = messageRepository.insertMessageRaw(sessionId, "assistant", "Hello!", null, true)
        val greeting2Id = messageRepository.insertMessageRaw(sessionId, "assistant", "Alt greeting", null, false)
        sessionRepository.updateSessionCurrentMessage(sessionId, greetingId)
        val userId = messageRepository.insertMessage(sessionId, "user", "Hi", greetingId)

        messageRepository.deleteMessage(greeting2Id)
        testScheduler.advanceUntilIdle()

        val session = sessionRepository.getSessionById(sessionId)
        assertNotNull(session)
        assertEquals(userId, session.currentMessageId, "Deleting a non-current message must not touch currentMessageId")
        assertEquals(1, messageRepository.getMessageSiblings(sessionId, null).size)
    }

    @Test
    fun deleteMessage_deactivatesDescendantsAndRepointsCurrent() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val db = TestDb()
        val (sessionRepository, messageRepository) = newRepos(db, testDispatcher)

        val sessionId = sessionRepository.createNewSession(CHARACTER.id, PERSONA.id)
        val greetingId = messageRepository.insertMessageRaw(sessionId, "assistant", "Hello!", null, true)
        sessionRepository.updateSessionCurrentMessage(sessionId, greetingId)
        val userId = messageRepository.insertMessage(sessionId, "user", "Hi", greetingId)
        val replyId = messageRepository.insertMessage(sessionId, "assistant", "Reply", userId)
        val followUpId = messageRepository.insertMessage(sessionId, "user", "Follow-up", replyId)

        messageRepository.deleteMessage(replyId)
        testScheduler.advanceUntilIdle()

        val timeline = messageRepository.getMessagesForSession(sessionId)
        assertEquals(listOf(greetingId, userId), timeline.map { it.id },
            "Descendants of a deleted message must be deactivated")
        assertEquals(userId, sessionRepository.getSessionById(sessionId)?.currentMessageId,
            "currentMessageId must move to the deleted message's parent")
    }

    @Test
    fun deleteRootGreeting_doesNotReseedGreetings() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val db = TestDb()
        val (sessionRepository, messageRepository) = newRepos(db, testDispatcher)

        val sessionId = sessionRepository.createNewSession(CHARACTER.id, PERSONA.id)
        val greetingId = messageRepository.insertMessageRaw(sessionId, "assistant", "Hello!", null, true)
        sessionRepository.updateSessionCurrentMessage(sessionId, greetingId)

        messageRepository.deleteMessage(greetingId)
        messageRepository.ensureInitialGreetings(sessionId, CHARACTER)
        testScheduler.advanceUntilIdle()

        assertTrue(messageRepository.getMessagesForSession(sessionId).isEmpty(),
            "Deleting the root greeting must not trigger greeting re-seeding")
        assertEquals(null, sessionRepository.getSessionById(sessionId)?.currentMessageId)
    }

    @Test
    fun selectVariation_deactivatesDescendantsOfDeselectedBranch() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val db = TestDb()
        val (sessionRepository, messageRepository) = newRepos(db, testDispatcher)

        val sessionId = sessionRepository.createNewSession(CHARACTER.id, PERSONA.id)
        val greetingId = messageRepository.insertMessageRaw(sessionId, "assistant", "Hello!", null, true)
        sessionRepository.updateSessionCurrentMessage(sessionId, greetingId)
        val userId = messageRepository.insertMessage(sessionId, "user", "Hi", greetingId)
        val replyA = messageRepository.insertMessage(sessionId, "assistant", "Reply A", userId)
        val followUp = messageRepository.insertMessage(sessionId, "user", "Follow-up", replyA)
        val replyB = messageRepository.insertMessageRaw(sessionId, "assistant", "Reply B", userId, false)

        messageRepository.selectVariation(sessionId, replyB, userId)
        testScheduler.advanceUntilIdle()

        val timeline = messageRepository.getMessagesForSession(sessionId)
        assertEquals(listOf(greetingId, userId, replyB), timeline.map { it.id },
            "Only the selected branch must remain active")
        assertEquals(replyB, sessionRepository.getSessionById(sessionId)?.currentMessageId)
    }

    @Test
    fun deleteMessagesRaw_keepsTimelineConsistent() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val db = TestDb()
        val (sessionRepository, messageRepository) = newRepos(db, testDispatcher)
        val apiSettingsRepository = ApiSettingsRepository(db.database, chat.donzi.localtavern.data.security.ApiKeyCipher(chat.donzi.localtavern.data.security.TestSecretCrypto()), testDispatcher)
        apiSettingsRepository.insertApiConnection(
            provider = "test", name = "Test", baseUrl = "https://example.com",
            apiKey = "key", model = "model", isActive = true
        )
        val controller = ChatController(
            sessionRepository, messageRepository, apiSettingsRepository,
            ChatClient(HttpClient(MockEngine { respond(
                content = ByteReadChannel("data: [DONE]"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            ) })),
            CoroutineScope(testDispatcher + SupervisorJob()),
            payloadDispatcher = testDispatcher
        )

        val sessionId = sessionRepository.createNewSession(CHARACTER.id, PERSONA.id)
        val greetingId = messageRepository.insertMessageRaw(sessionId, "assistant", "Hello!", null, true)
        sessionRepository.updateSessionCurrentMessage(sessionId, greetingId)
        val u1 = messageRepository.insertMessage(sessionId, "user", "Hi", greetingId)
        val a1 = messageRepository.insertMessage(sessionId, "assistant", "Reply A", u1)
        val u2 = messageRepository.insertMessage(sessionId, "user", "Follow-up", a1)
        val a2 = messageRepository.insertMessage(sessionId, "assistant", "Reply B", u2)

        controller.refresh(sessionId)
        testScheduler.advanceUntilIdle()

        val allSuffix = listOf(u1, a1, u2, a2)
        controller.deleteMessagesRaw(sessionId, allSuffix.shuffled(Random(42)))
        testScheduler.advanceUntilIdle()

        val timeline = messageRepository.getMessagesForSession(sessionId)
        assertEquals(listOf(greetingId), timeline.map { it.id },
            "Deleting the whole suffix must leave only the greeting active")
        assertEquals(greetingId, sessionRepository.getSessionById(sessionId)?.currentMessageId,
            "currentMessageId must never dangle on a deleted message")
    }

    @Test
    fun deleteMessagesRaw_deletingTailRepointsCurrentToLastRemaining() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val db = TestDb()
        val (sessionRepository, messageRepository) = newRepos(db, testDispatcher)
        val controller = ChatController(
            sessionRepository, messageRepository,
            ApiSettingsRepository(db.database, chat.donzi.localtavern.data.security.ApiKeyCipher(chat.donzi.localtavern.data.security.TestSecretCrypto()), testDispatcher),
            ChatClient(HttpClient(MockEngine { respond(
                content = ByteReadChannel("data: [DONE]"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            ) })),
            CoroutineScope(testDispatcher + SupervisorJob()),
            payloadDispatcher = testDispatcher
        )

        val sessionId = sessionRepository.createNewSession(CHARACTER.id, PERSONA.id)
        val greetingId = messageRepository.insertMessageRaw(sessionId, "assistant", "Hello!", null, true)
        sessionRepository.updateSessionCurrentMessage(sessionId, greetingId)
        val u1 = messageRepository.insertMessage(sessionId, "user", "Hi", greetingId)
        val a1 = messageRepository.insertMessage(sessionId, "assistant", "Reply A", u1)
        val u2 = messageRepository.insertMessage(sessionId, "user", "Follow-up", a1)
        val a2 = messageRepository.insertMessage(sessionId, "assistant", "Reply B", u2)

        controller.refresh(sessionId)
        testScheduler.advanceUntilIdle()

        // Delete only the tail; the session must still point at the last
        // surviving message so the next reply is not parented to a deleted id.
        controller.deleteMessagesRaw(sessionId, listOf(u2, a2))
        testScheduler.advanceUntilIdle()

        val timeline = messageRepository.getMessagesForSession(sessionId)
        assertEquals(listOf(greetingId, u1, a1), timeline.map { it.id })
        assertEquals(a1, sessionRepository.getSessionById(sessionId)?.currentMessageId)

        // The next user message must attach to the surviving tail.
        val u3 = messageRepository.insertMessage(sessionId, "user", "Again", a1)
        val session = sessionRepository.getSessionById(sessionId)
        assertEquals(u3, session?.currentMessageId)
        assertEquals(4, messageRepository.getMessagesForSession(sessionId).size)
    }

    @Test
    fun generationCompletion_doesNotOverwriteViewOfAnotherSession() = runTest {
        val (controller, db) = newController("""{"choices":[{"delta":{"content":"Done"}}]}""", "[DONE]")
        val seed = seedSession(db)
        val (sessionRepository, messageRepository) = seed.sessionRepository to seed.messageRepository
        val otherSessionId = sessionRepository.createNewSession(CHARACTER.id, PERSONA.id)

        controller.refresh(seed.sessionId)
        testScheduler.advanceUntilIdle()

        // Start generation in session A, then switch the view to session B before it completes.
        controller.requestAiResponse(seed.sessionId, CHARACTER, PERSONA, seed.userId)
        controller.refresh(otherSessionId)
        testScheduler.advanceUntilIdle()

        // The response must be persisted in A's session...
        val sessionATimeline = messageRepository.getMessagesForSession(seed.sessionId)
        assertEquals("Done", sessionATimeline.last().content)

        // ...but the UI must still be showing B (empty session), not A.
        assertEquals(0, controller.state.value.messages.size,
            "Generation completion must not overwrite the currently viewed session")
        assertEquals(otherSessionId, controller.state.value.currentSession?.id)

        // Returning to session A shows the finished response.
        controller.refresh(seed.sessionId)
        testScheduler.advanceUntilIdle()
        assertEquals("Done", controller.state.value.messages.last().content)
    }

    @Test
    fun anthropicStream_usesNativeEndpointAndAuth() = runTest {
        var capturedUrl = ""
        var capturedKeyHeader = ""
        var capturedVersionHeader = ""
        var capturedBody = ""
        val client = ChatClient(
            HttpClient(MockEngine { request ->
                capturedUrl = request.url.toString()
                capturedKeyHeader = request.headers["x-api-key"] ?: ""
                capturedVersionHeader = request.headers["anthropic-version"] ?: ""
                capturedBody = (request.body as TextContent).text
                respond(
                    content = ByteReadChannel("""
data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"Hello"}}

data: {"type":"content_block_delta","delta":{"type":"text_delta","text":" world"}}

data: {"type":"message_stop"}
""".trimIndent()),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
                )
            }) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; isLenient = true })
                }
            }
        )
        val tokens = mutableListOf<String>()
        client.streamChatRequest(
            baseUrl = "https://api.anthropic.com/v1", apiKey = "sk-ant-key", model = "claude-x",
            messages = listOf(
                chat.donzi.localtavern.utils.ChatMessage(role = "system", content = "You are a pirate"),
                chat.donzi.localtavern.utils.ChatMessage(role = "user", content = "Hi")
            ),
            provider = "Anthropic"
        ).collect { chunk -> chunk.content?.let { tokens.add(it) } }

        assertEquals("https://api.anthropic.com/v1/messages", capturedUrl)
        assertEquals("sk-ant-key", capturedKeyHeader)
        assertEquals("2023-06-01", capturedVersionHeader)
        assertEquals(listOf("Hello", " world"), tokens)

        val body = Json.parseToJsonElement(capturedBody).jsonObject
        assertEquals("claude-x", body["model"]?.jsonPrimitive?.content)
        assertEquals("You are a pirate", body["system"]?.jsonPrimitive?.content)
        assertEquals(8192L, body["max_tokens"]?.jsonPrimitive?.content?.toLong(),
            "Anthropic requires max_tokens; a generous default must be supplied when responseLimit is 0")
    }

    @Test
    fun anthropicBody_mergesConsecutiveSameRoleMessages() = runTest {
        var capturedBody = ""
        val client = ChatClient(
            HttpClient(MockEngine { request ->
                capturedBody = (request.body as TextContent).text
                respond(
                    content = ByteReadChannel("""data: {"type":"message_stop"}"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
                )
            }) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; isLenient = true })
                }
            }
        )
        client.streamChatRequest(
            baseUrl = "https://api.anthropic.com/v1", apiKey = "k", model = "m",
            messages = listOf(
                chat.donzi.localtavern.utils.ChatMessage(role = "user", content = "one"),
                chat.donzi.localtavern.utils.ChatMessage(role = "user", content = "two"),
                chat.donzi.localtavern.utils.ChatMessage(role = "assistant", content = "reply")
            ),
            provider = "Anthropic"
        ).collect { }

        val messages = Json.parseToJsonElement(capturedBody).jsonObject["messages"]!!.jsonArray
        assertEquals(2, messages.size, "Consecutive user messages must be merged for Anthropic")
        assertEquals("user", messages[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("one\n\ntwo", messages[0].jsonObject["content"]?.jsonPrimitive?.content)
        assertEquals("assistant", messages[1].jsonObject["role"]?.jsonPrimitive?.content)
    }

    @Test
    fun baseUrl_trailingSlashDoesNotDuplicatePathSegments() = runTest {
        var capturedUrl = ""
        val client = ChatClient(
            HttpClient(MockEngine { request ->
                capturedUrl = request.url.toString()
                respond(
                    content = ByteReadChannel("""data: {"choices":[{"delta":{"content":"hi"}}]}

data: [DONE]
"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
                )
            }) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; isLenient = true })
                }
            }
        )
        client.streamChatRequest(
            baseUrl = "http://localhost:11434/v1/", apiKey = "", model = "m",
            messages = listOf(chat.donzi.localtavern.utils.ChatMessage(role = "user", content = "hi"))
        ).collect { }

        assertEquals("http://localhost:11434/v1/chat/completions", capturedUrl)
    }

    @Test
    fun apiError_doesNotPersistErrorMessage() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val db = TestDb()
        val (sessionRepository, messageRepository) = newRepos(db, testDispatcher)
        val apiSettingsRepository = ApiSettingsRepository(db.database, chat.donzi.localtavern.data.security.ApiKeyCipher(chat.donzi.localtavern.data.security.TestSecretCrypto()), testDispatcher)
        apiSettingsRepository.insertApiConnection(
            provider = "test", name = "Test", baseUrl = "https://example.com",
            apiKey = "key", model = "model", isActive = true
        )
        val client = ChatClient(
            HttpClient(
                MockEngine(
                    MockEngineConfig().apply {
                        dispatcher = testDispatcher
                        addHandler {
                            respond(
                                content = ByteReadChannel("""{"error": {"message": "Invalid API key"}}"""),
                                status = HttpStatusCode.Unauthorized,
                                headers = headersOf(HttpHeaders.ContentType, "application/json")
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
            sessionRepository, messageRepository, apiSettingsRepository, client,
            CoroutineScope(testDispatcher + SupervisorJob()),
            payloadDispatcher = testDispatcher
        )
        val seed = seedSession(db)

        controller.refresh(seed.sessionId)
        testScheduler.advanceUntilIdle()

        controller.requestAiResponse(seed.sessionId, CHARACTER, PERSONA, seed.userId)
        testScheduler.advanceUntilIdle()

        val timeline = messageRepository.getMessagesForSession(seed.sessionId)
        assertEquals(2, timeline.size, "Placeholder must be removed on API error")
        assertTrue(timeline.none { it.content.contains("Error") }, "Error text must not be persisted as a message")
        assertEquals("API error: Invalid API key", controller.state.value.errorMessage)
    }

    @Test
    fun streamChatRequest_sendsGenerationParams() = runTest {
        var capturedBody = ""
        val client = ChatClient(
            HttpClient(MockEngine { request ->
                capturedBody = (request.body as TextContent).text
                respond(
                    content = ByteReadChannel("""data: {"choices":[{"delta":{"content":"hi"}}]}

data: [DONE]
"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
                )
            }) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; isLenient = true })
                }
            }
        )
        client.streamChatRequest(
            baseUrl = "https://example.com", apiKey = "k", model = "m",
            messages = listOf(chat.donzi.localtavern.utils.ChatMessage(role = "user", content = "hi")),
            params = chat.donzi.localtavern.data.network.GenerationParams(
                temperature = 0.7, topP = 0.9, topK = 40,
                presencePenalty = 0.1, frequencyPenalty = 0.2, maxTokens = 512
            )
        ).collect { }

        val body = Json.parseToJsonElement(capturedBody).jsonObject
        assertEquals(0.7, body["temperature"]?.jsonPrimitive?.content?.toDouble())
        assertEquals(0.9, body["top_p"]?.jsonPrimitive?.content?.toDouble())
        assertEquals(40L, body["top_k"]?.jsonPrimitive?.content?.toLong())
        assertEquals(0.1, body["presence_penalty"]?.jsonPrimitive?.content?.toDouble())
        assertEquals(0.2, body["frequency_penalty"]?.jsonPrimitive?.content?.toDouble())
        assertEquals(512L, body["max_tokens"]?.jsonPrimitive?.content?.toLong())
    }

    @Test
    fun streamChatRequest_omitsZeroMaxTokens() = runTest {
        var capturedBody = ""
        val client = ChatClient(
            HttpClient(MockEngine { request ->
                capturedBody = (request.body as TextContent).text
                respond(
                    content = ByteReadChannel("""data: {"choices":[{"delta":{"content":"hi"}}]}

data: [DONE]
"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
                )
            }) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; isLenient = true })
                }
            }
        )
        client.streamChatRequest(
            baseUrl = "https://example.com", apiKey = "k", model = "m",
            messages = listOf(chat.donzi.localtavern.utils.ChatMessage(role = "user", content = "hi")),
            params = chat.donzi.localtavern.data.network.GenerationParams()
        ).collect { }

        val body = Json.parseToJsonElement(capturedBody).jsonObject
        assertTrue(!body.containsKey("max_tokens"), "max_tokens=0 must not be sent")
        assertTrue(!body.containsKey("top_k"), "top_k=0 must not be sent")
    }

    @Test
    fun anthropicBody_prependsUserMessageWhenHistoryStartsWithAssistant() = runTest {
        var capturedBody = ""
        val client = ChatClient(
            HttpClient(MockEngine { request ->
                capturedBody = (request.body as TextContent).text
                respond(
                    content = ByteReadChannel("""data: {"type":"message_stop"}"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
                )
            }) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; isLenient = true })
                }
            }
        )
        client.streamChatRequest(
            baseUrl = "https://api.anthropic.com/v1", apiKey = "k", model = "m",
            messages = listOf(
                chat.donzi.localtavern.utils.ChatMessage(role = "system", content = "sys"),
                chat.donzi.localtavern.utils.ChatMessage(role = "assistant", content = "Hello there"),
                chat.donzi.localtavern.utils.ChatMessage(role = "user", content = "Hi")
            ),
            provider = "Anthropic"
        ).collect { }

        val messages = Json.parseToJsonElement(capturedBody).jsonObject["messages"]!!.jsonArray
        assertEquals(3, messages.size, "A leading user turn must be prepended")
        assertEquals("user", messages[0].jsonObject["role"]?.jsonPrimitive?.content)
        // The leading turn must be non-empty: Anthropic rejects blank content.
        assertEquals(" ", messages[0].jsonObject["content"]?.jsonPrimitive?.content)
        assertEquals("assistant", messages[1].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("user", messages[2].jsonObject["role"]?.jsonPrimitive?.content)
    }

    @Test
    fun anthropicBody_serializesImagesAsContentBlocks() = runTest {
        var capturedBody = ""
        val client = ChatClient(
            HttpClient(MockEngine { request ->
                capturedBody = (request.body as TextContent).text
                respond(
                    content = ByteReadChannel("""data: {"type":"message_stop"}"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
                )
            }) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; isLenient = true })
                }
            }
        )
        val image = chat.donzi.localtavern.utils.ImageAttachment(base64 = "aGVsbG8=", mimeType = "image/png")
        client.streamChatRequest(
            baseUrl = "https://api.anthropic.com/v1", apiKey = "k", model = "m",
            messages = listOf(
                chat.donzi.localtavern.utils.ChatMessage(role = "user", content = "What is this?", images = listOf(image))
            ),
            provider = "Anthropic"
        ).collect { }

        val body = Json.parseToJsonElement(capturedBody).jsonObject
        val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(2, content.size, "Text and image blocks must both be sent")
        assertEquals("text", content[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("What is this?", content[0].jsonObject["text"]?.jsonPrimitive?.content)
        assertEquals("image", content[1].jsonObject["type"]?.jsonPrimitive?.content)
        val source = content[1].jsonObject["source"]!!.jsonObject
        assertEquals("base64", source["type"]?.jsonPrimitive?.content)
        assertEquals("image/png", source["media_type"]?.jsonPrimitive?.content)
        assertEquals("aGVsbG8=", source["data"]?.jsonPrimitive?.content)
    }

    @Test
    fun regenerateDuringGeneration_restartsCleanly() = runTest {
        val (controller, db) = newController("""{"choices":[{"delta":{"content":"New reply"}}]}""", "[DONE]")
        val seed = seedSession(db)
        val (sessionRepository, messageRepository) = seed.sessionRepository to seed.messageRepository

        controller.refresh(seed.sessionId)
        testScheduler.advanceUntilIdle()

        // Start a generation, then regenerate while it is still in flight.
        // The in-flight placeholder must not survive and a fresh response
        // must be attached to the last user message.
        controller.requestAiResponse(seed.sessionId, CHARACTER, PERSONA, seed.userId)
        assertTrue(controller.state.value.isGenerating)

        controller.regenerate(seed.sessionId, CHARACTER, PERSONA)
        testScheduler.advanceUntilIdle()

        val timeline = messageRepository.getMessagesForSession(seed.sessionId)
        assertEquals(3, timeline.size, "Regenerating mid-stream must produce exactly one fresh response")
        assertTrue(timeline.none { it.id == seed.assistantId }, "Old assistant message must be deleted")
        assertEquals("New reply", timeline.last().content)
        assertEquals(seed.userId, timeline.last().parentId)
        assertFalse(controller.state.value.isGenerating)
    }

    @Test
    fun truncatedStream_withoutTerminator_emitsPartialThenThrows() = runTest {
        var requestCount = 0
        val client = ChatClient(
            HttpClient(MockEngine { request ->
                requestCount++
                respond(
                    content = ByteReadChannel("""data: {"choices":[{"delta":{"content":"Partial"}}]}

"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
                )
            }) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; isLenient = true })
                }
            }
        )
        val tokens = mutableListOf<String>()
        var thrown: Exception? = null
        try {
            client.streamChatRequest(
                baseUrl = "https://example.com", apiKey = "k", model = "m",
                messages = listOf(chat.donzi.localtavern.utils.ChatMessage(role = "user", content = "hi"))
            ).collect { chunk -> chunk.content?.let { tokens.add(it) } }
        } catch (e: Exception) {
            thrown = e
        }

        assertEquals(listOf("Partial"), tokens, "Partial tokens must still be delivered")
        assertEquals("Response stream ended before completion.", thrown?.message,
            "A stream without [DONE] must be reported as truncated")
        assertEquals(1, requestCount, "A truncated stream must not be retried inside the client")
    }

    @Test
    fun truncatedStream_recoversFullResponseReplacingPartial() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val db = TestDb()
        val (sessionRepository, messageRepository) = newRepos(db, testDispatcher)
        val apiSettingsRepository = ApiSettingsRepository(db.database, chat.donzi.localtavern.data.security.ApiKeyCipher(chat.donzi.localtavern.data.security.TestSecretCrypto()), testDispatcher)
        apiSettingsRepository.insertApiConnection(
            provider = "test", name = "Test", baseUrl = "https://example.com",
            apiKey = "key", model = "model", isActive = true
        )
        var requestCount = 0
        val client = ChatClient(
            HttpClient(
                MockEngine(
                    MockEngineConfig().apply {
                        dispatcher = testDispatcher
                        addHandler {
                            requestCount++
                            if (requestCount == 1) {
                                respond(
                                    content = ByteReadChannel("""data: {"choices":[{"delta":{"content":"Partial"}}]}

"""),
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
                                )
                            } else {
                                respond(
                                    content = """{"choices":[{"message":{"content":"Full reply"}}]}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                                )
                            }
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
            sessionRepository, messageRepository, apiSettingsRepository, client,
            CoroutineScope(testDispatcher + SupervisorJob()),
            payloadDispatcher = testDispatcher
        )
        val seed = seedSession(db)

        controller.refresh(seed.sessionId)
        testScheduler.advanceUntilIdle()

        controller.requestAiResponse(seed.sessionId, CHARACTER, PERSONA, seed.userId)
        testScheduler.advanceUntilIdle()

        val timeline = messageRepository.getMessagesForSession(seed.sessionId)
        assertEquals(3, timeline.size)
        assertEquals("Full reply", timeline.last().content,
            "The recovered response must replace the partial text, not append to it")
        assertEquals(null, controller.state.value.errorMessage)
        assertFalse(controller.state.value.isGenerating)
    }

    @Test
    fun truncatedStream_withFailedRecovery_keepsPartialAndSurfacesError() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val db = TestDb()
        val (sessionRepository, messageRepository) = newRepos(db, testDispatcher)
        val apiSettingsRepository = ApiSettingsRepository(db.database, chat.donzi.localtavern.data.security.ApiKeyCipher(chat.donzi.localtavern.data.security.TestSecretCrypto()), testDispatcher)
        apiSettingsRepository.insertApiConnection(
            provider = "test", name = "Test", baseUrl = "https://example.com",
            apiKey = "key", model = "model", isActive = true
        )
        var requestCount = 0
        val client = ChatClient(
            HttpClient(
                MockEngine(
                    MockEngineConfig().apply {
                        dispatcher = testDispatcher
                        addHandler {
                            requestCount++
                            if (requestCount == 1) {
                                respond(
                                    content = ByteReadChannel("""data: {"choices":[{"delta":{"content":"Partial"}}]}

"""),
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
                                )
                            } else {
                                respond(
                                    content = ByteReadChannel("""{"error": {"message": "boom"}}"""),
                                    status = HttpStatusCode.InternalServerError,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                                )
                            }
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
            sessionRepository, messageRepository, apiSettingsRepository, client,
            CoroutineScope(testDispatcher + SupervisorJob()),
            payloadDispatcher = testDispatcher
        )
        val seed = seedSession(db)

        controller.refresh(seed.sessionId)
        testScheduler.advanceUntilIdle()

        controller.requestAiResponse(seed.sessionId, CHARACTER, PERSONA, seed.userId)
        testScheduler.advanceUntilIdle()

        val timeline = messageRepository.getMessagesForSession(seed.sessionId)
        assertEquals(3, timeline.size)
        assertEquals("Partial", timeline.last().content,
            "Partial tokens of a truncated stream must be kept, not deleted")
        assertEquals("Response stream ended before completion.", controller.state.value.errorMessage)
        assertFalse(controller.state.value.errorIsWarning)
    }

    @Test
    fun silentFirstToken_reasoningModel_survivesIdleTimeoutGrace() = runTest {
        // A reasoning model that thinks in silence for 30 s before its first
        // token, with a 10 s connection timeout. The first-token grace (4x for
        // reasoning models) must outlast the silence; the old idle timer fired
        // at 10 s and killed the stream mid-thinking.
        val (controller, db) = newControllerWithEngine(model = "deepseek-r1", timeoutLimit = 10L) {
            delay(30_000)
            respond(
                content = ByteReadChannel(
                    """data: {"choices":[{"delta":{"content":"Hello there"}}]}

data: [DONE]

"""
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            )
        }
        val seed = seedSession(db)

        controller.refresh(seed.sessionId)
        testScheduler.advanceUntilIdle()

        controller.requestAiResponse(seed.sessionId, CHARACTER, PERSONA, seed.userId)
        testScheduler.advanceUntilIdle()

        assertEquals("Hello there", controller.state.value.messages.lastOrNull()?.content,
            "A silent thinking phase must not be killed by the idle timeout")
        assertNull(controller.state.value.errorMessage)
    }

    @Test
    fun silentFirstToken_plainModel_survivesShorterGrace() = runTest {
        // Non-reasoning models get a 2x first-token grace: 15 s of silence
        // with a 10 s timeout is past the plain idle timeout but within the
        // grace, so the stream must still complete.
        val (controller, db) = newControllerWithEngine(model = "gpt-4o", timeoutLimit = 10L) {
            delay(15_000)
            respond(
                content = ByteReadChannel(
                    """data: {"choices":[{"delta":{"content":"Hello there"}}]}

data: [DONE]

"""
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            )
        }
        val seed = seedSession(db)

        controller.refresh(seed.sessionId)
        testScheduler.advanceUntilIdle()

        controller.requestAiResponse(seed.sessionId, CHARACTER, PERSONA, seed.userId)
        testScheduler.advanceUntilIdle()

        assertEquals("Hello there", controller.state.value.messages.lastOrNull()?.content)
        assertNull(controller.state.value.errorMessage)
    }

    @Test
    fun silentFirstToken_beyondGrace_stillTimesOut() = runTest {
        // The grace is not unlimited: silence past the 2x first-token budget
        // (25 s grace vs 30 s of silence, 10 s timeout) must still surface a
        // timeout.
        val (controller, db) = newControllerWithEngine(model = "gpt-4o", timeoutLimit = 10L) {
            delay(30_000)
            respond(
                content = ByteReadChannel(
                    """data: {"choices":[{"delta":{"content":"Hello there"}}]}

data: [DONE]

"""
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            )
        }
        val seed = seedSession(db)

        controller.refresh(seed.sessionId)
        testScheduler.advanceUntilIdle()

        controller.requestAiResponse(seed.sessionId, CHARACTER, PERSONA, seed.userId)
        testScheduler.advanceUntilIdle()

        assertEquals("Response timeout exceeded.", controller.state.value.errorMessage,
            "Silence beyond the first-token grace must still time out")
        assertTrue(controller.state.value.errorIsWarning)
    }

    @Test
    fun midStreamStall_afterFirstToken_stillTimesOut() = runTest {
        // The grace applies to the first token only: once tokens flow, a
        // stall must be killed at the plain idle timeout even on reasoning
        // models.
        val (controller, db) = newControllerWithEngine(model = "deepseek-r1", timeoutLimit = 10L) {
            // Stream one token immediately, then go silent for 30 s before
            // finishing the stream.
            val producerScope = CoroutineScope(coroutineContext + SupervisorJob())
            val channel = ByteChannel()
            producerScope.launch {
                // The runner cancels the stream at the timeout, which closes
                // the channel mid-silence; the trailing write then fails and
                // is expected, so it must not crash the producer.
                runCatching {
                    channel.writeFully(
                        """data: {"choices":[{"delta":{"content":"Hello"}}]}

""".encodeToByteArray()
                    )
                    channel.flush()
                    delay(30_000)
                    channel.writeFully("data: [DONE]\n\n".encodeToByteArray())
                    channel.flush()
                    channel.close()
                }
            }
            respond(
                content = channel,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            )
        }
        val seed = seedSession(db)

        controller.refresh(seed.sessionId)
        testScheduler.advanceUntilIdle()

        controller.requestAiResponse(seed.sessionId, CHARACTER, PERSONA, seed.userId)
        testScheduler.advanceUntilIdle()

        val (_, messageRepository) = seed.sessionRepository to seed.messageRepository
        assertEquals("Hello", messageRepository.getMessagesForSession(seed.sessionId).last().content,
            "The partial response must be kept with a warning")
        assertEquals("Response timeout exceeded.", controller.state.value.errorMessage)
        assertTrue(controller.state.value.errorIsWarning)
    }
}
