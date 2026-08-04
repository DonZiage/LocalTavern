package chat.donzi.localtavern.data.database

import chat.donzi.localtavern.data.blob.BlobStore
import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.domain.ImageRef
import chat.donzi.localtavern.domain.Message
import chat.donzi.localtavern.utils.Hashing
import chat.donzi.localtavern.utils.deserializeImageRefs
import chat.donzi.localtavern.utils.serializeImageRefs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

// All message-tree operations (read, insert, edit, delete, variation
// switching, greeting seeding) plus the image-blob wiring. Session rows are
// only touched through [sessionRepository] (current-message fix-ups must use
// the same logical timestamps the message wrote, so the session updates are
// stamped explicitly via stampSessionCurrentMessage, never re-stamped).
class MessageRepository(
    database: LocalTavernDB,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    clock: LogicalClock = LogicalClock(database),
    // Content-addressed blob store for message images. Null (tests, legacy
    // wiring) disables persistence: images are dropped on write and reads
    // hydrate nothing, but rows and refs stay consistent.
    private val blobStore: BlobStore? = null,
    private val sessionRepository: SessionRepository,
    private val readDispatcher: CoroutineDispatcher = ioDispatcher
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

    suspend fun getMessagesForSession(sessionId: String): List<Message> = withContext(readDispatcher) {
        queries.selectActiveTimeline(sessionId).executeAsList().map { it.toMessage() }
    }

    suspend fun getAllMessagesForSession(sessionId: String): List<Message> = withContext(readDispatcher) {
        queries.selectAllMessagesForSession(sessionId).executeAsList().map { it.toMessage() }
    }

    suspend fun getMessageSiblings(sessionId: String, parentId: String?): List<Message> = withContext(readDispatcher) {
        queries.selectSiblings(sessionId, parentId).executeAsList().map { it.toMessage() }
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
            sessionRepository.stampSessionCurrentMessage(sessionId, messageId, now, ts, seq)
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
            sessionRepository.stampSessionCurrentMessage(sessionId, newId, now, ts, seq)
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
                sessionRepository.stampSessionCurrentMessage(sessionId, it, now, ts, seq)
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
        // Blobs are content-addressed, so an image already stored is not
        // written twice; they can be persisted before the row update. The
        // read-combine-write of the refs list runs inside ONE transaction so
        // concurrent appends cannot lose each other's images.
        val newRefs = persistImages(newImages)
        database.transaction {
            val existingRefs = deserializeImageRefs(queries.selectMessageById(messageId).executeAsOneOrNull()?.imageRefs)
            val combined = existingRefs + newRefs
            queries.updateMessageImageRefs(
                imageRefs = serializeImageRefs(combined),
                updatedAt = nextTimestamp(),
                syncSeq = nextSyncSeq(),
                id = messageId
            )
        }
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
                    sessionRepository.stampSessionCurrentMessage(sessionId, message.parentId, now, ts, seq)
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
                            sessionRepository.stampSessionCurrentMessage(sessionId, existingMessage.parentId, now, ts, seq)
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
}
