package chat.donzi.localtavern.controller

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.donzi.localtavern.data.database.ApiSettingsRepository
import chat.donzi.localtavern.data.database.LocalTavernDB
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
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
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
import kotlin.test.assertTrue

class ChatControllerTest {

    private suspend fun TestScope.newController(vararg streamChunks: String): Pair<ChatController, TestDb> {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val db = TestDb()
        val sessionRepository = SessionRepository(db.database, testDispatcher)
        val apiSettingsRepository = ApiSettingsRepository(db.database, testDispatcher)
        apiSettingsRepository.insertApiConnection(
            provider = "test", name = "Test", baseUrl = "https://example.com",
            apiKey = "key", model = "model", isActive = true
        )
        val controller = ChatController(
            sessionRepository = sessionRepository,
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

    private suspend fun TestScope.seedSession(db: TestDb): Seed {
        val sessionRepository = SessionRepository(db.database, StandardTestDispatcher(testScheduler))
        val sessionId = sessionRepository.createNewSession(CHARACTER.id, PERSONA.id)
        val greetingId = sessionRepository.insertMessageRaw(sessionId, "assistant", "Hello!", null, true)
        sessionRepository.updateSessionCurrentMessage(sessionId, greetingId)
        val userId = sessionRepository.insertMessage(sessionId, "user", "Hi", greetingId)
        val assistantId = sessionRepository.insertMessage(sessionId, "assistant", "Old reply", userId)
        return Seed(sessionRepository, sessionId, greetingId, userId, assistantId)
    }

    private data class Seed(
        val sessionRepository: SessionRepository,
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
        ).collect { tokens.add(it) }
        assertEquals(listOf("New reply"), tokens)
    }

    @Test
    fun regenerate_attachesNewResponseToLastUserMessage() = runTest {
        val (controller, db) = newController("""{"choices":[{"delta":{"content":"New reply"}}]}""", "[DONE]")
        val seed = seedSession(db)
        val sessionRepository = seed.sessionRepository

        controller.refresh(seed.sessionId)
        testScheduler.advanceUntilIdle()

        controller.regenerate(seed.sessionId, CHARACTER, PERSONA)
        testScheduler.advanceUntilIdle()

        val timeline = sessionRepository.getMessagesForSession(seed.sessionId)
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
    fun emptyStreamResponse_deletesPlaceholderMessage() = runTest {
        val (controller, db) = newController("[DONE]")
        val seed = seedSession(db)
        val sessionRepository = seed.sessionRepository

        controller.refresh(seed.sessionId)
        testScheduler.advanceUntilIdle()

        controller.requestAiResponse(seed.sessionId, CHARACTER, PERSONA, seed.userId)
        testScheduler.advanceUntilIdle()

        val timeline = sessionRepository.getMessagesForSession(seed.sessionId)
        assertEquals(2, timeline.size, "Empty response placeholder must be removed")
        assertEquals(seed.userId, sessionRepository.getSessionById(seed.sessionId)?.currentMessageId)
    }

    @Test
    fun deadStream_surfacesErrorInsteadOfSilentlyDropping() = runTest {
        // A 200 with an SSE content type but zero data lines must raise an
        // error, not silently remove the placeholder.
        val (controller, db) = newController()
        val seed = seedSession(db)
        val sessionRepository = seed.sessionRepository

        controller.refresh(seed.sessionId)
        testScheduler.advanceUntilIdle()

        controller.requestAiResponse(seed.sessionId, CHARACTER, PERSONA, seed.userId)
        testScheduler.advanceUntilIdle()

        val timeline = sessionRepository.getMessagesForSession(seed.sessionId)
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
        val apiSettingsRepository = ApiSettingsRepository(db.database, testDispatcher)

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
        val sessionRepository = SessionRepository(db.database, testDispatcher)

        val sessionId = sessionRepository.createNewSession(CHARACTER.id, PERSONA.id)
        val greetingId = sessionRepository.insertMessageRaw(sessionId, "assistant", "Hello!", null, true)
        sessionRepository.updateSessionCurrentMessage(sessionId, greetingId)
        val userId = sessionRepository.insertMessage(sessionId, "user", "Hi", greetingId)

        assertEquals(userId, sessionRepository.getSessionById(sessionId)?.currentMessageId)
        sessionRepository.deleteMessage(userId)
        testScheduler.advanceUntilIdle()

        assertEquals(greetingId, sessionRepository.getSessionById(sessionId)?.currentMessageId, "currentMessageId must not point to a deleted message")
        val timeline = sessionRepository.getMessagesForSession(sessionId)
        assertEquals(1, timeline.size)
        assertEquals(greetingId, timeline.first().id)
    }

    @Test
    fun deleteMessage_doesNotMoveCurrentWhenDeletingNonCurrentMessage() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val db = TestDb()
        val sessionRepository = SessionRepository(db.database, testDispatcher)

        val sessionId = sessionRepository.createNewSession(CHARACTER.id, PERSONA.id)
        val greetingId = sessionRepository.insertMessageRaw(sessionId, "assistant", "Hello!", null, true)
        val greeting2Id = sessionRepository.insertMessageRaw(sessionId, "assistant", "Alt greeting", null, false)
        sessionRepository.updateSessionCurrentMessage(sessionId, greetingId)
        val userId = sessionRepository.insertMessage(sessionId, "user", "Hi", greetingId)

        sessionRepository.deleteMessage(greeting2Id)
        testScheduler.advanceUntilIdle()

        val session = sessionRepository.getSessionById(sessionId)
        assertNotNull(session)
        assertEquals(userId, session.currentMessageId, "Deleting a non-current message must not touch currentMessageId")
        assertEquals(1, sessionRepository.getMessageSiblings(sessionId, null).size)
    }

    @Test
    fun deleteMessage_deactivatesDescendantsAndRepointsCurrent() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val db = TestDb()
        val sessionRepository = SessionRepository(db.database, testDispatcher)

        val sessionId = sessionRepository.createNewSession(CHARACTER.id, PERSONA.id)
        val greetingId = sessionRepository.insertMessageRaw(sessionId, "assistant", "Hello!", null, true)
        sessionRepository.updateSessionCurrentMessage(sessionId, greetingId)
        val userId = sessionRepository.insertMessage(sessionId, "user", "Hi", greetingId)
        val replyId = sessionRepository.insertMessage(sessionId, "assistant", "Reply", userId)
        val followUpId = sessionRepository.insertMessage(sessionId, "user", "Follow-up", replyId)

        sessionRepository.deleteMessage(replyId)
        testScheduler.advanceUntilIdle()

        val timeline = sessionRepository.getMessagesForSession(sessionId)
        assertEquals(listOf(greetingId, userId), timeline.map { it.id },
            "Descendants of a deleted message must be deactivated")
        assertEquals(userId, sessionRepository.getSessionById(sessionId)?.currentMessageId,
            "currentMessageId must move to the deleted message's parent")
    }

    @Test
    fun deleteRootGreeting_doesNotReseedGreetings() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val db = TestDb()
        val sessionRepository = SessionRepository(db.database, testDispatcher)

        val sessionId = sessionRepository.createNewSession(CHARACTER.id, PERSONA.id)
        val greetingId = sessionRepository.insertMessageRaw(sessionId, "assistant", "Hello!", null, true)
        sessionRepository.updateSessionCurrentMessage(sessionId, greetingId)

        sessionRepository.deleteMessage(greetingId)
        sessionRepository.ensureInitialGreetings(sessionId, CHARACTER)
        testScheduler.advanceUntilIdle()

        assertTrue(sessionRepository.getMessagesForSession(sessionId).isEmpty(),
            "Deleting the root greeting must not trigger greeting re-seeding")
        assertEquals(null, sessionRepository.getSessionById(sessionId)?.currentMessageId)
    }

    @Test
    fun selectVariation_deactivatesDescendantsOfDeselectedBranch() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val db = TestDb()
        val sessionRepository = SessionRepository(db.database, testDispatcher)

        val sessionId = sessionRepository.createNewSession(CHARACTER.id, PERSONA.id)
        val greetingId = sessionRepository.insertMessageRaw(sessionId, "assistant", "Hello!", null, true)
        sessionRepository.updateSessionCurrentMessage(sessionId, greetingId)
        val userId = sessionRepository.insertMessage(sessionId, "user", "Hi", greetingId)
        val replyA = sessionRepository.insertMessage(sessionId, "assistant", "Reply A", userId)
        val followUp = sessionRepository.insertMessage(sessionId, "user", "Follow-up", replyA)
        val replyB = sessionRepository.insertMessageRaw(sessionId, "assistant", "Reply B", userId, false)

        sessionRepository.selectVariation(sessionId, replyB, userId)
        testScheduler.advanceUntilIdle()

        val timeline = sessionRepository.getMessagesForSession(sessionId)
        assertEquals(listOf(greetingId, userId, replyB), timeline.map { it.id },
            "Only the selected branch must remain active")
        assertEquals(replyB, sessionRepository.getSessionById(sessionId)?.currentMessageId)
    }

    @Test
    fun deleteMessagesRaw_keepsTimelineConsistent() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val db = TestDb()
        val sessionRepository = SessionRepository(db.database, testDispatcher)
        val apiSettingsRepository = ApiSettingsRepository(db.database, testDispatcher)
        apiSettingsRepository.insertApiConnection(
            provider = "test", name = "Test", baseUrl = "https://example.com",
            apiKey = "key", model = "model", isActive = true
        )
        val controller = ChatController(
            sessionRepository, apiSettingsRepository,
            ChatClient(HttpClient(MockEngine { respond(
                content = ByteReadChannel("data: [DONE]"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            ) })),
            CoroutineScope(testDispatcher + SupervisorJob()),
            payloadDispatcher = testDispatcher
        )

        val sessionId = sessionRepository.createNewSession(CHARACTER.id, PERSONA.id)
        val greetingId = sessionRepository.insertMessageRaw(sessionId, "assistant", "Hello!", null, true)
        sessionRepository.updateSessionCurrentMessage(sessionId, greetingId)
        val u1 = sessionRepository.insertMessage(sessionId, "user", "Hi", greetingId)
        val a1 = sessionRepository.insertMessage(sessionId, "assistant", "Reply A", u1)
        val u2 = sessionRepository.insertMessage(sessionId, "user", "Follow-up", a1)
        val a2 = sessionRepository.insertMessage(sessionId, "assistant", "Reply B", u2)

        controller.refresh(sessionId)
        testScheduler.advanceUntilIdle()

        val allSuffix = listOf(u1, a1, u2, a2)
        controller.deleteMessagesRaw(sessionId, allSuffix.shuffled(Random(42)))
        testScheduler.advanceUntilIdle()

        val timeline = sessionRepository.getMessagesForSession(sessionId)
        assertEquals(listOf(greetingId), timeline.map { it.id },
            "Deleting the whole suffix must leave only the greeting active")
        assertEquals(greetingId, sessionRepository.getSessionById(sessionId)?.currentMessageId,
            "currentMessageId must never dangle on a deleted message")
    }

    @Test
    fun generationCompletion_doesNotOverwriteViewOfAnotherSession() = runTest {
        val (controller, db) = newController("""{"choices":[{"delta":{"content":"Done"}}]}""", "[DONE]")
        val seed = seedSession(db)
        val sessionRepository = seed.sessionRepository
        val otherSessionId = sessionRepository.createNewSession(CHARACTER.id, PERSONA.id)

        controller.refresh(seed.sessionId)
        testScheduler.advanceUntilIdle()

        // Start generation in session A, then switch the view to session B before it completes.
        controller.requestAiResponse(seed.sessionId, CHARACTER, PERSONA, seed.userId)
        controller.refresh(otherSessionId)
        testScheduler.advanceUntilIdle()

        // The response must be persisted in A's session...
        val sessionATimeline = sessionRepository.getMessagesForSession(seed.sessionId)
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
        ).collect { tokens.add(it) }

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
        val sessionRepository = SessionRepository(db.database, testDispatcher)
        val apiSettingsRepository = ApiSettingsRepository(db.database, testDispatcher)
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
            sessionRepository, apiSettingsRepository, client,
            CoroutineScope(testDispatcher + SupervisorJob()),
            payloadDispatcher = testDispatcher
        )
        val seed = seedSession(db)

        controller.refresh(seed.sessionId)
        testScheduler.advanceUntilIdle()

        controller.requestAiResponse(seed.sessionId, CHARACTER, PERSONA, seed.userId)
        testScheduler.advanceUntilIdle()

        val timeline = sessionRepository.getMessagesForSession(seed.sessionId)
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
        assertEquals("", messages[0].jsonObject["content"]?.jsonPrimitive?.content)
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
}
