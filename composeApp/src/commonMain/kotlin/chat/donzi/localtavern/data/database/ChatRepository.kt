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

    private fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

    suspend fun getAllCharacters(): List<CharacterEntity> = withContext(Dispatchers.IO) {
        queries.selectAllCharacters().executeAsList()
    }

    suspend fun getAssistant(): CharacterEntity? = withContext(Dispatchers.IO) {
        queries.selectAssistant().executeAsOneOrNull()
    }

    suspend fun createAssistant(): String = withContext(Dispatchers.IO) {
        val newId = generateUuid()
        val now = currentTimeMillis()
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
            isAssistant = 1L,
            updatedAt = now,
            isDeleted = 0L
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
        val now = currentTimeMillis()
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
            isAssistant = 0L,
            updatedAt = now,
            isDeleted = 0L
        )
        newId
    }

    suspend fun createCharacter(name: String): String = withContext(Dispatchers.IO) {
        val newId = generateUuid()
        val now = currentTimeMillis()
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
            isAssistant = 0L,
            updatedAt = now,
            isDeleted = 0L
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
            updatedAt = currentTimeMillis(),
            id = id
        )
    }

    suspend fun deleteCharacters(ids: Collection<String>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        queries.deleteCharactersByIds(
            updatedAt = currentTimeMillis(),
            id = ids.toList()
        )
    }

    suspend fun getAllPersonas(): List<PersonaEntity> = withContext(Dispatchers.IO) {
        queries.selectAllPersonas().executeAsList()
    }

    suspend fun insertPersona(name: String, description: String?, avatarData: ByteArray?): String = withContext(Dispatchers.IO) {
        val newId = generateUuid()
        queries.insertPersona(
            id = newId,
            name = name,
            description = description,
            avatarData = avatarData,
            updatedAt = currentTimeMillis(),
            isDeleted = 0L
        )
        newId
    }

    suspend fun updatePersona(id: String, name: String, description: String?, avatarData: ByteArray?) = withContext(Dispatchers.IO) {
        queries.updatePersona(
            name = name,
            description = description,
            avatarData = avatarData,
            updatedAt = currentTimeMillis(),
            id = id
        )
    }

    suspend fun deletePersona(id: String) = withContext(Dispatchers.IO) {
        queries.deletePersona(
            updatedAt = currentTimeMillis(),
            id = id
        )
    }

    suspend fun getAllApiConnections(): List<ApiConnection> = withContext(Dispatchers.IO) {
        queries.selectAllApiConnections().executeAsList()
    }

    suspend fun insertApiConnection(
        provider: String, name: String, baseUrl: String?, apiKey: String?, model: String?,
        isActive: Boolean = false, isChatCompletion: Boolean = true, temperature: Double = 1.0,
        topP: Double = 1.0, topK: Long = 0, presencePenalty: Double = 0.0, frequencyPenalty: Double = 0.0,
        contextLimit: Long = 4096, responseLimit: Long = 1024, displayOrder: Long = 0, timeoutLimit: Long = 60
    ): String = withContext(Dispatchers.IO) {
        val newId = generateUuid()
        val now = currentTimeMillis()
        queries.insertApiConnection(
            id = newId, provider = provider, name = name, baseUrl = baseUrl, apiKey = apiKey, model = model,
            isActive = if (isActive) 1L else 0L, isChatCompletion = if (isChatCompletion) 1L else 0L,
            lastUsed = if (isActive) now else 0L, temperature = temperature, topP = topP, topK = topK,
            presencePenalty = presencePenalty, frequencyPenalty = frequencyPenalty, contextLimit = contextLimit,
            responseLimit = responseLimit, displayOrder = displayOrder, timeoutLimit = timeoutLimit,
            updatedAt = now, isDeleted = 0L
        )
        newId
    }

    suspend fun updateApiConnection(
        id: String, provider: String, name: String, baseUrl: String?, apiKey: String?, model: String?,
        isActive: Boolean, isChatCompletion: Boolean, lastUsed: Long? = null, temperature: Double,
        topP: Double, topK: Long, presencePenalty: Double, frequencyPenalty: Double, contextLimit: Long,
        responseLimit: Long, displayOrder: Long, timeoutLimit: Long
    ) = withContext(Dispatchers.IO) {
        val now = currentTimeMillis()
        queries.updateApiConnection(
            provider = provider, name = name, baseUrl = baseUrl, apiKey = apiKey, model = model,
            isActive = if (isActive) 1L else 0L, isChatCompletion = if (isChatCompletion) 1L else 0L,
            lastUsed = lastUsed ?: (if (isActive) now else null), temperature = temperature, topP = topP, topK = topK,
            presencePenalty = presencePenalty, frequencyPenalty = frequencyPenalty, contextLimit = contextLimit,
            responseLimit = responseLimit, displayOrder = displayOrder, timeoutLimit = timeoutLimit,
            updatedAt = now, id = id
        )
    }

    suspend fun deleteApiConnection(id: String) = withContext(Dispatchers.IO) {
        queries.deleteApiConnection(
            updatedAt = currentTimeMillis(),
            id = id
        )
    }

    suspend fun setActiveApiConnection(id: String) = withContext(Dispatchers.IO) {
        val now = currentTimeMillis()
        queries.setActiveApiConnection(updatedAt = now)
        queries.updateActiveApiConnection(lastUsed = now, updatedAt = now, id = id)
    }

    suspend fun getActiveApiConnection(): ApiConnection? = withContext(Dispatchers.IO) {
        queries.selectActiveApiConnection().executeAsOneOrNull()
            ?: queries.selectLastUsedApiConnection().executeAsOneOrNull()
    }

    suspend fun updateApiConnectionDisplayOrders(orderedIds: List<String>): Unit = withContext(Dispatchers.IO) {
        val now = currentTimeMillis()
        database.transaction {
            orderedIds.forEachIndexed { index, id ->
                queries.updateApiConnectionDisplayOrder(displayOrder = index.toLong(), updatedAt = now, id = id)
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
        val now = currentTimeMillis()
        session?.id ?: database.transactionWithResult {
            val newId = generateUuid()
            queries.insertChatSession(
                id = newId, characterId = characterId, personaId = personaId, title = null,
                lastTimestamp = now, currentMessageId = null, parentSessionId = null,
                updatedAt = now, isDeleted = 0L
            )
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
        val now = currentTimeMillis()
        queries.updateSessionCurrentMessage(currentMessageId = messageId, lastTimestamp = now, updatedAt = now, id = sessionId)
    }

    suspend fun selectVariation(sessionId: String, messageId: String, parentId: String?) = withContext(Dispatchers.IO) {
        val now = currentTimeMillis()
        database.transaction {
            queries.activateMessage(updatedAt = now, id = messageId)
            queries.deactivateSiblings(updatedAt = now, sessionId = sessionId, parentId = parentId, id = messageId)
            queries.updateSessionCurrentMessage(currentMessageId = messageId, lastTimestamp = now, updatedAt = now, id = sessionId)
        }
    }

    suspend fun insertMessage(sessionId: String, role: String, content: String, parentId: String?, imageData: ByteArray? = null): String = withContext(Dispatchers.IO) {
        val now = currentTimeMillis()
        database.transactionWithResult {
            val newId = generateUuid()
            queries.insertMessageWithParent(
                id = newId, sessionId = sessionId, role = role, content = content,
                timestamp = now, parentId = parentId, isActivePath = 1L,
                updatedAt = now, isDeleted = 0L, imageData = imageData
            )
            queries.deactivateSiblings(updatedAt = now, sessionId = sessionId, parentId = parentId, id = newId)
            queries.updateSessionCurrentMessage(currentMessageId = newId, lastTimestamp = now, updatedAt = now, id = sessionId)
            newId
        }
    }

    suspend fun insertMessageRaw(sessionId: String, role: String, content: String, parentId: String?, isActivePath: Boolean, imageData: ByteArray? = null): String = withContext(Dispatchers.IO) {
        val newId = generateUuid()
        val now = currentTimeMillis()
        queries.insertMessageWithParent(
            id = newId, sessionId = sessionId, role = role, content = content,
            timestamp = now, parentId = parentId, isActivePath = if (isActivePath) 1L else 0L,
            updatedAt = now, isDeleted = 0L, imageData = imageData
        )
        newId
    }

    suspend fun updateMessageContent(id: String, content: String) = withContext(Dispatchers.IO) {
        queries.updateMessageContent(content = content, updatedAt = currentTimeMillis(), id = id)
    }

    suspend fun updateMessageImage(id: String, imageData: ByteArray?) = withContext(Dispatchers.IO) {
        queries.updateMessageImage(imageData = imageData, updatedAt = currentTimeMillis(), id = id)
    }

    suspend fun deleteMessage(id: String) = withContext(Dispatchers.IO) {
        queries.deleteMessage(updatedAt = currentTimeMillis(), id = id)
    }

    suspend fun getAllPromptBlocks(): List<PromptBlockEntity> = withContext(Dispatchers.IO) {
        val storedBlocks = queries.selectAllPromptBlocks().executeAsList()
        if (storedBlocks.isEmpty()) {
            val now = currentTimeMillis()
            database.transaction {
                var initialOrder = 0L
                queries.insertPromptBlock("system", "System Prompt", "You are roleplaying. Stay in character, describe actions vividly, and adapt seamlessly to the story scenario.", 1L, 0L, initialOrder++, now, 0L)
                queries.insertPromptBlock("persona", "User Persona", "User Persona:\n{{user_persona}}", 1L, 0L, initialOrder++, now, 0L)
                queries.insertPromptBlock("description", "Character Description", "Character Info:\n{{character_description}}", 1L, 0L, initialOrder++, now, 0L)
                queries.insertPromptBlock("personality", "Personality", "Personality:\n{{personality}}", 1L, 0L, initialOrder++, now, 0L)
                queries.insertPromptBlock("scenario", "Scenario", "Scenario:\n{{scenario}}", 1L, 0L, initialOrder++, now, 0L)
                queries.insertPromptBlock("chat_history", "Chat History", "{{chat_history}}", 1L, 0L, initialOrder, now, 0L)
            }
            queries.selectAllPromptBlocks().executeAsList()
        } else {
            storedBlocks
        }
    }

    suspend fun savePromptBlock(id: String, name: String, template: String, isEnabled: Boolean) = withContext(Dispatchers.IO) {
        queries.updatePromptBlock(name = name, template = template, isEnabled = if (isEnabled) 1L else 0L, updatedAt = currentTimeMillis(), id = id)
    }

    suspend fun insertCustomPromptBlock(name: String, template: String): String = withContext(Dispatchers.IO) {
        val now = currentTimeMillis()
        val uniqueId = "custom_$now"
        val currentBlocks = queries.selectAllPromptBlocks().executeAsList()
        val nextOrderPosition = (currentBlocks.maxOfOrNull { it.displayOrder } ?: -1L) + 1L
        queries.insertPromptBlock(uniqueId, name, template, 1L, 1L, nextOrderPosition, now, 0L)
        uniqueId
    }

    suspend fun deletePromptBlock(id: String) = withContext(Dispatchers.IO) {
        queries.deletePromptBlock(updatedAt = currentTimeMillis(), id = id)
    }

    suspend fun updatePromptBlockDisplayOrders(orderedIds: List<String>): Unit = withContext(Dispatchers.IO) {
        val now = currentTimeMillis()
        database.transaction {
            orderedIds.forEachIndexed { index, id ->
                queries.updatePromptBlockDisplayOrder(displayOrder = index.toLong(), updatedAt = now, id = id)
            }
        }
    }

    suspend fun getSessionsForCharacter(characterId: String): List<ChatSession> = withContext(Dispatchers.IO) {
        queries.selectSessionsForCharacter(characterId).executeAsList()
    }

    suspend fun createNewSession(characterId: String, personaId: String): String = withContext(Dispatchers.IO) {
        val newId = generateUuid()
        val now = currentTimeMillis()
        queries.insertChatSession(
            id = newId, characterId = characterId, personaId = personaId, title = null,
            lastTimestamp = now, currentMessageId = null, parentSessionId = null,
            updatedAt = now, isDeleted = 0L
        )
        newId
    }

    suspend fun branchSession(
        originalSessionId: String,
        untilMessageId: String,
        messagesToCopy: List<MessageEntity>,
        newTitle: String
    ): String = withContext(Dispatchers.IO) {
        val now = currentTimeMillis()
        database.transactionWithResult {
            val originalSession = queries.selectSessionById(originalSessionId).executeAsOneOrNull()
                ?: throw IllegalArgumentException("Original session not found")

            val newSessionId = generateUuid()
            queries.insertChatSession(
                id = newSessionId,
                characterId = originalSession.characterId,
                personaId = originalSession.personaId,
                title = newTitle,
                lastTimestamp = now,
                currentMessageId = null,
                parentSessionId = originalSessionId,
                updatedAt = now,
                isDeleted = 0L
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
                    id = newMsgId, sessionId = newSessionId, role = msg.role, content = msg.content,
                    timestamp = msg.timestamp, parentId = lastInsertedNewId, isActivePath = 1L,
                    updatedAt = now, isDeleted = 0L, imageData = msg.imageData
                )
                lastInsertedNewId = newMsgId
            }

            if (lastInsertedNewId != null) {
                queries.updateSessionCurrentMessage(currentMessageId = lastInsertedNewId, lastTimestamp = now, updatedAt = now, id = newSessionId)
            }

            newSessionId
        }
    }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        val now = currentTimeMillis()
        database.transaction {
            queries.deleteMessagesForSession(updatedAt = now, sessionId = sessionId)
            queries.deleteSession(updatedAt = now, id = sessionId)
        }
    }

    suspend fun updateSessionTitle(sessionId: Long, title: String?) = withContext(Dispatchers.IO) {
        queries.updateSessionTitle(title = title, updatedAt = currentTimeMillis(), id = sessionId.toString())
    }

    suspend fun updateSessionTitle(sessionId: String, title: String?) = withContext(Dispatchers.IO) {
        queries.updateSessionTitle(title = title, updatedAt = currentTimeMillis(), id = sessionId)
    }

    suspend fun getCharactersModifiedAfter(timestamp: Long): List<CharacterEntity> = withContext(Dispatchers.IO) {
        queries.selectCharactersModifiedAfter(timestamp).executeAsList()
    }

    suspend fun mergeCharacterCrdt(entity: CharacterEntity) = withContext(Dispatchers.IO) {
        database.transaction {
            val existing = queries.selectCharacterForSync(entity.id).executeAsOneOrNull()
            if (existing == null) {
                queries.crdtInsertCharacter(
                    id = entity.id, name = entity.name, description = entity.description, personality = entity.personality,
                    scenario = entity.scenario, firstMes = entity.firstMes, mesExample = entity.mesExample,
                    creatorNotes = entity.creatorNotes, altGreetings = entity.altGreetings, avatarData = entity.avatarData,
                    isAssistant = entity.isAssistant, updatedAt = entity.updatedAt, isDeleted = entity.isDeleted
                )
            } else if (entity.updatedAt > existing.updatedAt) {
                queries.crdtUpdateCharacter(
                    name = entity.name, description = entity.description, personality = entity.personality,
                    scenario = entity.scenario, firstMes = entity.firstMes, mesExample = entity.mesExample,
                    creatorNotes = entity.creatorNotes, altGreetings = entity.altGreetings,
                    avatarData = entity.avatarData ?: existing.avatarData,
                    isAssistant = entity.isAssistant, updatedAt = entity.updatedAt, isDeleted = entity.isDeleted,
                    id = entity.id
                )
            }
        }
    }

    suspend fun getPersonasModifiedAfter(timestamp: Long): List<PersonaEntity> = withContext(Dispatchers.IO) {
        queries.selectPersonasModifiedAfter(timestamp).executeAsList()
    }

    suspend fun mergePersonaCrdt(entity: PersonaEntity) = withContext(Dispatchers.IO) {
        database.transaction {
            val existing = queries.selectPersonaForSync(entity.id).executeAsOneOrNull()
            if (existing == null) {
                queries.crdtInsertPersona(
                    id = entity.id, name = entity.name, description = entity.description,
                    avatarData = entity.avatarData, updatedAt = entity.updatedAt, isDeleted = entity.isDeleted
                )
            } else if (entity.updatedAt > existing.updatedAt) {
                queries.crdtUpdatePersona(
                    name = entity.name, description = entity.description,
                    avatarData = entity.avatarData ?: existing.avatarData,
                    updatedAt = entity.updatedAt, isDeleted = entity.isDeleted, id = entity.id
                )
            }
        }
    }

    suspend fun getSessionsModifiedAfter(timestamp: Long): List<ChatSession> = withContext(Dispatchers.IO) {
        queries.selectSessionsModifiedAfter(timestamp).executeAsList()
    }

    suspend fun mergeSessionCrdt(entity: ChatSession) = withContext(Dispatchers.IO) {
        database.transaction {
            val existing = queries.selectSessionForSync(entity.id).executeAsOneOrNull()
            if (existing == null) {
                queries.crdtInsertChatSession(
                    id = entity.id, characterId = entity.characterId, personaId = entity.personaId, title = entity.title,
                    lastTimestamp = entity.lastTimestamp, currentMessageId = entity.currentMessageId,
                    parentSessionId = entity.parentSessionId, updatedAt = entity.updatedAt, isDeleted = entity.isDeleted
                )
            } else if (entity.updatedAt > existing.updatedAt) {
                queries.crdtUpdateChatSession(
                    characterId = entity.characterId, personaId = entity.personaId, title = entity.title,
                    lastTimestamp = entity.lastTimestamp, currentMessageId = entity.currentMessageId,
                    parentSessionId = entity.parentSessionId, isDeleted = entity.isDeleted,
                    updatedAt = entity.updatedAt, id = entity.id
                )
            }
        }
    }

    suspend fun getMessagesModifiedAfter(timestamp: Long): List<MessageEntity> = withContext(Dispatchers.IO) {
        queries.selectMessagesModifiedAfter(timestamp).executeAsList()
    }

    suspend fun mergeMessageCrdt(entity: MessageEntity) = withContext(Dispatchers.IO) {
        database.transaction {
            val existing = queries.selectMessageForSync(entity.id).executeAsOneOrNull()
            if (existing == null) {
                queries.crdtInsertMessage(
                    id = entity.id, sessionId = entity.sessionId, role = entity.role, content = entity.content,
                    timestamp = entity.timestamp, parentId = entity.parentId, isActivePath = entity.isActivePath,
                    updatedAt = entity.updatedAt, isDeleted = entity.isDeleted, imageData = entity.imageData
                )
            } else if (entity.updatedAt > existing.updatedAt) {
                queries.crdtUpdateMessage(
                    sessionId = entity.sessionId, role = entity.role, content = entity.content,
                    timestamp = entity.timestamp, parentId = entity.parentId, isActivePath = entity.isActivePath,
                    isDeleted = entity.isDeleted, updatedAt = entity.updatedAt,
                    imageData = entity.imageData ?: existing.imageData, id = entity.id
                )
            }
        }
    }

    suspend fun getPromptBlocksModifiedAfter(timestamp: Long): List<PromptBlockEntity> = withContext(Dispatchers.IO) {
        queries.selectPromptBlocksModifiedAfter(timestamp).executeAsList()
    }

    suspend fun mergePromptBlockCrdt(entity: PromptBlockEntity) = withContext(Dispatchers.IO) {
        database.transaction {
            val existing = queries.selectPromptBlockForSync(entity.id).executeAsOneOrNull()
            if (existing == null) {
                queries.crdtInsertPromptBlock(
                    id = entity.id, name = entity.name, template = entity.template, isEnabled = entity.isEnabled,
                    isCustom = entity.isCustom, displayOrder = entity.displayOrder, updatedAt = entity.updatedAt, isDeleted = entity.isDeleted
                )
            } else if (entity.updatedAt > existing.updatedAt) {
                queries.crdtUpdatePromptBlock(
                    name = entity.name, template = entity.template, isEnabled = entity.isEnabled,
                    isCustom = entity.isCustom, displayOrder = entity.displayOrder, isDeleted = entity.isDeleted,
                    updatedAt = entity.updatedAt, id = entity.id
                )
            }
        }
    }
}