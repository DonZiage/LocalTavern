package chat.donzi.localtavern.controller

import chat.donzi.localtavern.data.database.ApiSettingsRepository
import chat.donzi.localtavern.data.database.CharacterRepository
import chat.donzi.localtavern.data.database.SessionRepository
import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.domain.Persona
import chat.donzi.localtavern.utils.BatchImportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _initError = MutableStateFlow<String?>(null)
    val initError: StateFlow<String?> = _initError.asStateFlow()

    init {
        retry()
    }

    // Any failure in initialization must surface instead of leaving the app
    // on a permanent loading spinner; retry() re-runs the whole sequence.
    fun retry() {
        _isInitialized.value = false
        _initError.value = null
        scope.launch {
            try {
                var currentPersonas = characterRepository.getAllPersonas()
                if (currentPersonas.isEmpty()) {
                    characterRepository.insertPersona("User", "", null)
                    currentPersonas = characterRepository.getAllPersonas()
                }

                val settings = apiSettingsRepository.getAppSettings()
                var pId = settings.activePersonaId
                if (pId == null && currentPersonas.isNotEmpty()) {
                    pId = currentPersonas.first().id
                    apiSettingsRepository.updateActivePersonaId(pId)
                }
                _activePersonaId.value = pId
                _isDarkMode.value = settings.isDarkMode != 0L
                _sendWithCtrlEnter.value = settings.sendWithCtrlEnter != 0L
                _autoSyncOnLaunch.value = settings.autoSyncOnLaunch != 0L
                _confirmBeforeDelete.value = settings.confirmBeforeDelete != 0L
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
        scope.launch { characterRepository.insertPersona(name, description, avatarData) }
    }

    fun updatePersona(id: String, name: String, description: String?, avatarData: ByteArray?) {
        scope.launch { characterRepository.updatePersona(id, name, description, avatarData) }
    }

    fun deletePersona(personaId: String) {
        scope.launch {
            // Chats bound to the deleted persona are unusable (the persona
            // can never be re-selected); remove them with it.
            sessionRepository.deleteSessionsForPersona(personaId)
            characterRepository.deletePersona(personaId)
            if (_activePersonaId.value == personaId) {
                val remainingPersonas = characterRepository.getAllPersonas()
                val nextPersonaId = remainingPersonas.firstOrNull()?.id
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

    fun importCharacters(result: BatchImportResult) {
        scope.launch { characterRepository.importCharacters(result.imports) }
    }

    fun createCharacter(name: String) {
        scope.launch { characterRepository.createCharacter(name) }
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
}
