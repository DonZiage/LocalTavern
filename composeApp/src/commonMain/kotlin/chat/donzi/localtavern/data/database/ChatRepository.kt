package chat.donzi.localtavern.data.database

import chat.donzi.localtavern.data.models.SillyTavernCardV2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.time.Clock

class ChatRepository(private val database: LocalTavernDB) {
    private val queries = database.localTavernDBQueries

    private fun generateUuid(): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..32).map { chars.random() }.joinToString("")
    }

    suspend fun getAllCharacters(): List<CharacterEntity> = withContext(Dispatchers.IO) {
        queries.selectAllCharacters().executeAsList()
    }

    suspend fun getAssistant(): CharacterEntity? = withContext(Dispatchers.IO) {
        queries.selectAssistant().executeAsOneOrNull()
    }

    suspend fun createAssistant(): String = withContext(Dispatchers.IO) {
        val newId = generateUuid()
        queries.insertCharacter(
            id = newId,
            name = "Assistant",
            description = "A helpful AI assistant.",
            personality = "Helpful, polite, and direct.",
            scenario = "",
            firstMes = "Hello! How can I help you today?",
            mesExample = null,
            creatorNotes = null,
            altGreetings = null,
            avatarData = null,
            isAssistant = 1L
        )
        newId
    }

    suspend fun getCharacterById(id: String): CharacterEntity? = withContext(Dispatchers.IO) {
        (queries.selectAllCharacters().executeAsList() + (queries.selectAssistant().executeAsOneOrNull()?.let { listOf(it) } ?: emptyList())).find { it.id == id }
    }

    suspend fun upsertCharacter(
        card: SillyTavernCardV2,
        avatarData: ByteArray? = null
    ): String = withContext(Dispatchers.IO) {
        val newId = generateUuid()
        queries.insertCharacter(
            id = newId,
            name = card.name,
            description = card.description,
            personality = card.personality,
            scenario = card.scenario,
            firstMes = card.first_mes,
            mesExample = card.mes_example,
            creatorNotes = card.creator_notes,
            altGreetings = card.alternate_greetings.joinToString("|||").ifBlank { null },
            avatarData = avatarData,
            isAssistant = 0L
        )
        newId
    }

    suspend fun createCharacter(name: String): String = withContext(Dispatchers.IO) {
        val newId = generateUuid()
        queries.insertCharacter(
            id = newId,
            name = name,
            description = null,
            personality = "",
            scenario = "",
            firstMes = null,
            mesExample = null,
            creatorNotes = null,
            altGreetings = null,
            avatarData = null,
            isAssistant = 0L
        )
        newId
    }

    suspend fun updateCharacter(
        id: String,
        name: String,
        personality: String,
        scenario: String,
        description: String?,
        firstMes: String?,
        mesExample: List<String> = emptyList(),
        altGreetings: List<String> = emptyList(),
        avatarData: ByteArray? = null
    ) = withContext(Dispatchers.IO) {
        queries.updateCharacter(
            name = name,
            description = description,
            personality = personality,
            scenario = scenario,
            firstMes = firstMes,
            mesExample = mesExample.joinToString("|||").ifBlank { null },
            altGreetings = altGreetings.joinToString("|||").ifBlank { null },
            avatarData = avatarData,
            id = id
        )
    }

    suspend fun deleteCharacters(ids: Collection<String>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        queries.deleteCharactersByIds(ids.toList())
    }

    suspend fun getAllPersonas(): List<PersonaEntity> = withContext(Dispatchers.IO) {
        queries.selectAllPersonas().executeAsList()
    }

    suspend fun insertPersona(name: String, description: String?, avatarData: ByteArray?): String = withContext(Dispatchers.IO) {
        val newId = generateUuid()
        queries.insertPersona(newId, name, description, avatarData)
        newId
    }

    suspend fun updatePersona(id: String, name: String, description: String?, avatarData: ByteArray?) = withContext(Dispatchers.IO) {
        queries.updatePersona(name, description, avatarData, id)
    }

    suspend fun deletePersona(id: String) = withContext(Dispatchers.IO) {
        queries.deletePersona(id)
    }

    suspend fun getAllApiConnections(): List<ApiConnection> = withContext(Dispatchers.IO) {
        queries.selectAllApiConnections().executeAsList()
    }

    private fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

    suspend fun insertApiConnection(
        provider: String, name: String, baseUrl: String?, apiKey: String?, model: String?,
        isActive: Boolean = false, isChatCompletion: Boolean = true, temperature: Double = 1.0,
        topP: Double = 1.0, topK: Long = 0, presencePenalty: Double = 0.0, frequencyPenalty: Double = 0.0,
        contextLimit: Long = 4096, responseLimit: Long = 1024, displayOrder: Long = 0, timeoutLimit: Long = 60
    ): String = withContext(Dispatchers.IO) {
        val newId = generateUuid()
        queries.insertApiConnection(
            newId, provider, name, baseUrl, apiKey, model, if (isActive) 1L else 0L, if (isChatCompletion) 1L else 0L,
            if (isActive) currentTimeMillis() else 0L, temperature, topP, topK, presencePenalty, frequencyPenalty,
            contextLimit, responseLimit, displayOrder, timeoutLimit
        )
        newId
    }

    suspend fun updateApiConnection(
        id: String, provider: String, name: String, baseUrl: String?, apiKey: String?, model: String?,
        isActive: Boolean, isChatCompletion: Boolean, lastUsed: Long? = null, temperature: Double,
        topP: Double, topK: Long, presencePenalty: Double, frequencyPenalty: Double, contextLimit: Long,
        responseLimit: Long, displayOrder: Long, timeoutLimit: Long
    ) = withContext(Dispatchers.IO) {
        queries.updateApiConnection(
            provider, name, baseUrl, apiKey, model, if (isActive) 1L else 0L, if (isChatCompletion) 1L else 0L,
            lastUsed ?: (if (isActive) currentTimeMillis() else null), temperature, topP, topK, presencePenalty,
            frequencyPenalty, contextLimit, responseLimit, displayOrder, timeoutLimit, id
        )
    }

    suspend fun deleteApiConnection(id: String) = withContext(Dispatchers.IO) {
        queries.deleteApiConnection(id)
    }

    suspend fun setActiveApiConnection(id: String) = withContext(Dispatchers.IO) {
        queries.setActiveApiConnection()
        queries.updateActiveApiConnection(currentTimeMillis(), id)
    }

    suspend fun getActiveApiConnection(): ApiConnection? = withContext(Dispatchers.IO) {
        queries.selectActiveApiConnection().executeAsOneOrNull()
            ?: queries.selectLastUsedApiConnection().executeAsOneOrNull()
    }

    suspend fun updateApiConnectionDisplayOrders(orderedIds: List<String>): Unit = withContext(Dispatchers.IO) {
        database.transaction {
            orderedIds.forEachIndexed { index, id ->
                queries.updateApiConnectionDisplayOrder(index.toLong(), id)
            }
        }
    }

    suspend fun getAppSettings(): AppSettings = withContext(Dispatchers.IO) {
        queries.insertDefaultSettings()
        queries.getAppSettings().executeAsOne()
    }

    suspend fun updateActivePersonaId(personaId: String?) = withContext(Dispatchers.IO) {
        queries.updateActivePersonaId(personaId)
    }

    suspend fun updateDarkMode(isDarkMode: Boolean) = withContext(Dispatchers.IO) {
        queries.updateDarkMode(if (isDarkMode) 1L else 0L)
    }

    suspend fun getSessionById(id: String): ChatSession? = withContext(Dispatchers.IO) {
        queries.selectSessionById(id).executeAsOneOrNull()
    }

    suspend fun getOrCreateSession(characterId: String, personaId: String): String = withContext(Dispatchers.IO) {
        val session = queries.selectLastSessionForCharacter(characterId).executeAsOneOrNull()
        session?.id ?: database.transactionWithResult {
            val newId = generateUuid()
            queries.insertChatSession(newId, characterId, personaId, null, currentTimeMillis(), null, null)
            newId
        }
    }

    suspend fun getMessagesForSession(sessionId: String): List<MessageEntity> = withContext(Dispatchers.IO) {
        queries.selectActiveTimeline(sessionId).executeAsList()
    }

    suspend fun getMessageSiblings(sessionId: String, parentId: String?): List<MessageEntity> = withContext(Dispatchers.IO) {
        queries.selectSiblings(sessionId, parentId).executeAsList()
    }

    suspend fun updateSessionCurrentMessage(sessionId: String, messageId: String) = withContext(Dispatchers.IO) {
        queries.updateSessionCurrentMessage(messageId, currentTimeMillis(), sessionId)
    }

    suspend fun selectVariation(sessionId: String, messageId: String, parentId: String?) = withContext(Dispatchers.IO) {
        database.transaction {
            queries.activateMessage(messageId)
            queries.deactivateSiblings(sessionId, parentId, messageId)
            queries.updateSessionCurrentMessage(messageId, currentTimeMillis(), sessionId)
        }
    }

    suspend fun insertMessage(sessionId: String, role: String, content: String, parentId: String?): String = withContext(Dispatchers.IO) {
        database.transactionWithResult {
            val newId = generateUuid()
            queries.insertMessageWithParent(newId, sessionId, role, content, currentTimeMillis(), parentId, 1L)
            queries.deactivateSiblings(sessionId, parentId, newId)
            queries.updateSessionCurrentMessage(newId, currentTimeMillis(), sessionId)
            newId
        }
    }

    suspend fun insertMessageRaw(sessionId: String, role: String, content: String, parentId: String?, isActivePath: Boolean): String = withContext(Dispatchers.IO) {
        val newId = generateUuid()
        queries.insertMessageWithParent(newId, sessionId, role, content, currentTimeMillis(), parentId, if (isActivePath) 1L else 0L)
        newId
    }

    suspend fun updateMessageContent(id: String, content: String) = withContext(Dispatchers.IO) {
        queries.updateMessageContent(content, id)
    }

    suspend fun deleteMessage(id: String) = withContext(Dispatchers.IO) {
        queries.deleteMessage(id)
    }

    suspend fun getAllPromptBlocks(): List<PromptBlockEntity> = withContext(Dispatchers.IO) {
        val storedBlocks = queries.selectAllPromptBlocks().executeAsList()
        storedBlocks.ifEmpty {
            database.transaction {
                var initialOrder = 0L
                queries.insertPromptBlock("system", "System Prompt", "You are roleplaying. Stay in character, describe actions vividly, and adapt seamlessly to the story scenario.", 1L, 0L, initialOrder++)
                queries.insertPromptBlock("persona", "User Persona", "User Persona:\n{{user_persona}}", 1L, 0L, initialOrder++)
                queries.insertPromptBlock("description", "Character Description", "Character Info:\n{{character_description}}", 1L, 0L, initialOrder++)
                queries.insertPromptBlock("personality", "Personality", "Personality:\n{{personality}}", 1L, 0L, initialOrder++)
                queries.insertPromptBlock("scenario", "Scenario", "Scenario:\n{{scenario}}", 1L, 0L, initialOrder++)
                queries.insertPromptBlock("chat_history", "Chat History", "{{chat_history}}", 1L, 0L, initialOrder)
            }
            queries.selectAllPromptBlocks().executeAsList()
        }
    }

    suspend fun savePromptBlock(id: String, name: String, template: String, isEnabled: Boolean) = withContext(Dispatchers.IO) {
        queries.updatePromptBlock(name, template, if (isEnabled) 1L else 0L, id)
    }

    suspend fun insertCustomPromptBlock(name: String, template: String): String = withContext(Dispatchers.IO) {
        val uniqueId = "custom_${currentTimeMillis()}"
        val currentBlocks = queries.selectAllPromptBlocks().executeAsList()
        val nextOrderPosition = (currentBlocks.maxOfOrNull { it.displayOrder } ?: -1L) + 1L
        queries.insertPromptBlock(uniqueId, name, template, 1L, 1L, nextOrderPosition)
        uniqueId
    }

    suspend fun deletePromptBlock(id: String) = withContext(Dispatchers.IO) {
        queries.deletePromptBlock(id)
    }

    suspend fun updatePromptBlockDisplayOrders(orderedIds: List<String>): Unit = withContext(Dispatchers.IO) {
        database.transaction {
            orderedIds.forEachIndexed { index, id ->
                queries.updatePromptBlockDisplayOrder(index.toLong(), id)
            }
        }
    }

    suspend fun getSessionsForCharacter(characterId: String): List<ChatSession> = withContext(Dispatchers.IO) {
        queries.selectSessionsForCharacter(characterId).executeAsList()
    }

    suspend fun createNewSession(characterId: String, personaId: String): String = withContext(Dispatchers.IO) {
        val newId = generateUuid()
        queries.insertChatSession(newId, characterId, personaId, null, currentTimeMillis(), null, null)
        newId
    }

    suspend fun branchSession(
        originalSessionId: String,
        untilMessageId: String,
        messagesToCopy: List<MessageEntity>,
        newTitle: String
    ): String = withContext(Dispatchers.IO) {
        database.transactionWithResult {
            val originalSession = queries.selectSessionById(originalSessionId).executeAsOneOrNull()
                ?: throw IllegalArgumentException("Original session not found")

            val newSessionId = generateUuid()
            queries.insertChatSession(
                newSessionId,
                originalSession.characterId,
                originalSession.personaId,
                newTitle,
                currentTimeMillis(),
                null,
                originalSessionId
            )

            var lastInsertedNewId: String? = null
            val cutoffIndex = messagesToCopy.indexOfFirst { it.id == untilMessageId }
            val filteredMessages = if (cutoffIndex != -1) {
                messagesToCopy.subList(0, cutoffIndex + 1)
            } else {
                messagesToCopy
            }

            filteredMessages.forEach { msg ->
                val newMsgId = generateUuid()
                queries.insertMessageWithParent(
                    newMsgId, newSessionId, msg.role, msg.content, msg.timestamp, lastInsertedNewId, 1L
                )
                lastInsertedNewId = newMsgId
            }

            if (lastInsertedNewId != null) {
                queries.updateSessionCurrentMessage(lastInsertedNewId!!, currentTimeMillis(), newSessionId)
            }

            newSessionId
        }
    }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        database.transaction {
            queries.deleteMessagesForSession(sessionId)
            queries.deleteSession(sessionId)
        }
    }

    suspend fun updateSessionTitle(sessionId: Long, title: String?) = withContext(Dispatchers.IO) {
        queries.updateSessionTitle(title, sessionId.toString())
    }

    suspend fun updateSessionTitle(sessionId: String, title: String?) = withContext(Dispatchers.IO) {
        queries.updateSessionTitle(title, sessionId)
    }
}