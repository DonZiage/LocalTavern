package chat.donzi.localtavern.data.database

import chat.donzi.localtavern.domain.ApiConfig
import chat.donzi.localtavern.domain.PromptBlock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

class ApiSettingsRepository(
    database: LocalTavernDB,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : BaseRepository(database) {

    suspend fun getAllApiConnections(): List<ApiConfig> = withContext(ioDispatcher) {
        queries.selectAllApiConnections().executeAsList().map { it.toDomain() }
    }

    suspend fun insertApiConnection(
        provider: String, name: String, baseUrl: String?, apiKey: String?, model: String?,
        isActive: Boolean = false, isChatCompletion: Boolean = true, temperature: Double = 1.0,
        topP: Double = 1.0, topK: Long = 0, presencePenalty: Double = 0.0, frequencyPenalty: Double = 0.0,
        contextLimit: Long = 4096, responseLimit: Long = 1024, timeoutLimit: Long = 60
    ): String = withContext(ioDispatcher) {
        val newId = generateUuid()
        val now = currentTimeMillis()
        // The active flag and display order are derived from fresh DB state
        // inside the transaction, never from possibly-stale UI state: a new
        // connection must not be wrongly activated when the UI's connection
        // list is empty only because the initial load hasn't finished, and its
        // displayOrder must not collide with an existing connection's.
        database.transactionWithResult {
            val existingActive = queries.selectActiveApiConnection().executeAsOneOrNull()
            val shouldActivate = isActive || existingActive == null
            val nextOrder = (queries.selectAllApiConnections().executeAsList().maxOfOrNull { it.displayOrder } ?: -1L) + 1L
            queries.insertApiConnection(
                id = newId, provider = provider, name = name, baseUrl = baseUrl, apiKey = apiKey, model = model,
                isActive = if (shouldActivate) 1L else 0L, isChatCompletion = if (isChatCompletion) 1L else 0L,
                lastUsed = if (shouldActivate) now else 0L, temperature = temperature, topP = topP, topK = topK,
                presencePenalty = presencePenalty, frequencyPenalty = frequencyPenalty, contextLimit = contextLimit,
                responseLimit = responseLimit, displayOrder = nextOrder, timeoutLimit = timeoutLimit,
                updatedAt = now, isDeleted = 0L
            )
            newId
        }
    }

    suspend fun updateApiConnection(connection: ApiConfig) = updateApiConnection(
        id = connection.id,
        provider = connection.provider,
        name = connection.name,
        baseUrl = connection.baseUrl,
        apiKey = connection.apiKey,
        model = connection.model,
        isActive = connection.isActive,
        isChatCompletion = connection.isChatCompletion,
        temperature = connection.temperature,
        topP = connection.topP,
        topK = connection.topK,
        presencePenalty = connection.presencePenalty,
        frequencyPenalty = connection.frequencyPenalty,
        contextLimit = connection.contextLimit,
        responseLimit = connection.responseLimit,
        displayOrder = connection.displayOrder,
        timeoutLimit = connection.timeoutLimit
    )

    suspend fun updateApiConnection(
        id: String, provider: String, name: String, baseUrl: String?, apiKey: String?, model: String?,
        isActive: Boolean, isChatCompletion: Boolean, lastUsed: Long? = null, temperature: Double,
        topP: Double, topK: Long, presencePenalty: Double, frequencyPenalty: Double, contextLimit: Long,
        responseLimit: Long, displayOrder: Long, timeoutLimit: Long
    ) = withContext(ioDispatcher) {
        val now = currentTimeMillis()
        queries.updateApiConnection(
            provider = provider, name = name, baseUrl = baseUrl, apiKey = apiKey, model = model,
            isActive = if (isActive) 1L else 0L, isChatCompletion = if (isChatCompletion) 1L else 0L,
            temperature = temperature, topP = topP, topK = topK,
            presencePenalty = presencePenalty, frequencyPenalty = frequencyPenalty, contextLimit = contextLimit,
            responseLimit = responseLimit, displayOrder = displayOrder, timeoutLimit = timeoutLimit,
            updatedAt = now, id = id
        )
        // Only touch lastUsed when explicitly provided or when activating the
        // profile; editing an inactive connection must not wipe its marker.
        if (lastUsed != null || isActive) {
            queries.updateApiConnectionLastUsed(lastUsed = lastUsed ?: now, updatedAt = now, id = id)
        }
    }

    suspend fun deleteApiConnection(id: String) = withContext(ioDispatcher) {
        queries.deleteApiConnection(
            updatedAt = currentTimeMillis(),
            id = id
        )
    }

    suspend fun setActiveApiConnection(id: String) = withContext(ioDispatcher) {
        val now = currentTimeMillis()
        queries.setActiveApiConnection(updatedAt = now)
        queries.updateActiveApiConnection(lastUsed = now, updatedAt = now, id = id)
    }

    suspend fun getActiveApiConnection(): ApiConfig? = withContext(ioDispatcher) {
        // No silent fallback to the last-used profile: a connection is active
        // only when explicitly marked as such, so the UI never claims a
        // deactivated profile is in use.
        queries.selectActiveApiConnection().executeAsOneOrNull()?.toDomain()
    }

    suspend fun updateApiConnectionDisplayOrders(orderedIds: List<String>): Unit = withContext(ioDispatcher) {
        val now = currentTimeMillis()
        database.transaction {
            orderedIds.forEachIndexed { index, id ->
                queries.updateApiConnectionDisplayOrder(displayOrder = index.toLong(), updatedAt = now, id = id)
            }
        }
    }

    suspend fun getAppSettings(): AppSettings = withContext(ioDispatcher) {
        queries.insertDefaultSettings()
        queries.getAppSettings().executeAsOne()
    }

    suspend fun updateActivePersonaId(personaId: String?) = withContext(ioDispatcher) {
        queries.updateActivePersonaId(personaId)
    }

    suspend fun updateDarkMode(isDarkMode: Boolean) = withContext(ioDispatcher) {
        queries.updateDarkMode(if (isDarkMode) 1L else 0L)
    }

    suspend fun getAllPromptBlocks(): List<PromptBlock> = withContext(ioDispatcher) {
        val storedBlocks = queries.selectAllPromptBlocks().executeAsList()
        if (storedBlocks.isEmpty()) {
            val now = currentTimeMillis()
            database.transaction {
                var initialOrder = 0L
                queries.insertPromptBlock("system", "System Prompt", "You are roleplaying. Stay in character, describe actions vividly, and adapt seamlessly to the story scenario.", 1L, 0L, initialOrder++, now, 0L)
                queries.insertPromptBlock("persona", "User Persona", "User Persona:\n{{user_persona}}", 1L, 0L, initialOrder++, now, 0L)
                queries.insertPromptBlock("description", "Character Description", "Character Info:\n{{character_description}}", 1L, 0L, initialOrder++, now, 0L)
                queries.insertPromptBlock("personality", "Personality", "Personality:\n{{personality}}", 1L, 0L, initialOrder++, now, 0L)
                queries.insertPromptBlock("scenario", "Scenario", "Scenario:\n{{scenario}}", 1L, 0L, initialOrder++, now, 0L)
                queries.insertPromptBlock("chat_history", "Chat History", "{{chat_history}}", 1L, 0L, initialOrder, now, 0L)
            }
            queries.selectAllPromptBlocks().executeAsList().map { it.toDomain() }
        } else {
            storedBlocks.map { it.toDomain() }
        }
    }

    suspend fun savePromptBlock(id: String, name: String, template: String, isEnabled: Boolean) = withContext(ioDispatcher) {
        queries.updatePromptBlock(name = name, template = template, isEnabled = if (isEnabled) 1L else 0L, updatedAt = currentTimeMillis(), id = id)
    }

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    suspend fun insertCustomPromptBlock(name: String, template: String): String = withContext(ioDispatcher) {
        val now = currentTimeMillis()
        val uniqueId = generateUuid()
        val currentBlocks = queries.selectAllPromptBlocks().executeAsList()
        val nextOrderPosition = (currentBlocks.maxOfOrNull { it.displayOrder } ?: -1L) + 1L
        queries.insertPromptBlock(uniqueId, name, template, 1L, 1L, nextOrderPosition, now, 0L)
        uniqueId
    }

    suspend fun deletePromptBlock(id: String) = withContext(ioDispatcher) {
        queries.deletePromptBlock(updatedAt = currentTimeMillis(), id = id)
    }

    suspend fun updatePromptBlockDisplayOrders(orderedIds: List<String>): Unit = withContext(ioDispatcher) {
        val now = currentTimeMillis()
        database.transaction {
            orderedIds.forEachIndexed { index, id ->
                queries.updatePromptBlockDisplayOrder(displayOrder = index.toLong(), updatedAt = now, id = id)
            }
        }
    }
}
