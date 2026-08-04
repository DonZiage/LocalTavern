package chat.donzi.localtavern.data.database

import chat.donzi.localtavern.data.blob.BlobStore
import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.domain.ImageRef
import chat.donzi.localtavern.domain.Message
import chat.donzi.localtavern.domain.Session
import chat.donzi.localtavern.utils.Hashing
import chat.donzi.localtavern.utils.deserializeImageRefs
import chat.donzi.localtavern.utils.serializeImageRefs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

class SessionRepository(
    database: LocalTavernDB,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    clock: LogicalClock = LogicalClock(database),
    // Content-addressed blob store for message images. Null (tests, legacy
    // wiring) disables persistence: images are dropped on write and reads
    // hydrate nothing, but rows and refs stay consistent.
    private val blobStore: BlobStore? = null
) : BaseRepository(database, clock) {

    // Writes every image to the store (content-addressed, deduplicated) and
    // returns the references to persist on the row. Must run BEFORE the row
    // write so a crash never leaves a row referencing a missing blob.
    private suspend fun persistImages(images: List<ByteArray>): List<ImageRef> {
        val store = blobStore ?: return emptyList()
        return images.map { img ->
            val hash = Hashing.sha256Hex(img)
            if (store.read(hash) == null) store.write(hash, img)
            ImageRef(hash, img.size.toLong())
        }
    }

    // Hydrates a row's references from the store; blobs that are missing
    // (e.g. pending sync) yield nothing here and are shown as placeholders.
    private suspend fun MessageEntity.toMessage(): Message =
        toDomain().copy(
            images = blobStore?.let { store ->
                deserializeImageRefs(imageRefs).mapNotNull { store.read(it.sha256) }
            } ?: emptyList()
        )

    suspend fun getSessionById(id: String): Session? = withContext(ioDispatcher) {
        queries.selectSessionById(id).executeAsOneOrNull()?.toDomain()
    }

    suspend fun getOrCreateSession(characterId: String, personaId: String): String = withContext(ioDispatcher) {
        val now = currentTimeMillis()
        val ts = nextTimestamp()
        // The lookup and the insert must be one atomic step: two concurrent
        // calls (e.g. a double-tap on send) must not both see "no session"
        // and each create a duplicate row.
        database.transactionWithResult {
            val session = queries.selectLastSessionForCharacterAndPersona(characterId, personaId).executeAsOneOrNull()
            if (session != null) {
                session.id
            } else {
                val newId = generateUuid()
                val seq = nextSyncSeq()
                queries.insertChatSession(
                    id = newId, characterId = characterId, personaId = personaId, title = null,
                    lastTimestamp = now, currentMessageId = null, parentSessionId = null,
                    updatedAt = ts, isDeleted = 0L, syncSeq = seq
                )
                newId
            }
        }
    }

    suspend fun getMessagesForSession(sessionId: String): List<Message> = withContext(ioDispatcher) {
        queries.selectActiveTimeline(sessionId).executeAsList().map { it.toMessage() }
    }

    suspend fun getAllMessagesForSession(sessionId: String): List<Message> = withContext(ioDispatcher) {
        queries.selectAllMessagesForSession(sessionId).executeAsList().map { it.toMessage() }
    }

    suspend fun getMessageSiblings(sessionId: String, parentId: String?): List<Message> = withContext(ioDispatcher) {
        queries.selectSiblings(sessionId, parentId).executeAsList().map { it.toMessage() }
    }

    suspend fun updateSessionCurrentMessage(sessionId: String, messageId: String?) = withContext(ioDispatcher) {
        val now = currentTimeMillis()
        val ts = nextTimestamp()
        queries.updateSessionCurrentMessage(currentMessageId = messageId, lastTimestamp = now, updatedAt = ts, syncSeq = nextSyncSeq(), id = sessionId)
    }

    suspend fun selectVariation(sessionId: String, messageId: String, parentId: String?) = withContext(ioDispatcher) {
        val now = currentTimeMillis()
        val ts = nextTimestamp()
        val seq = nextSyncSeq()
        database.transaction {
            val allMessages = queries.selectAllMessagesForSession(sessionId).executeAsList()
            val ancestorIds = buildSet {
                var current = allMessages.find { it.id == messageId }
                while (current != null && add(current.id)) {
                    current = current.parentId?.let { pid -> allMessages.find { it.id == pid } }
                }
            }
            allMessages.filter { it.id !in ancestorIds }.forEach { msg ->
                queries.deactivateMessage(updatedAt = ts, syncSeq = seq, sessionId = sessionId, id = msg.id)
            }
            queries.activateMessage(updatedAt = ts, syncSeq = seq, id = messageId)
            queries.updateSessionCurrentMessage(currentMessageId = messageId, lastTimestamp = now, updatedAt = ts, syncSeq = seq, id = sessionId)
        }
    }

    suspend fun insertMessage(sessionId: String, role: String, content: String, parentId: String?, imageDataList: List<ByteArray>? = null): String = withContext(ioDispatcher) {
        val now = currentTimeMillis()
        val ts = nextTimestamp()
        val seq = nextSyncSeq()
        // Blobs go to the store before the row is inserted (crash-safe order).
        val refs = persistImages(imageDataList.orEmpty())
        database.transactionWithResult {
            val newId = generateUuid()
            queries.insertMessageWithParent(
                id = newId, sessionId = sessionId, role = role, content = content,
                timestamp = now, parentId = parentId, isActivePath = 1L,
                updatedAt = ts, isDeleted = 0L, imageRefs = serializeImageRefs(refs),
                reasoningText = null, costEstimate = null, syncSeq = seq
            )
            queries.deactivateSiblings(updatedAt = ts, syncSeq = seq, sessionId = sessionId, parentId = parentId, id = newId)
            queries.updateSessionCurrentMessage(currentMessageId = newId, lastTimestamp = now, updatedAt = ts, syncSeq = seq, id = sessionId)
            newId
        }
    }

    suspend fun insertMessageRaw(sessionId: String, role: String, content: String, parentId: String?, isActivePath: Boolean, imageDataList: List<ByteArray>? = null): String = withContext(ioDispatcher) {
        val newId = generateUuid()
        val now = currentTimeMillis()
        val ts = nextTimestamp()
        val seq = nextSyncSeq()
        val refs = persistImages(imageDataList.orEmpty())
        queries.insertMessageWithParent(
            id = newId, sessionId = sessionId, role = role, content = content,
            timestamp = now, parentId = parentId, isActivePath = if (isActivePath) 1L else 0L,
            updatedAt = ts, isDeleted = 0L, imageRefs = serializeImageRefs(refs),
            reasoningText = null, costEstimate = null, syncSeq = seq
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
            val ts = nextTimestamp()
            val seq = nextSyncSeq()
            var primaryMessageId: String? = null
            allGreetings.forEachIndexed { index, greeting ->
                val isActive = index == 0
                // Monotonic timestamps keep the greeting swipe order stable
                // (all greetings inserted in the same millisecond otherwise tie).
                val msgId = insertGreetingMessageRaw(sessionId, "assistant", greeting, null, isActive, now + index, ts, seq)
                if (isActive) primaryMessageId = msgId
            }
            primaryMessageId?.let {
                val now = currentTimeMillis()
                queries.updateSessionCurrentMessage(currentMessageId = it, lastTimestamp = now, updatedAt = ts, syncSeq = seq, id = sessionId)
            }
        }
    }

    private fun insertGreetingMessageRaw(sessionId: String, role: String, content: String, parentId: String?, isActivePath: Boolean, timestamp: Long, updatedAt: Long, syncSeq: Long): String {
        val newId = generateUuid()
        queries.insertMessageWithParent(
            id = newId, sessionId = sessionId, role = role, content = content,
            timestamp = timestamp, parentId = parentId, isActivePath = if (isActivePath) 1L else 0L,
            updatedAt = updatedAt, isDeleted = 0L, imageRefs = null,
            reasoningText = null, costEstimate = null, syncSeq = syncSeq
        )
        return newId
    }

    suspend fun updateMessageContent(id: String, content: String) = withContext(ioDispatcher) {
        queries.updateMessageContent(content = content, updatedAt = nextTimestamp(), syncSeq = nextSyncSeq(), id = id)
    }

    suspend fun updateMessageReasoning(id: String, reasoningText: String?) = withContext(ioDispatcher) {
        queries.updateMessageReasoning(reasoningText = reasoningText, updatedAt = nextTimestamp(), syncSeq = nextSyncSeq(), id = id)
    }

    suspend fun updateMessageCostEstimate(id: String, costEstimateUsd: Double?) = withContext(ioDispatcher) {
        queries.updateMessageCostEstimate(costEstimate = costEstimateUsd, updatedAt = nextTimestamp(), syncSeq = nextSyncSeq(), id = id)
    }

    suspend fun updateMessageContentAndReasoning(id: String, content: String, reasoningText: String?) = withContext(ioDispatcher) {
        queries.updateMessageContentAndReasoning(
            content = content,
            reasoningText = reasoningText,
            updatedAt = nextTimestamp(),
            syncSeq = nextSyncSeq(),
            id = id
        )
    }

    suspend fun updateMessageImage(id: String, imageDataList: List<ByteArray>?) = withContext(ioDispatcher) {
        val ts = nextTimestamp()
        val seq = nextSyncSeq()
        val refs = persistImages(imageDataList.orEmpty())
        queries.updateMessageImageRefs(imageRefs = serializeImageRefs(refs), updatedAt = ts, syncSeq = seq, id = id)
    }

    suspend fun appendImagesToMessage(sessionId: String, messageId: String, newImages: List<ByteArray>) = withContext(ioDispatcher) {
        // All calls serialize on this repository's single dispatcher, so the
        // read-combine-write sequence is atomic in practice; blobs are
        // content-addressed, so an image already stored is not written twice.
        val existingRefs = deserializeImageRefs(queries.selectMessageById(messageId).executeAsOneOrNull()?.imageRefs)
        val newRefs = persistImages(newImages)
        val combined = existingRefs + newRefs
        queries.updateMessageImageRefs(
            imageRefs = serializeImageRefs(combined),
            updatedAt = nextTimestamp(),
            syncSeq = nextSyncSeq(),
            id = messageId
        )
    }

    suspend fun deleteMessage(id: String) = withContext(ioDispatcher) {
        database.transaction {
            val message = queries.selectMessageById(id).executeAsOneOrNull()
            val now = currentTimeMillis()
            val ts = nextTimestamp()
            val seq = nextSyncSeq()
            queries.deleteMessage(updatedAt = ts, syncSeq = seq, id = id)
            if (message != null) {
                val sessionId = message.sessionId
                val allMessages = queries.selectAllMessagesForSession(sessionId).executeAsList()
                val descendants = collectDescendantIds(allMessages, id)
                descendants.forEach { descendantId ->
                    queries.deactivateMessage(updatedAt = ts, syncSeq = seq, sessionId = sessionId, id = descendantId)
                }
                val session = queries.selectSessionById(sessionId).executeAsOneOrNull()
                val currentId = session?.currentMessageId
                if (currentId != null && (currentId == id || currentId in descendants)) {
                    queries.updateSessionCurrentMessage(
                        currentMessageId = message.parentId,
                        lastTimestamp = now,
                        updatedAt = ts,
                        syncSeq = seq,
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
        val ts = nextTimestamp()
        val seq = nextSyncSeq()
        database.transaction {
            val allMessages = queries.selectAllMessagesForSession(sessionId).executeAsList()
            val currentRoots = allMessages.filter { it.parentId == null && it.isActivePath == 1L }

            currentRoots.forEachIndexed { index, existingMessage ->
                if (index < textList.size) {
                    queries.updateMessageContent(content = textList[index], updatedAt = ts, syncSeq = seq, id = existingMessage.id)
                } else {
                    // Only delete a root that is not part of a live conversation.
                    // Deleting a root cascades to its entire subtree (deactivating
                    // every descendant), which would make the session look empty
                    // even though its chat history is intact.
                    val hasActiveConversation = allMessages.any { it.parentId == existingMessage.id && it.isActivePath == 1L }
                    if (!hasActiveConversation) {
                        val descendants = collectDescendantIds(allMessages, existingMessage.id)
                        queries.deleteMessage(updatedAt = ts, syncSeq = seq, id = existingMessage.id)
                        descendants.forEach { descendantId ->
                            queries.deactivateMessage(updatedAt = ts, syncSeq = seq, sessionId = sessionId, id = descendantId)
                        }
                        val session = queries.selectSessionById(sessionId).executeAsOneOrNull()
                        val currentId = session?.currentMessageId
                        if (currentId != null && (currentId == existingMessage.id || currentId in descendants)) {
                            queries.updateSessionCurrentMessage(
                                currentMessageId = existingMessage.parentId,
                                lastTimestamp = now,
                                updatedAt = ts,
                                syncSeq = seq,
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
                        updatedAt = ts, isDeleted = 0L, imageRefs = null,
                        reasoningText = null, costEstimate = null, syncSeq = seq
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
        val ts = nextTimestamp()
        val seq = nextSyncSeq()
        queries.insertChatSession(
            id = newId, characterId = characterId, personaId = personaId, title = null,
            lastTimestamp = now, currentMessageId = null, parentSessionId = null,
            updatedAt = ts, isDeleted = 0L, syncSeq = seq
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
        val ts = nextTimestamp()
        database.transactionWithResult {
            val originalSession = queries.selectSessionById(originalSessionId).executeAsOneOrNull()
                ?: throw IllegalArgumentException("Original session not found")

            val newSessionId = generateUuid()
            val seq = nextSyncSeq()
            queries.insertChatSession(
                id = newSessionId,
                characterId = originalSession.characterId,
                personaId = originalSession.personaId,
                title = newTitle,
                lastTimestamp = now,
                currentMessageId = null,
                parentSessionId = originalSessionId,
                updatedAt = ts,
                isDeleted = 0L,
                syncSeq = seq
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
                    updatedAt = ts, isDeleted = 0L, imageRefs = serializeImageRefs(msg.imageRefs),
                    reasoningText = msg.reasoningText, costEstimate = msg.costEstimateUsd, syncSeq = seq
                )
                lastInsertedNewId = newMsgId
            }

            if (lastInsertedNewId != null) {
                queries.updateSessionCurrentMessage(currentMessageId = lastInsertedNewId, lastTimestamp = now, updatedAt = ts, syncSeq = seq, id = newSessionId)
            }

            newSessionId
        }
    }

    suspend fun deleteSession(sessionId: String) = withContext(ioDispatcher) {
        val ts = nextTimestamp()
        val seq = nextSyncSeq()
        database.transaction {
            queries.deleteMessagesForSession(updatedAt = ts, syncSeq = seq, sessionId = sessionId)
            queries.deleteSession(updatedAt = ts, syncSeq = seq, id = sessionId)
        }
    }

    // Sessions of deleted characters/personas must not linger: they would
    // keep being returned by session queries and could be resumed against an
    // entity the UI can no longer load.
    suspend fun deleteSessionsForCharacters(characterIds: Set<String>) = withContext(ioDispatcher) {
        if (characterIds.isEmpty()) return@withContext
        val ts = nextTimestamp()
        val seq = nextSyncSeq()
        database.transaction {
            characterIds.forEach { characterId ->
                queries.selectSessionsForCharacter(characterId).executeAsList().forEach { session ->
                    queries.deleteMessagesForSession(updatedAt = ts, syncSeq = seq, sessionId = session.id)
                    queries.deleteSession(updatedAt = ts, syncSeq = seq, id = session.id)
                }
            }
        }
    }

    suspend fun deleteSessionsForPersona(personaId: String) = withContext(ioDispatcher) {
        val ts = nextTimestamp()
        val seq = nextSyncSeq()
        database.transaction {
            queries.selectSessionsForPersona(personaId).executeAsList().forEach { session ->
                queries.deleteMessagesForSession(updatedAt = ts, syncSeq = seq, sessionId = session.id)
                queries.deleteSession(updatedAt = ts, syncSeq = seq, id = session.id)
            }
        }
    }

    suspend fun updateSessionTitle(sessionId: String, title: String?) = withContext(ioDispatcher) {
        queries.updateSessionTitle(title = title, updatedAt = nextTimestamp(), syncSeq = nextSyncSeq(), id = sessionId)
    }
}
