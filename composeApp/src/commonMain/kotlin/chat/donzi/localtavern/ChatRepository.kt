package chat.donzi.localtavern

import chat.donzi.localtavern.models.SillyTavernCardV2
import chat.donzi.localtavern.database.CharacterEntity
import chat.donzi.localtavern.database.LocalTavernDB
import chat.donzi.localtavern.database.ApiConnection
import chat.donzi.localtavern.database.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

class ChatRepository(private val database: LocalTavernDB) {
    private val queries = database.localTavernDBQueries

    suspend fun getAllCharacters(): List<CharacterEntity> = withContext(Dispatchers.IO) {
        queries.selectAllCharacters().executeAsList()
    }

    suspend fun upsertCharacter(
        card: SillyTavernCardV2,
        avatarData: ByteArray? = null
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
            avatarData = avatarData
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
        altGreetings: List<String> = emptyList(),
        avatarData: ByteArray? = null
    ) = withContext(Dispatchers.IO) {
        queries.updateCharacter(
            name = name,
            description = description,
            personality = personality,
            scenario = scenario,
            firstMes = firstMes,
            systemPrompt = systemPrompt,
            altGreetings = altGreetings.joinToString("|||").ifBlank { null },
            avatarData = avatarData,
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
        return Clock.System.now().toEpochMilliseconds()
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
        responseLimit: Long = 1024,
        displayOrder: Long = 0
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
            responseLimit,
            displayOrder
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
        responseLimit: Long,
        displayOrder: Long
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
            displayOrder,
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

    suspend fun updateApiConnectionDisplayOrders(orderedIds: List<Long>): Unit = withContext(Dispatchers.IO) {
        database.transaction {
            orderedIds.forEachIndexed { index, id ->
                queries.updateApiConnectionDisplayOrder(index.toLong(), id)
            }
        }
    }

    // App Settings
    suspend fun getAppSettings(): AppSettings = withContext(Dispatchers.IO) {
        queries.insertDefaultSettings()
        queries.getAppSettings().executeAsOne()
    }

    suspend fun updateDarkMode(isDarkMode: Boolean) = withContext(Dispatchers.IO) {
        queries.updateDarkMode(if (isDarkMode) 1L else 0L)
    }
}
