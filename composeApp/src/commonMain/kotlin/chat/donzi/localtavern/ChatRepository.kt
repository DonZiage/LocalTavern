package chat.donzi.localtavern

import chat.donzi.localtavern.models.SillyTavernCardV2
import chat.donzi.localtavern.database.CharacterEntity
import chat.donzi.localtavern.database.LocalTavernDB
import chat.donzi.localtavern.database.ApiConnection
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

    // API Connections
    suspend fun getAllApiConnections(): List<ApiConnection> = withContext(Dispatchers.IO) {
        queries.selectAllApiConnections().executeAsList()
    }

    private fun currentTimeMillis(): Long {
        // Simple fallback since kotlinx-datetime is not being resolved
        // In KMP, you might want to use expect/actual for a more robust solution
        return 0L // Placeholder until dependency is fixed or proper KMP time is used
    }

    suspend fun insertApiConnection(
        provider: String,
        name: String,
        baseUrl: String?,
        apiKey: String?,
        model: String?,
        isActive: Boolean = false,
        isChatCompletion: Boolean = true,
        temperature: Double = 1.0,
        topP: Double = 1.0,
        topK: Long = 0,
        presencePenalty: Double = 0.0,
        frequencyPenalty: Double = 0.0,
        contextLimit: Long = 4096,
        responseLimit: Long = 0
    ) = withContext(Dispatchers.IO) {
        queries.insertApiConnection(
            provider, 
            name, 
            baseUrl, 
            apiKey, 
            model, 
            if (isActive) 1L else 0L,
            if (isChatCompletion) 1L else 0L,
            if (isActive) currentTimeMillis() else 0L,
            temperature,
            topP,
            topK,
            presencePenalty,
            frequencyPenalty,
            contextLimit,
            responseLimit
        )
    }

    suspend fun updateApiConnection(
        id: Long,
        provider: String,
        name: String,
        baseUrl: String?,
        apiKey: String?,
        model: String?,
        isActive: Boolean,
        isChatCompletion: Boolean,
        lastUsed: Long? = null,
        temperature: Double,
        topP: Double,
        topK: Long,
        presencePenalty: Double,
        frequencyPenalty: Double,
        contextLimit: Long,
        responseLimit: Long
    ) = withContext(Dispatchers.IO) {
        queries.updateApiConnection(
            provider, 
            name, 
            baseUrl, 
            apiKey, 
            model, 
            if (isActive) 1L else 0L, 
            if (isChatCompletion) 1L else 0L,
            lastUsed ?: (if (isActive) currentTimeMillis() else null),
            temperature,
            topP,
            topK,
            presencePenalty,
            frequencyPenalty,
            contextLimit,
            responseLimit,
            id
        )
    }

    suspend fun deleteApiConnection(id: Long) = withContext(Dispatchers.IO) {
        queries.deleteApiConnection(id)
    }

    suspend fun setActiveApiConnection(id: Long) = withContext(Dispatchers.IO) {
        queries.setActiveApiConnection()
        queries.updateActiveApiConnection(currentTimeMillis(), id)
    }

    suspend fun getActiveApiConnection(): ApiConnection? = withContext(Dispatchers.IO) {
        queries.selectActiveApiConnection().executeAsOneOrNull()
            ?: queries.selectLastUsedApiConnection().executeAsOneOrNull()
    }
}