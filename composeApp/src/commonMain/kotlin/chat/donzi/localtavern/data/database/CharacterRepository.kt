package chat.donzi.localtavern.data.database

import chat.donzi.localtavern.data.models.SillyTavernCardV2
import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.domain.Persona
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
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
    clock: LogicalClock = LogicalClock(database)
) : BaseRepository(database, clock) {

    fun observeCharacters(): Flow<List<Character>> =
        queries.selectAllCharacters().asFlow().mapToList(Dispatchers.IO).map { list -> list.map { it.toDomain() } }

    fun observePersonas(): Flow<List<Persona>> =
        queries.selectAllPersonas().asFlow().mapToList(Dispatchers.IO).map { list -> list.map { it.toDomain() } }

    suspend fun getAllCharacters(): List<Character> = withContext(Dispatchers.IO) {
        queries.selectAllCharacters().executeAsList().map { it.toDomain() }
    }

    suspend fun getAssistant(): Character? = withContext(Dispatchers.IO) {
        queries.selectAssistant().executeAsOneOrNull()?.toDomain()
    }

    suspend fun createAssistant(): String = withContext(Dispatchers.IO) {
        val newId = generateUuid()
        val now = nextTimestamp()
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
            characterBook = null
        )
        newId
    }

    suspend fun getCharacterById(id: String): Character? = withContext(Dispatchers.IO) {
        queries.selectCharacterById(id).executeAsOneOrNull()?.toDomain()
    }

    suspend fun upsertCharacter(
        card: SillyTavernCardV2,
        avatarData: ByteArray? = null
    ): String = withContext(Dispatchers.IO) {
        val newId = generateUuid()
        val now = nextTimestamp()
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
            characterBook = encodeJsonObject(card.character_book)
        )
        newId
    }

    suspend fun createCharacter(name: String): String = withContext(Dispatchers.IO) {
        val newId = generateUuid()
        val now = nextTimestamp()
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
            characterBook = null
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
        // The character editor does not touch the round-trip-only card fields;
        // carry the stored values forward so a save cannot strip them.
        val existing = queries.selectCharacterById(id).executeAsOneOrNull()
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
            id = id
        )
    }

    suspend fun deleteCharacters(ids: Collection<String>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        queries.deleteCharactersByIds(
            updatedAt = nextTimestamp(),
            id = ids.toList()
        )
    }

    suspend fun updateCharacterLorebook(id: String, characterBook: JsonObject?) = withContext(Dispatchers.IO) {
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
            id = id
        )
    }

    suspend fun getAllPersonas(): List<Persona> = withContext(Dispatchers.IO) {
        queries.selectAllPersonas().executeAsList().map { it.toDomain() }
    }

    suspend fun insertPersona(name: String, description: String?, avatarData: ByteArray?): String = withContext(Dispatchers.IO) {
        val newId = generateUuid()
        queries.insertPersona(
            id = newId,
            name = name,
            description = description,
            avatarData = avatarData,
            updatedAt = nextTimestamp(),
            isDeleted = 0L
        )
        newId
    }

    suspend fun updatePersona(id: String, name: String, description: String?, avatarData: ByteArray?) = withContext(Dispatchers.IO) {
        queries.updatePersona(
            name = name,
            description = description,
            avatarData = avatarData,
            updatedAt = nextTimestamp(),
            id = id
        )
    }

    suspend fun deletePersona(id: String) = withContext(Dispatchers.IO) {
        queries.deletePersona(
            updatedAt = nextTimestamp(),
            id = id
        )
    }
}
