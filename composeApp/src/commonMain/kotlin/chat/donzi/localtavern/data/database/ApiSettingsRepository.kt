package chat.donzi.localtavern.data.database

import chat.donzi.localtavern.domain.ApiConfig
import chat.donzi.localtavern.domain.PromptBlock
import chat.donzi.localtavern.data.security.ApiKeyCipher
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class ApiSettingsRepository(
    database: LocalTavernDB,
    private val apiKeyCipher: ApiKeyCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    clock: LogicalClock = LogicalClock(database)
) : BaseRepository(database, clock) {

    // Live stream of the active connection so UI checks (e.g. refusing a send
    // without a profile) never rely on a stale snapshot loaded once.
    fun observeActiveApiConnection(): Flow<ApiConfig?> =
        queries.selectActiveApiConnection().asFlow().mapToOneOrNull(ioDispatcher)
            .map { it?.toDomain()?.withDecryptedKey() }

    suspend fun getAllApiConnections(): List<ApiConfig> = withContext(ioDispatcher) {
        queries.selectAllApiConnections().executeAsList().map { it.toDomain().withDecryptedKey() }
    }

    suspend fun insertApiConnection(
        provider: String, name: String, baseUrl: String?, apiKey: String?, model: String?,
        inferenceProvider: String? = null,
        quantization: String? = null,
        isActive: Boolean = false, isChatCompletion: Boolean = true, temperature: Double = 1.0,
        topP: Double = 1.0, topK: Long = 0, presencePenalty: Double = 0.0, frequencyPenalty: Double = 0.0,
        contextLimit: Long = 4096, responseLimit: Long = 1024, timeoutLimit: Long = 60,
        reasoningOverride: Int = 0
    ): String = withContext(ioDispatcher) {
        val newId = generateUuid()
        val now = currentTimeMillis()
        val ts = nextTimestamp()
        // The active flag and display order are derived from fresh DB state
        // inside the transaction, never from possibly-stale UI state: a new
        // connection must not be wrongly activated when the UI's connection
        // list is empty only because the initial load hasn't finished, and its
        // displayOrder must not collide with an existing connection's.
        database.transactionWithResult {
            val existingActive = queries.selectActiveApiConnection().executeAsOneOrNull()
            val shouldActivate = isActive || existingActive == null
            val nextOrder = (queries.selectAllApiConnections().executeAsList().maxOfOrNull { it.displayOrder } ?: -1L) + 1L
            if (shouldActivate && existingActive != null) {
                // Only one connection may be active at a time; without this the
                // row above would be a second active one and chat traffic
                // would silently go to an arbitrary endpoint.
                queries.setActiveApiConnection(updatedAt = ts, syncSeq = nextSyncSeq())
            }
            queries.insertApiConnection(
                id = newId, provider = provider, name = name, baseUrl = baseUrl, apiKey = apiKeyCipher.encryptForStorage(apiKey), model = model,
                inferenceProvider = inferenceProvider, quantization = quantization,
                isActive = if (shouldActivate) 1L else 0L, isChatCompletion = if (isChatCompletion) 1L else 0L,
                lastUsed = if (shouldActivate) now else 0L, temperature = temperature, topP = topP, topK = topK,
                presencePenalty = presencePenalty, frequencyPenalty = frequencyPenalty, contextLimit = contextLimit,
                responseLimit = responseLimit, displayOrder = nextOrder, timeoutLimit = timeoutLimit,
                reasoningOverride = reasoningOverride.toLong(),
                updatedAt = ts, isDeleted = 0L, syncSeq = nextSyncSeq()
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
        inferenceProvider = connection.inferenceProvider,
        quantization = connection.quantization,
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
        timeoutLimit = connection.timeoutLimit,
        reasoningOverride = connection.reasoningOverride
    )

    suspend fun updateApiConnection(
        id: String, provider: String, name: String, baseUrl: String?, apiKey: String?, model: String?,
        inferenceProvider: String? = null,
        quantization: String? = null,
        isActive: Boolean, isChatCompletion: Boolean, lastUsed: Long? = null, temperature: Double,
        topP: Double, topK: Long, presencePenalty: Double, frequencyPenalty: Double, contextLimit: Long,
        responseLimit: Long, displayOrder: Long, timeoutLimit: Long,
        reasoningOverride: Int = 0
    ) = withContext(ioDispatcher) {
        val now = currentTimeMillis()
        val ts = nextTimestamp()
        // A stored key that is unchanged (or unreadable while locked) must be
        // carried forward untouched; only a genuinely new key is re-encrypted.
        // When encryption is unavailable (backend configured but failing, or
        // the passphrase is still locked) the PREVIOUS stored value is kept:
        // writing plaintext would silently downgrade protection, and writing
        // null would wipe the key.
        val storedRow = queries.selectApiConnectionById(id).executeAsOneOrNull()
        val storedKey = storedRow?.apiKey
        val finalKey = if (apiKey == null || apiKey == apiKeyCipher.decryptFromStorage(storedKey)) {
            storedKey
        } else {
            apiKeyCipher.encryptForStorage(apiKey) ?: storedKey
        }
        // Activating a connection must atomically deactivate any other one;
        // the update and the lastUsed write share the same transaction so a
        // failure between them cannot leave the profile half-written.
        database.transaction {
            val seq = nextSyncSeq()
            if (isActive) {
                queries.setActiveApiConnection(updatedAt = ts, syncSeq = seq)
            }
            queries.updateApiConnection(
                provider = provider, name = name, baseUrl = baseUrl, apiKey = finalKey, model = model,
                inferenceProvider = inferenceProvider, quantization = quantization,
                isActive = if (isActive) 1L else 0L, isChatCompletion = if (isChatCompletion) 1L else 0L,
                temperature = temperature, topP = topP, topK = topK,
                presencePenalty = presencePenalty, frequencyPenalty = frequencyPenalty, contextLimit = contextLimit,
                responseLimit = responseLimit, displayOrder = displayOrder, timeoutLimit = timeoutLimit,
                reasoningOverride = reasoningOverride.toLong(),
                updatedAt = ts, syncSeq = seq, id = id
            )
            // Only touch lastUsed when explicitly provided or when activating the
            // profile; editing an inactive connection must not wipe its marker.
            if (lastUsed != null || isActive) {
                queries.updateApiConnectionLastUsed(lastUsed = lastUsed ?: now, updatedAt = ts, syncSeq = seq, id = id)
            }
        }
    }

    // Re-encrypts every stored API key with the current crypto state. Called
    // after the desktop passphrase is set (or changed) so keys that were
    // stored as plaintext are protected by the new key.
    suspend fun reencryptAllApiKeys() = withContext(ioDispatcher) {
        val ts = nextTimestamp()
        database.transaction {
            queries.selectAllApiConnections().executeAsList().forEach { row ->
                val decrypted = apiKeyCipher.decryptFromStorage(row.apiKey)
                // A row that is STILL marked as encrypted was not decryptable
                // here (lost keystore key, locked passphrase). Re-wrapping the
                // marker string would permanently destroy the stored key, so
                // such rows are left untouched.
                if (decrypted != null && decrypted.startsWith(chat.donzi.localtavern.data.security.EncryptedMarkerPrefix)) return@forEach
                val reEncrypted = apiKeyCipher.encryptForStorage(decrypted)
                if (reEncrypted != row.apiKey && reEncrypted != null) {
                    queries.updateApiConnectionApiKey(apiKey = reEncrypted, updatedAt = ts, syncSeq = nextSyncSeq(), id = row.id)
                }
            }
        }
    }

    // Stores every API key as plaintext. Only used when REMOVING desktop
    // protection, and only valid while the crypto is unlocked (so decryption
    // actually succeeds); the passphrase is forgotten right after.
    suspend fun decryptAllApiKeysToPlaintext() = withContext(ioDispatcher) {
        val ts = nextTimestamp()
        database.transaction {
            queries.selectAllApiConnections().executeAsList().forEach { row ->
                val decrypted = apiKeyCipher.decryptFromStorage(row.apiKey)
                if (decrypted != row.apiKey) {
                    queries.updateApiConnectionApiKey(apiKey = decrypted, updatedAt = ts, syncSeq = nextSyncSeq(), id = row.id)
                }
            }
        }
    }

    private fun ApiConfig.withDecryptedKey(): ApiConfig =
        if (apiKey == null) this else copy(apiKey = apiKeyCipher.decryptFromStorage(apiKey))

    suspend fun deleteApiConnection(id: String) = withContext(ioDispatcher) {
        queries.deleteApiConnection(
            updatedAt = nextTimestamp(),
            syncSeq = nextSyncSeq(),
            id = id
        )
    }

    suspend fun setActiveApiConnection(id: String) = withContext(ioDispatcher) {
        val now = currentTimeMillis()
        val ts = nextTimestamp()
        // Deactivating all rows and activating the target must be atomic: a
        // crash between the two would leave the app with zero active
        // connections (silently losing its API profile), and two concurrent
        // activations could both succeed.
        database.transaction {
            val seq = nextSyncSeq()
            queries.setActiveApiConnection(updatedAt = ts, syncSeq = seq)
            queries.updateActiveApiConnection(lastUsed = now, updatedAt = ts, syncSeq = seq, id = id)
        }
    }

    suspend fun getActiveApiConnection(): ApiConfig? = withContext(ioDispatcher) {
        // No silent fallback to the last-used profile: a connection is active
        // only when explicitly marked as such, so the UI never claims a
        // deactivated profile is in use.
        queries.selectActiveApiConnection().executeAsOneOrNull()?.toDomain()?.withDecryptedKey()
    }

    suspend fun updateApiConnectionDisplayOrders(orderedIds: List<String>): Unit = withContext(ioDispatcher) {
        val ts = nextTimestamp()
        database.transaction {
            val seq = nextSyncSeq()
            orderedIds.forEachIndexed { index, id ->
                queries.updateApiConnectionDisplayOrder(displayOrder = index.toLong(), updatedAt = ts, syncSeq = seq, id = id)
            }
        }
    }

    suspend fun getAppSettings(): AppSettings = withContext(ioDispatcher) {
        queries.insertDefaultSettings()
        queries.getAppSettings().executeAsOne()
    }

    suspend fun updateActivePersonaId(personaId: String?) = withContext(ioDispatcher) {
        // Self-healing: the settings row may not exist yet if no code path
        // ever ran getAppSettings(); without the insert the update silently
        // affects zero rows and the setting is lost.
        database.transaction {
            queries.insertDefaultSettings()
            queries.updateActivePersonaId(personaId)
        }
    }

    suspend fun updateDarkMode(isDarkMode: Boolean) = withContext(ioDispatcher) {
        database.transaction {
            queries.insertDefaultSettings()
            queries.updateDarkMode(if (isDarkMode) 1L else 0L)
        }
    }

    suspend fun updateSendWithCtrlEnter(enabled: Boolean) = withContext(ioDispatcher) {
        database.transaction {
            queries.insertDefaultSettings()
            queries.updateSendWithCtrlEnter(if (enabled) 1L else 0L)
        }
    }

    suspend fun updateAutoSyncOnLaunch(enabled: Boolean) = withContext(ioDispatcher) {
        database.transaction {
            queries.insertDefaultSettings()
            queries.updateAutoSyncOnLaunch(if (enabled) 1L else 0L)
        }
    }

    suspend fun updateConfirmBeforeDelete(enabled: Boolean) = withContext(ioDispatcher) {
        database.transaction {
            queries.insertDefaultSettings()
            queries.updateConfirmBeforeDelete(if (enabled) 1L else 0L)
        }
    }

    suspend fun getAllPromptBlocks(): List<PromptBlock> = withContext(ioDispatcher) {
        // The emptiness check and the seeding run inside one transaction: two
        // concurrent cold starts (settings screen + prompt editor) would both
        // see an empty table and the second INSERT would fail on the unique
        // "system" block, crashing the startup path.
        database.transactionWithResult {
            val storedBlocks = queries.selectAllPromptBlocks().executeAsList()
            if (storedBlocks.isEmpty()) {
                val ts = nextTimestamp()
                val seq = nextSyncSeq()
                var initialOrder = 0L
                queries.insertPromptBlock("system", "System Prompt", "You are roleplaying. Stay in character, describe actions vividly, and adapt seamlessly to the story scenario.", 1L, 0L, initialOrder++, ts, 0L, seq)
                queries.insertPromptBlock("persona", "User Persona", "User Persona:\n{{user_persona}}", 1L, 0L, initialOrder++, ts, 0L, seq)
                queries.insertPromptBlock("description", "Character Description", "Character Info:\n{{character_description}}", 1L, 0L, initialOrder++, ts, 0L, seq)
                queries.insertPromptBlock("personality", "Personality", "Personality:\n{{personality}}", 1L, 0L, initialOrder++, ts, 0L, seq)
                queries.insertPromptBlock("scenario", "Scenario", "Scenario:\n{{scenario}}", 1L, 0L, initialOrder++, ts, 0L, seq)
                queries.insertPromptBlock("chat_history", "Chat History", "{{chat_history}}", 1L, 0L, initialOrder, ts, 0L, seq)
                queries.selectAllPromptBlocks().executeAsList().map { it.toDomain() }
            } else {
                storedBlocks.map { it.toDomain() }
            }
        }
    }

    suspend fun savePromptBlock(id: String, name: String, template: String, isEnabled: Boolean) = withContext(ioDispatcher) {
        queries.updatePromptBlock(name = name, template = template, isEnabled = if (isEnabled) 1L else 0L, updatedAt = nextTimestamp(), syncSeq = nextSyncSeq(), id = id)
    }

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    suspend fun insertCustomPromptBlock(name: String, template: String): String = withContext(ioDispatcher) {
        val ts = nextTimestamp()
        val uniqueId = generateUuid()
        val currentBlocks = queries.selectAllPromptBlocks().executeAsList()
        val nextOrderPosition = (currentBlocks.maxOfOrNull { it.displayOrder } ?: -1L) + 1L
        queries.insertPromptBlock(uniqueId, name, template, 1L, 1L, nextOrderPosition, ts, 0L, nextSyncSeq())
        uniqueId
    }

    suspend fun deletePromptBlock(id: String) = withContext(ioDispatcher) {
        queries.deletePromptBlock(updatedAt = nextTimestamp(), syncSeq = nextSyncSeq(), id = id)
    }

    suspend fun updatePromptBlockDisplayOrders(orderedIds: List<String>): Unit = withContext(ioDispatcher) {
        val ts = nextTimestamp()
        database.transaction {
            val seq = nextSyncSeq()
            orderedIds.forEachIndexed { index, id ->
                queries.updatePromptBlockDisplayOrder(displayOrder = index.toLong(), updatedAt = ts, syncSeq = seq, id = id)
            }
        }
    }
}
