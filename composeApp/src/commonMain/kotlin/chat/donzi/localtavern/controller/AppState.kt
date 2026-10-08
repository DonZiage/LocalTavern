package chat.donzi.localtavern.controller

import chat.donzi.localtavern.data.database.ApiSettingsRepository
import chat.donzi.localtavern.data.database.CharacterRepository
import chat.donzi.localtavern.data.database.SessionRepository
import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.domain.Persona
import chat.donzi.localtavern.utils.BatchImportResult
import chat.donzi.localtavern.utils.CharacterManager
import chat.donzi.localtavern.utils.ImportedCharacter
import chat.donzi.localtavern.utils.InputValidation
import chat.donzi.localtavern.utils.PickedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppState(
    private val characterRepository: CharacterRepository,
    private val apiSettingsRepository: ApiSettingsRepository,
    private val sessionRepository: SessionRepository,
    private val scope: CoroutineScope
) {
    val characters: StateFlow<List<Character>> = characterRepository.observeCharacters()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val personas: StateFlow<List<Persona>> = characterRepository.observePersonas()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _activePersonaId = MutableStateFlow<String?>(null)
    val activePersonaId: StateFlow<String?> = _activePersonaId.asStateFlow()

    private val _isDarkMode = MutableStateFlow(false)
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    private val _sendWithCtrlEnter = MutableStateFlow(false)
    val sendWithCtrlEnter: StateFlow<Boolean> = _sendWithCtrlEnter.asStateFlow()

    private val _autoSyncOnLaunch = MutableStateFlow(false)
    val autoSyncOnLaunch: StateFlow<Boolean> = _autoSyncOnLaunch.asStateFlow()

    private val _confirmBeforeDelete = MutableStateFlow(true)
    val confirmBeforeDelete: StateFlow<Boolean> = _confirmBeforeDelete.asStateFlow()

    // Desktop idle auto-lock in minutes; 0 = disabled. Only meaningful on the
    // passphrase backend, but harmless (and consistent) on every platform.
    private val _autoLockIdleMinutes = MutableStateFlow(10)
    val autoLockIdleMinutes: StateFlow<Int> = _autoLockIdleMinutes.asStateFlow()

    // Mobile: whether the first-launch "set a device screen lock"
    // recommendation has been dismissed. Loaded during init so the dialog
    // never flashes for returning users.
    private val _lockRecommendationShown = MutableStateFlow(false)
    val lockRecommendationShown: StateFlow<Boolean> = _lockRecommendationShown.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _initError = MutableStateFlow<String?>(null)
    val initError: StateFlow<String?> = _initError.asStateFlow()

    init {
        retry()
        // Keep the persisted selection converged at runtime: whenever the
        // persona list changes (add, edit, delete, sync), re-resolve the
        // active persona — last selection while it still exists, otherwise
        // the first available, otherwise the implicit blank "User" persona —
        // so a selected persona card always exists. retry() owns the initial
        // load, so this only acts on later emissions.
        scope.launch {
            characterRepository.observePersonas().collect { personas ->
                if (!_isInitialized.value) return@collect
                val current = _activePersonaId.value
                val resolved = personas.firstOrNull { it.id == current }?.id
                    ?: personas.firstOrNull()?.id
                    ?: Persona.DEFAULT_ID
                if (resolved != current) {
                    _activePersonaId.value = resolved
                    apiSettingsRepository.updateActivePersonaId(resolved)
                }
            }
        }
    }

    // Any failure in initialization must surface instead of leaving the app
    // on a permanent loading spinner; retry() re-runs the whole sequence.
    fun retry() {
        _isInitialized.value = false
        _initError.value = null
        scope.launch {
            try {
                val currentPersonas = characterRepository.getAllPersonas()

                val settings = apiSettingsRepository.getAppSettings()
                // The active persona must never dangle: the last selection
                // wins while its persona still exists, otherwise the first
                // available persona takes over, otherwise the implicit blank
                // "User" persona (never stored, never shown in the cards).
                val pId = settings.activePersonaId
                    ?.takeIf { stored -> currentPersonas.any { it.id == stored } }
                    ?: currentPersonas.firstOrNull()?.id
                    ?: Persona.DEFAULT_ID
                if (pId != settings.activePersonaId) {
                    apiSettingsRepository.updateActivePersonaId(pId)
                }
                _activePersonaId.value = pId
                _isDarkMode.value = settings.isDarkMode != 0L
                _sendWithCtrlEnter.value = settings.sendWithCtrlEnter != 0L
                _autoSyncOnLaunch.value = settings.autoSyncOnLaunch != 0L
                _confirmBeforeDelete.value = settings.confirmBeforeDelete != 0L
                _autoLockIdleMinutes.value = settings.autoLockIdleMinutes.toInt()
                _lockRecommendationShown.value = settings.lockRecommendationShown != 0L
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _initError.value = e.message ?: "Failed to initialize the app."
            } finally {
                _isInitialized.value = true
            }
        }
    }

    fun setActivePersona(personaId: String) {
        _activePersonaId.value = personaId
        scope.launch { apiSettingsRepository.updateActivePersonaId(personaId) }
    }

    fun addPersona(name: String, description: String?, avatarData: ByteArray?) {
        // Programmatic callers bypass the dialog's isNotBlank gate; enforce
        // the same rule here so blank personas can never reach the database.
        if (InputValidation.validateDisplayName(name) != null) return
        val clean = InputValidation.cleanDisplayName(name)
        scope.launch { characterRepository.insertPersona(clean, description, avatarData) }
    }

    fun updatePersona(id: String, name: String, description: String?, avatarData: ByteArray?) {
        if (InputValidation.validateDisplayName(name) != null) return
        val clean = InputValidation.cleanDisplayName(name)
        scope.launch { characterRepository.updatePersona(id, clean, description, avatarData) }
    }

    fun deletePersona(personaId: String) {
        scope.launch {
            // Chats bound to the deleted persona are unusable (the persona
            // can never be re-selected); remove them with it.
            sessionRepository.deleteSessionsForPersona(personaId)
            characterRepository.deletePersona(personaId)
            if (_activePersonaId.value == personaId) {
                val remainingPersonas = characterRepository.getAllPersonas()
                val nextPersonaId = remainingPersonas.firstOrNull()?.id ?: Persona.DEFAULT_ID
                _activePersonaId.value = nextPersonaId
                apiSettingsRepository.updateActivePersonaId(nextPersonaId)
            }
        }
    }

    fun deleteCharacters(ids: Set<String>) {
        scope.launch {
            // Remove the character's sessions too, so ghost chats are never
            // resumable against a character the UI can no longer load.
            sessionRepository.deleteSessionsForCharacters(ids)
            characterRepository.deleteCharacters(ids)
        }
    }

    /**
     * Imports picked card sources (PNG/JSON cards and ZIP archives) end to
     * end with bounded memory: the archive is streamed entry by entry, each
     * parsed card is handed to the repository in chunks cut by card count AND
     * buffered avatar bytes, and the returned summary never carries card or
     * avatar bytes — on a mobile device a heavy library (hundreds of MB) must
     * not be held in memory while it is being written to the database.
     *
     * This function never throws (except on coroutine cancellation): a
     * persistence failure falls back to per-card isolation so one bad card
     * cannot sink its chunk, and every card that did not reach the database
     * is reported by name in [BatchImportResult.failed] with the cause in
     * [BatchImportResult.error] — nothing is ever silently lost, and the app
     * cannot crash no matter how many characters are imported at once.
     */
    suspend fun importPickedFiles(files: List<PickedFile>): BatchImportResult = withContext(Dispatchers.Default) {
        val chunk = ArrayList<ImportedCharacter>(IMPORT_CHUNK_SIZE)
        var chunkBytes = 0L
        val failed = ArrayList<String>()
        var imported = 0
        var error: String? = null

        suspend fun flush() {
            if (chunk.isEmpty()) return
            val batch = ArrayList(chunk)
            chunk.clear()
            chunkBytes = 0L
            try {
                imported += characterRepository.importCharacters(batch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The fast batch path failed (disk full, corrupt bytes):
                // retry card by card so a single bad card cannot sink the
                // whole chunk, and record exactly the cards that did not land.
                batch.forEach { card ->
                    try {
                        imported += characterRepository.importCharacters(listOf(card))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (cardError: Exception) {
                        failed.add(card.card.name.ifBlank { "Unnamed character" })
                        if (error == null) error = cardError.message ?: "Failed to save a character."
                    }
                }
                if (error == null) error = e.message ?: "Failed to save characters."
            }
        }

        try {
            val result = CharacterManager.processImportBatch(files) { parsed ->
                chunk.add(parsed)
                chunkBytes += parsed.avatarData?.size?.toLong() ?: 0L
                if (chunk.size >= IMPORT_CHUNK_SIZE || chunkBytes >= IMPORT_CHUNK_BYTES) {
                    flush()
                }
            }
            flush()
            failed.addAll(result.failed)
            BatchImportResult(imported = imported, failed = failed, error = error)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The parse loop itself failed (malformed input never throws —
            // only persistence errors propagate, and those are handled in
            // flush): anything still buffered never reached the database, so
            // report it by name instead of dropping it.
            chunk.forEach { failed.add(it.card.name.ifBlank { "Unnamed character" }) }
            if (error == null) error = e.message ?: "Import failed."
            BatchImportResult(imported = imported, failed = failed, error = error)
        }
    }

    private companion object {
        // Cards buffered before one database commit, cut by count AND by
        // buffered avatar bytes: 20 cards with multi-MB avatars must flush
        // long before the count is reached, or a heavy import would pin
        // hundreds of MB on a mobile heap (OOM crash).
        const val IMPORT_CHUNK_SIZE = 20
        const val IMPORT_CHUNK_BYTES = 8L * 1024 * 1024
    }

    fun createCharacter(name: String) {
        if (InputValidation.validateDisplayName(name) != null) return
        val clean = InputValidation.cleanDisplayName(name)
        scope.launch { characterRepository.createCharacter(clean) }
    }

    fun setDarkMode(isDark: Boolean) {
        _isDarkMode.value = isDark
        scope.launch { apiSettingsRepository.updateDarkMode(isDark) }
    }

    fun setSendWithCtrlEnter(enabled: Boolean) {
        _sendWithCtrlEnter.value = enabled
        scope.launch { apiSettingsRepository.updateSendWithCtrlEnter(enabled) }
    }

    fun setAutoSyncOnLaunch(enabled: Boolean) {
        _autoSyncOnLaunch.value = enabled
        scope.launch { apiSettingsRepository.updateAutoSyncOnLaunch(enabled) }
    }

    fun setConfirmBeforeDelete(enabled: Boolean) {
        _confirmBeforeDelete.value = enabled
        scope.launch { apiSettingsRepository.updateConfirmBeforeDelete(enabled) }
    }

    fun setAutoLockIdleMinutes(minutes: Int) {
        _autoLockIdleMinutes.value = minutes
        scope.launch { apiSettingsRepository.updateAutoLockIdleMinutes(minutes) }
    }

    fun setLockRecommendationShown() {
        if (_lockRecommendationShown.value) return
        _lockRecommendationShown.value = true
        scope.launch { apiSettingsRepository.updateLockRecommendationShown(true) }
    }
}
