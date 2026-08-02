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

class CharacterRepository(database: LocalTavernDB) : BaseRepository(database) {

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

    suspend fun getCharacterById(id: String): Character? = withContext(Dispatchers.IO) {
        queries.selectCharacterById(id).executeAsOneOrNull()?.toDomain()
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
}
