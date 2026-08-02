package chat.donzi.localtavern.data.database

import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.domain.Message
import chat.donzi.localtavern.domain.Session
import chat.donzi.localtavern.utils.deserializeImageList
import chat.donzi.localtavern.utils.serializeImageList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

class SessionRepository(
    database: LocalTavernDB,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : BaseRepository(database) {

    suspend fun getSessionById(id: String): Session? = withContext(ioDispatcher) {
        queries.selectSessionById(id).executeAsOneOrNull()?.toDomain()
    }

    suspend fun getOrCreateSession(characterId: String, personaId: String): String = withContext(ioDispatcher) {
        val now = currentTimeMillis()
        // The lookup and the insert must be one atomic step: two concurrent
        // calls (e.g. a double-tap on send) must not both see "no session"
        // and each create a duplicate row.
        database.transactionWithResult {
            val session = queries.selectLastSessionForCharacterAndPersona(characterId, personaId).executeAsOneOrNull()
            if (session != null) {
                session.id
            } else {
                val newId = generateUuid()
                queries.insertChatSession(
                    id = newId, characterId = characterId, personaId = personaId, title = null,
                    lastTimestamp = now, currentMessageId = null, parentSessionId = null,
                    updatedAt = now, isDeleted = 0L
                )
                newId
            }
        }
    }

    suspend fun getMessagesForSession(sessionId: String): List<Message> = withContext(ioDispatcher) {
        queries.selectActiveTimeline(sessionId).executeAsList().map { it.toDomain() }
    }

    suspend fun getAllMessagesForSession(sessionId: String): List<Message> = withContext(ioDispatcher) {
        queries.selectAllMessagesForSession(sessionId).executeAsList().map { it.toDomain() }
    }

    suspend fun getMessageSiblings(sessionId: String, parentId: String?): List<Message> = withContext(ioDispatcher) {
        queries.selectSiblings(sessionId, parentId).executeAsList().map { it.toDomain() }
    }

    suspend fun updateSessionCurrentMessage(sessionId: String, messageId: String?) = withContext(ioDispatcher) {
        val now = currentTimeMillis()
        queries.updateSessionCurrentMessage(currentMessageId = messageId, lastTimestamp = now, updatedAt = now, id = sessionId)
    }

    suspend fun selectVariation(sessionId: String, messageId: String, parentId: String?) = withContext(ioDispatcher) {
        val now = currentTimeMillis()
        database.transaction {
            val allMessages = queries.selectAllMessagesForSession(sessionId).executeAsList()
            val ancestorIds = buildSet {
                var current = allMessages.find { it.id == messageId }
                while (current != null && add(current.id)) {
                    current = current.parentId?.let { pid -> allMessages.find { it.id == pid } }
                }
            }
            allMessages.filter { it.id !in ancestorIds }.forEach { msg ->
                queries.deactivateMessage(updatedAt = now, sessionId = sessionId, id = msg.id)
            }
            queries.activateMessage(updatedAt = now, id = messageId)
            queries.updateSessionCurrentMessage(currentMessageId = messageId, lastTimestamp = now, updatedAt = now, id = sessionId)
        }
    }

    suspend fun insertMessage(sessionId: String, role: String, content: String, parentId: String?, imageDataList: List<ByteArray>? = null): String = withContext(ioDispatcher) {
        val now = currentTimeMillis()
        database.transactionWithResult {
            val newId = generateUuid()
            queries.insertMessageWithParent(
                id = newId, sessionId = sessionId, role = role, content = content,
                timestamp = now, parentId = parentId, isActivePath = 1L,
                updatedAt = now, isDeleted = 0L, imageData = serializeImageList(imageDataList),
                reasoningText = null, costEstimate = null
            )
            queries.deactivateSiblings(updatedAt = now, sessionId = sessionId, parentId = parentId, id = newId)
            queries.updateSessionCurrentMessage(currentMessageId = newId, lastTimestamp = now, updatedAt = now, id = sessionId)
            newId
        }
    }

    suspend fun insertMessageRaw(sessionId: String, role: String, content: String, parentId: String?, isActivePath: Boolean, imageDataList: List<ByteArray>? = null): String = withContext(ioDispatcher) {
        val newId = generateUuid()
        val now = currentTimeMillis()
        queries.insertMessageWithParent(
            id = newId, sessionId = sessionId, role = role, content = content,
            timestamp = now, parentId = parentId, isActivePath = if (isActivePath) 1L else 0L,
            updatedAt = now, isDeleted = 0L, imageData = serializeImageList(imageDataList),
            reasoningText = null, costEstimate = null
        )
        newId
    }

    suspend fun ensureInitialGreetings(sessionId: String, character: Character) = withContext(ioDispatcher) {
        database.transaction {
            if (queries.selectAnyMessageForSession(sessionId).executeAsOne() > 0L) return@transaction

            val primaryGreeting = character.firstMes ?: ""
            val allGreetings = mutableListOf<String>()

            if (primaryGreeting.isNotBlank()) allGreetings.add(primaryGreeting)
            allGreetings.addAll(character.altGreetings)

            val now = currentTimeMillis()
            var primaryMessageId: String? = null
            allGreetings.forEachIndexed { index, greeting ->
                val isActive = index == 0
                // Monotonic timestamps keep the greeting swipe order stable
                // (all greetings inserted in the same millisecond otherwise tie).
                val msgId = insertGreetingMessageRaw(sessionId, "assistant", greeting, null, isActive, now + index)
                if (isActive) primaryMessageId = msgId
            }
            primaryMessageId?.let {
                val now = currentTimeMillis()
                queries.updateSessionCurrentMessage(currentMessageId = it, lastTimestamp = now, updatedAt = now, id = sessionId)
            }
        }
    }

    private fun insertGreetingMessageRaw(sessionId: String, role: String, content: String, parentId: String?, isActivePath: Boolean, timestamp: Long): String {
        val newId = generateUuid()
        queries.insertMessageWithParent(
            id = newId, sessionId = sessionId, role = role, content = content,
            timestamp = timestamp, parentId = parentId, isActivePath = if (isActivePath) 1L else 0L,
            updatedAt = timestamp, isDeleted = 0L, imageData = null,
            reasoningText = null, costEstimate = null
        )
        return newId
    }

    suspend fun updateMessageContent(id: String, content: String) = withContext(ioDispatcher) {
        queries.updateMessageContent(content = content, updatedAt = currentTimeMillis(), id = id)
    }

    suspend fun updateMessageReasoning(id: String, reasoningText: String?) = withContext(ioDispatcher) {
        queries.updateMessageReasoning(reasoningText = reasoningText, updatedAt = currentTimeMillis(), id = id)
    }

    suspend fun updateMessageCostEstimate(id: String, costEstimateUsd: Double?) = withContext(ioDispatcher) {
        queries.updateMessageCostEstimate(costEstimate = costEstimateUsd, updatedAt = currentTimeMillis(), id = id)
    }

    suspend fun updateMessageContentAndReasoning(id: String, content: String, reasoningText: String?) = withContext(ioDispatcher) {
        queries.updateMessageContentAndReasoning(
            content = content,
            reasoningText = reasoningText,
            updatedAt = currentTimeMillis(),
            id = id
        )
    }

    suspend fun updateMessageImage(id: String, imageDataList: List<ByteArray>?) = withContext(ioDispatcher) {
        queries.updateMessageImage(imageData = serializeImageList(imageDataList), updatedAt = currentTimeMillis(), id = id)
    }

    suspend fun appendImagesToMessage(sessionId: String, messageId: String, newImages: List<ByteArray>) = withContext(ioDispatcher) {
        // Read-modify-write inside a single transaction: two rapid "add image"
        // actions on the same message must not read the same base list and
        // drop each other's images.
        database.transaction {
            val existing = queries.selectMessageById(messageId).executeAsOneOrNull()?.imageData
            val combined = deserializeImageList(existing) + newImages
            queries.updateMessageImage(
                imageData = serializeImageList(combined),
                updatedAt = currentTimeMillis(),
                id = messageId
            )
        }
    }

    suspend fun deleteMessage(id: String) = withContext(ioDispatcher) {
        database.transaction {
            val message = queries.selectMessageById(id).executeAsOneOrNull()
            val now = currentTimeMillis()
            queries.deleteMessage(updatedAt = now, id = id)
            if (message != null) {
                val sessionId = message.sessionId
                val allMessages = queries.selectAllMessagesForSession(sessionId).executeAsList()
                val descendants = collectDescendantIds(allMessages, id)
                descendants.forEach { descendantId ->
                    queries.deactivateMessage(updatedAt = now, sessionId = sessionId, id = descendantId)
                }
                val session = queries.selectSessionById(sessionId).executeAsOneOrNull()
                val currentId = session?.currentMessageId
                if (currentId != null && (currentId == id || currentId in descendants)) {
                    queries.updateSessionCurrentMessage(
                        currentMessageId = message.parentId,
                        lastTimestamp = now,
                        updatedAt = now,
                        id = sessionId
                    )
                }
            }
        }
    }

    private fun collectDescendantIds(allMessages: List<MessageEntity>, rootId: String): Set<String> {
        val result = mutableSetOf<String>()
        fun collect(parentId: String) {
            allMessages.filter { it.parentId == parentId }.forEach { child ->
                if (result.add(child.id)) collect(child.id)
            }
        }
        collect(rootId)
        return result
    }

    // Syncs the character's greeting roots of one session inside a single
    // transaction. Editing a character can fire saves from multiple paths at
    // once (debounced autosave, close-time flush); without the transaction two
    // concurrent saves could read the same root list and insert the same
    // greeting root twice.
    suspend fun syncGreetingRoots(sessionId: String, textList: List<String>) = withContext(ioDispatcher) {
        val now = currentTimeMillis()
        database.transaction {
            val allMessages = queries.selectAllMessagesForSession(sessionId).executeAsList()
            val currentRoots = allMessages.filter { it.parentId == null && it.isActivePath == 1L }

            currentRoots.forEachIndexed { index, existingMessage ->
                if (index < textList.size) {
                    queries.updateMessageContent(content = textList[index], updatedAt = now, id = existingMessage.id)
                } else {
                    // Only delete a root that is not part of a live conversation.
                    // Deleting a root cascades to its entire subtree (deactivating
                    // every descendant), which would make the session look empty
                    // even though its chat history is intact.
                    val hasActiveConversation = allMessages.any { it.parentId == existingMessage.id && it.isActivePath == 1L }
                    if (!hasActiveConversation) {
                        val descendants = collectDescendantIds(allMessages, existingMessage.id)
                        queries.deleteMessage(updatedAt = now, id = existingMessage.id)
                        descendants.forEach { descendantId ->
                            queries.deactivateMessage(updatedAt = now, sessionId = sessionId, id = descendantId)
                        }
                        val session = queries.selectSessionById(sessionId).executeAsOneOrNull()
                        val currentId = session?.currentMessageId
                        if (currentId != null && (currentId == existingMessage.id || currentId in descendants)) {
                            queries.updateSessionCurrentMessage(
                                currentMessageId = existingMessage.parentId,
                                lastTimestamp = now,
                                updatedAt = now,
                                id = sessionId
                            )
                        }
                    }
                }
            }

            if (textList.size > currentRoots.size) {
                for (i in currentRoots.size until textList.size) {
                    // When no active root survived the sync (e.g. the old greeting
                    // roots were deleted or never existed), the first replacement
                    // must be ACTIVE: a session whose roots are all inactive shows
                    // an empty chat with no way to reach the new greeting.
                    val activateFirst = currentRoots.isEmpty() && i == currentRoots.size
                    val newId = generateUuid()
                    // Monotonic timestamps keep the greeting swipe order stable
                    // (all roots inserted in the same millisecond otherwise tie).
                    queries.insertMessageWithParent(
                        id = newId, sessionId = sessionId, role = "assistant", content = textList[i],
                        timestamp = now + i, parentId = null, isActivePath = if (activateFirst) 1L else 0L,
                        updatedAt = now, isDeleted = 0L, imageData = null,
                        reasoningText = null, costEstimate = null
                    )
                }
            }
        }
    }

    suspend fun getSessionsForCharacter(characterId: String): List<Session> = withContext(ioDispatcher) {
        queries.selectSessionsForCharacter(characterId).executeAsList().map { it.toDomain() }
    }

    suspend fun createNewSession(characterId: String, personaId: String): String = withContext(ioDispatcher) {
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
        messagesToCopy: List<Message>,
        newTitle: String
    ): String = withContext(ioDispatcher) {
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
                    updatedAt = now, isDeleted = 0L, imageData = serializeImageList(msg.images),
                    reasoningText = msg.reasoningText, costEstimate = msg.costEstimateUsd
                )
                lastInsertedNewId = newMsgId
            }

            if (lastInsertedNewId != null) {
                queries.updateSessionCurrentMessage(currentMessageId = lastInsertedNewId, lastTimestamp = now, updatedAt = now, id = newSessionId)
            }

            newSessionId
        }
    }

    suspend fun deleteSession(sessionId: String) = withContext(ioDispatcher) {
        val now = currentTimeMillis()
        database.transaction {
            queries.deleteMessagesForSession(updatedAt = now, sessionId = sessionId)
            queries.deleteSession(updatedAt = now, id = sessionId)
        }
    }

    // Sessions of deleted characters/personas must not linger: they would
    // keep being returned by session queries and could be resumed against an
    // entity the UI can no longer load.
    suspend fun deleteSessionsForCharacters(characterIds: Set<String>) = withContext(ioDispatcher) {
        if (characterIds.isEmpty()) return@withContext
        val now = currentTimeMillis()
        database.transaction {
            characterIds.forEach { characterId ->
                queries.selectSessionsForCharacter(characterId).executeAsList().forEach { session ->
                    queries.deleteMessagesForSession(updatedAt = now, sessionId = session.id)
                    queries.deleteSession(updatedAt = now, id = session.id)
                }
            }
        }
    }

    suspend fun deleteSessionsForPersona(personaId: String) = withContext(ioDispatcher) {
        val now = currentTimeMillis()
        database.transaction {
            queries.selectSessionsForPersona(personaId).executeAsList().forEach { session ->
                queries.deleteMessagesForSession(updatedAt = now, sessionId = session.id)
                queries.deleteSession(updatedAt = now, id = session.id)
            }
        }
    }

    suspend fun updateSessionTitle(sessionId: String, title: String?) = withContext(ioDispatcher) {
        queries.updateSessionTitle(title = title, updatedAt = currentTimeMillis(), id = sessionId)
    }
}
