package chat.donzi.localtavern.data.database

import chat.donzi.localtavern.domain.Message
import chat.donzi.localtavern.domain.Session
import chat.donzi.localtavern.utils.serializeImageRefs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

// Session-row operations only: CRUD, lookups, branching. Message-tree and
// image operations live in MessageRepository; the two repositories share the
// same database, dispatcher and logical clock, so their statements always
// run on the same single connection.
class SessionRepository(
    database: LocalTavernDB,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    clock: LogicalClock = LogicalClock(database),
    private val readDispatcher: CoroutineDispatcher = ioDispatcher
) : BaseRepository(database, clock) {

    suspend fun getSessionById(id: String): Session? = withContext(readDispatcher) {
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

    suspend fun updateSessionCurrentMessage(sessionId: String, messageId: String?) = withContext(ioDispatcher) {
        val now = currentTimeMillis()
        val ts = nextTimestamp()
        queries.updateSessionCurrentMessage(currentMessageId = messageId, lastTimestamp = now, updatedAt = ts, syncSeq = nextSyncSeq(), id = sessionId)
    }

    // Same-stamp variant used by MessageRepository inside its transactions:
    // the session row must carry the exact same logical timestamp and sync
    // sequence as the message row that changed it, or LWW/sync deltas would
    // treat them as independent edits. Non-suspend because transaction
    // bodies are plain lambdas; callers already run on the DB dispatcher.
    internal fun stampSessionCurrentMessage(sessionId: String, messageId: String?, lastTimestamp: Long, updatedAt: Long, syncSeq: Long) {
        queries.updateSessionCurrentMessage(currentMessageId = messageId, lastTimestamp = lastTimestamp, updatedAt = updatedAt, syncSeq = syncSeq, id = sessionId)
    }

    suspend fun getSessionsForCharacter(characterId: String): List<Session> = withContext(readDispatcher) {
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
