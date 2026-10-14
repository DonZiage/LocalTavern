package chat.donzi.localtavern.data.database

import chat.donzi.localtavern.data.models.SillyTavernCardV2
import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.domain.Persona
import chat.donzi.localtavern.utils.ImportedCharacter
import chat.donzi.localtavern.utils.CharacterManager
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.encodeToString

private val cardJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

private fun encodeJsonObject(value: JsonObject?): String? = value?.let { cardJson.encodeToString(it) }

private fun encodeTags(tags: List<String>): String? = tags.takeIf { it.isNotEmpty() }?.let { cardJson.encodeToString(it) }

class CharacterRepository(
    database: LocalTavernDB,
    clock: LogicalClock = LogicalClock(database),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val readDispatcher: CoroutineDispatcher = ioDispatcher
) : BaseRepository(database, clock) {

    // Rows per sub-transaction in importCharacters: bounds the write buffers
    // SQLite holds open for one commit. The cut is ALSO byte-budgeted on the
    // buffered payload (avatar bytes plus card text): a chunk of cards with
    // multi-MB avatars or lorebooks would otherwise pin hundreds of MB in
    // the write buffers on a mobile device (OOM crash on a heavy library
    // import). A single card larger than the byte budget still commits alone
    // (progress is guaranteed).
    private companion object {
        const val IMPORT_TRANSACTION_ROWS = 64
        const val IMPORT_TRANSACTION_BYTES = 16L * 1024 * 1024
        // Chunk size for CursorWindow-safe blob reassembly: far below the
        // ~2 MB Android CursorWindow so each substr() row always fits, large
        // enough that chunk loops stay cheap.
        const val AVATAR_CHUNK_BYTES = 512L * 1024
    }

    fun observeCharacters(): Flow<List<Character>> =
        queries.selectAllCharacters().asFlow().mapToList(readDispatcher).map { list -> list.map { it.toDomain() } }

    // Avatar-free list flow for the startup screens: observing the whole
    // library must not pin every avatar on the heap. Visible rows load their
    // bytes on demand (see getCharacterAvatarData).
    fun observeCharacterHeaders(): Flow<List<Character>> =
        queries.selectAllCharacterHeaders().asFlow().mapToList(readDispatcher).map { list -> list.map { it.toDomain() } }

    fun observePersonas(): Flow<List<Persona>> =
        queries.selectAllPersonas().asFlow().mapToList(readDispatcher).map { list -> list.map { it.toDomain() } }

    // Avatar-free persona list flow (same rule as the character list).
    fun observePersonaHeaders(): Flow<List<Persona>> =
        queries.selectAllPersonaHeaders().asFlow().mapToList(readDispatcher).map { list -> list.map { it.toDomain() } }

    suspend fun getAllCharacters(): List<Character> = withContext(readDispatcher) {
        queries.selectAllCharacters().executeAsList().map { it.toDomain() }
    }

    // Avatar-free snapshot for startup and list screens.
    suspend fun getAllCharacterHeaders(): List<Character> = withContext(readDispatcher) {
        queries.selectAllCharacterHeaders().executeAsList().map { it.toDomain() }
    }

    // One row's avatar bytes for a visible list row, editor or chat header.
    // CursorWindow-safe: avatars are phone PNGs that routinely exceed
    // Android's ~2 MB CursorWindow, which crashes ANY cursor that
    // materializes such a row whole ("Row too big to fit into CursorWindow"
    // at unlock/startup). Small avatars take the fast single-row path;
    // large ones are reassembled in small substr() chunks, each far below
    // the window. SQLite substr() is 1-indexed.
    suspend fun getCharacterAvatarData(id: String): ByteArray? = withContext(readDispatcher) {
        val length = queries.selectCharacterAvatarLength(id).executeAsOneOrNull()?.LENGTH
            ?: return@withContext queries.selectCharacterAvatarData(id).executeAsOneOrNull()?.avatarData
        if (length <= AVATAR_CHUNK_BYTES) {
            return@withContext queries.selectCharacterAvatarData(id).executeAsOneOrNull()?.avatarData
        }
        if (length <= 0L) return@withContext null
        val out = ByteArray(length.toInt())
        var offset = 1L
        var dest = 0
        while (offset <= length) {
            val size = minOf(AVATAR_CHUNK_BYTES, length - offset + 1)
            // substr() bind params are TEXT-typed by SQLDelight; SQLite
            // coerces the numeric strings (substr affinity conversion).
            val chunk = queries.selectCharacterAvatarChunk(offset.toString(), size.toString(), id)
                .executeAsOneOrNull()?.substr
                ?: return@withContext null
            chunk.copyInto(out, dest)
            dest += chunk.size
            offset += chunk.size
            // Defensive: a short chunk that is not the tail means the row
            // changed under us; stop instead of looping forever.
            if (chunk.size < size && offset <= length) break
        }
        // The row may have been edited concurrently; trim to what landed.
        if (dest == out.size) out else out.copyOf(dest)
    }

    suspend fun getAssistant(): Character? = withContext(readDispatcher) {
        queries.selectAssistant().executeAsOneOrNull()?.toDomain()
    }

    suspend fun createAssistant(): String = withContext(ioDispatcher) {
        val newId = generateUuid()
        val now = nextTimestamp()
        val seq = nextSyncSeq()
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
            isDeleted = 0L,
            systemPrompt = null,
            postHistoryInstructions = null,
            creator = null,
            characterVersion = null,
            tags = null,
            extensions = null,
            characterBook = null,
            syncSeq = seq
        )
        newId
    }

    suspend fun getCharacterById(id: String): Character? = withContext(readDispatcher) {
        queries.selectCharacterById(id).executeAsOneOrNull()?.toDomain()
    }

    suspend fun upsertCharacter(
        card: SillyTavernCardV2,
        avatarData: ByteArray? = null
    ): String = withContext(ioDispatcher) {
        val newId = generateUuid()
        val now = nextTimestamp()
        val seq = nextSyncSeq()
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
            isDeleted = 0L,
            systemPrompt = card.system_prompt,
            postHistoryInstructions = card.post_history_instructions,
            creator = card.creator,
            characterVersion = card.character_version,
            tags = encodeTags(card.tags),
            extensions = encodeJsonObject(card.extensions),
            characterBook = encodeJsonObject(card.character_book),
            syncSeq = seq
        )
        newId
    }

    // Bulk import entry point for the mass-import flow: every card is
    // inserted inside one transaction so a large batch commits atomically
    // and sync sees a single change batch. Duplicate display names are kept
    // (each row gets its own UUID, matching single-character imports).
    //
    // The batch is committed in bounded sub-transactions: a single
    // transaction holding tens of thousands of rows (with per-row HLC and
    // sync-sequence statements) would pin the whole batch in SQLite's write
    // buffers on a mobile device. Sub-commits keep memory flat; a crash
    // mid-import only loses the tail of the batch, and re-importing is
    // idempotent (fresh UUIDs).
    suspend fun importCharacters(characters: List<ImportedCharacter>): Int = withContext(ioDispatcher) {
        if (characters.isEmpty()) return@withContext 0
        var count = 0
        var index = 0
        while (index < characters.size) {
            var bytes = 0L
            var end = index
            while (end < characters.size && end - index < IMPORT_TRANSACTION_ROWS) {
                // Avatar bytes plus card text (see AppState's import chunking):
                // text-heavy cards must cut the transaction too.
                val payloadSize = (characters[end].avatarData?.size?.toLong() ?: 0L) +
                    CharacterManager.cardTextSize(characters[end].card)
                if (end > index && bytes + payloadSize > IMPORT_TRANSACTION_BYTES) break
                bytes += payloadSize
                end++
            }
            val chunk = characters.subList(index, end)
            database.transaction {
                chunk.forEach { imported ->
                    val card = imported.card
                    val seq = nextSyncSeq()
                    queries.insertCharacter(
                        id = generateUuid(),
                        name = card.name,
                        description = card.description,
                        personality = card.personality,
                        scenario = card.scenario,
                        firstMes = card.first_mes,
                        mesExample = card.mes_example,
                        creatorNotes = card.creator_notes,
                        altGreetings = card.alternate_greetings.joinToString("|||").ifBlank { null },
                        avatarData = imported.avatarData,
                        isAssistant = 0L,
                        updatedAt = nextTimestamp(),
                        isDeleted = 0L,
                        systemPrompt = card.system_prompt,
                        postHistoryInstructions = card.post_history_instructions,
                        creator = card.creator,
                        characterVersion = card.character_version,
                        tags = encodeTags(card.tags),
                        extensions = encodeJsonObject(card.extensions),
                        characterBook = encodeJsonObject(card.character_book),
                        syncSeq = seq
                    )
                    count++
                }
            }
            index = end
        }
        count
    }

    suspend fun createCharacter(name: String): String = withContext(ioDispatcher) {
        val newId = generateUuid()
        val now = nextTimestamp()
        val seq = nextSyncSeq()
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
            isDeleted = 0L,
            systemPrompt = null,
            postHistoryInstructions = null,
            creator = null,
            characterVersion = null,
            tags = null,
            extensions = null,
            characterBook = null,
            syncSeq = seq
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
    ) = withContext(ioDispatcher) {
        // The character editor does not touch the round-trip-only card fields;
        // carry the stored values forward so a save cannot strip them.
        val existing = queries.selectCharacterById(id).executeAsOneOrNull()
        // A local avatar change (set or removed) supersedes any unresolved
        // out-of-band ref on the row; the ref must not resurrect the old
        // avatar. Untouched edits (the ref carried forward unchanged) keep it,
        // so a pending avatar is not lost by an unrelated save.
        if (avatarData != existing?.avatarData) queries.clearCharacterAvatarRef(id)
        queries.updateCharacter(
            name = name,
            description = description,
            personality = personality,
            scenario = scenario,
            firstMes = firstMes,
            mesExample = mesExample.joinToString("|||").ifBlank { null },
            altGreetings = altGreetings.joinToString("|||").ifBlank { null },
            avatarData = avatarData,
            systemPrompt = existing?.systemPrompt,
            postHistoryInstructions = existing?.postHistoryInstructions,
            creator = existing?.creator,
            characterVersion = existing?.characterVersion,
            tags = existing?.tags,
            extensions = existing?.extensions,
            characterBook = existing?.characterBook,
            updatedAt = nextTimestamp(),
            syncSeq = nextSyncSeq(),
            id = id
        )
    }

    suspend fun deleteCharacters(ids: Collection<String>) = withContext(ioDispatcher) {
        if (ids.isEmpty()) return@withContext
        queries.deleteCharactersByIds(
            updatedAt = nextTimestamp(),
            syncSeq = nextSyncSeq(),
            id = ids.toList()
        )
    }

    suspend fun updateCharacterLorebook(id: String, characterBook: JsonObject?) = withContext(ioDispatcher) {
        val existing = queries.selectCharacterById(id).executeAsOneOrNull() ?: return@withContext
        // Only the round-trip characterBook field changes; carry everything
        // else forward so the lorebook save cannot strip card metadata.
        queries.updateCharacter(
            name = existing.name,
            description = existing.description,
            personality = existing.personality ?: "",
            scenario = existing.scenario ?: "",
            firstMes = existing.firstMes,
            mesExample = existing.mesExample,
            altGreetings = existing.altGreetings,
            avatarData = existing.avatarData,
            systemPrompt = existing.systemPrompt,
            postHistoryInstructions = existing.postHistoryInstructions,
            creator = existing.creator,
            characterVersion = existing.characterVersion,
            tags = existing.tags,
            extensions = existing.extensions,
            characterBook = encodeJsonObject(characterBook),
            updatedAt = nextTimestamp(),
            syncSeq = nextSyncSeq(),
            id = id
        )
    }

    suspend fun getAllPersonas(): List<Persona> = withContext(readDispatcher) {
        queries.selectAllPersonas().executeAsList().map { it.toDomain() }
    }

    // Avatar-free persona snapshot for startup and list screens.
    suspend fun getAllPersonaHeaders(): List<Persona> = withContext(readDispatcher) {
        queries.selectAllPersonaHeaders().executeAsList().map { it.toDomain() }
    }

    // One persona's avatar bytes for a visible card or the chat header.
    // CursorWindow-safe chunked reassembly (see getCharacterAvatarData).
    suspend fun getPersonaAvatarData(id: String): ByteArray? = withContext(readDispatcher) {
        val length = queries.selectPersonaAvatarLength(id).executeAsOneOrNull()?.LENGTH
            ?: return@withContext queries.selectPersonaAvatarData(id).executeAsOneOrNull()?.avatarData
        if (length <= AVATAR_CHUNK_BYTES) {
            return@withContext queries.selectPersonaAvatarData(id).executeAsOneOrNull()?.avatarData
        }
        if (length <= 0L) return@withContext null
        val out = ByteArray(length.toInt())
        var offset = 1L
        var dest = 0
        while (offset <= length) {
            val size = minOf(AVATAR_CHUNK_BYTES, length - offset + 1)
            // substr() bind params are TEXT-typed by SQLDelight; SQLite
            // coerces the numeric strings (substr affinity conversion).
            val chunk = queries.selectPersonaAvatarChunk(offset.toString(), size.toString(), id)
                .executeAsOneOrNull()?.substr
                ?: return@withContext null
            chunk.copyInto(out, dest)
            dest += chunk.size
            offset += chunk.size
            if (chunk.size < size && offset <= length) break
        }
        if (dest == out.size) out else out.copyOf(dest)
    }

    suspend fun insertPersona(name: String, description: String?, avatarData: ByteArray?): String = withContext(ioDispatcher) {
        val newId = generateUuid()
        queries.insertPersona(
            id = newId,
            name = name,
            description = description,
            avatarData = avatarData,
            updatedAt = nextTimestamp(),
            isDeleted = 0L,
            syncSeq = nextSyncSeq()
        )
        newId
    }

    suspend fun updatePersona(id: String, name: String, description: String?, avatarData: ByteArray?) = withContext(ioDispatcher) {
        val existing = queries.selectPersonaByIdAny(id).executeAsOneOrNull()
        // See updateCharacter: a local avatar change supersedes an unresolved
        // out-of-band ref; an unrelated save keeps it.
        if (avatarData != existing?.avatarData) queries.clearPersonaAvatarRef(id)
        queries.updatePersona(
            name = name,
            description = description,
            avatarData = avatarData,
            updatedAt = nextTimestamp(),
            syncSeq = nextSyncSeq(),
            id = id
        )
    }

    suspend fun deletePersona(id: String) = withContext(ioDispatcher) {
        queries.deletePersona(
            updatedAt = nextTimestamp(),
            syncSeq = nextSyncSeq(),
            id = id
        )
    }
}
