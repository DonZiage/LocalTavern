package chat.donzi.localtavern

import chat.donzi.localtavern.models.SillyTavernCardV2
import chat.donzi.localtavern.database.CharacterEntity
import chat.donzi.localtavern.database.LocalTavernDB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChatRepository(database: LocalTavernDB) {
    private val queries = database.localTavernDBQueries

    suspend fun getAllCharacters(): List<CharacterEntity> = withContext(Dispatchers.IO) {
        queries.selectAllCharacters().executeAsList()
    }

    suspend fun upsertCharacter(
        card: SillyTavernCardV2,
        avatarPath: String? = null
    ) = withContext(Dispatchers.IO) {
        queries.insertCharacter(
            name = card.name,
            description = card.description,
            personality = card.personality,
            scenario = card.scenario,
            firstMes = card.first_mes,
            mesExample = card.mes_example,
            creatorNotes = card.creator_notes,
            systemPrompt = card.system_prompt,
            altGreetings = card.alternate_greetings.joinToString("|||").ifBlank { null },
            avatarPath = avatarPath
        )
    }

    suspend fun updateCharacter(
        id: Long,
        name: String,
        personality: String,
        scenario: String,
        description: String?,
        firstMes: String?,
        systemPrompt: String?,
        altGreetings: List<String> = emptyList()
    ) = withContext(Dispatchers.IO) {
        queries.updateCharacter(
            name = name,
            description = description,
            personality = personality,
            scenario = scenario,
            firstMes = firstMes,
            systemPrompt = systemPrompt,
            altGreetings = altGreetings.joinToString("|||").ifBlank { null },
            id = id
        )
    }

    suspend fun deleteCharacters(ids: Collection<Long>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        queries.deleteCharactersByIds(ids.toList())
    }
}