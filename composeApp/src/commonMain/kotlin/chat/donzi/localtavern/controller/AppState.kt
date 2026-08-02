package chat.donzi.localtavern.controller

import chat.donzi.localtavern.data.database.ApiSettingsRepository
import chat.donzi.localtavern.data.database.CharacterRepository
import chat.donzi.localtavern.data.models.SillyTavernCardV2
import chat.donzi.localtavern.domain.Character
import chat.donzi.localtavern.domain.Persona
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

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    init {
        scope.launch {
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
            _isInitialized.value = true
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
        scope.launch { characterRepository.deleteCharacters(ids) }
    }

    fun importCharacter(card: SillyTavernCardV2, avatarData: ByteArray?) {
        scope.launch { characterRepository.upsertCharacter(card, avatarData) }
    }

    fun createCharacter(name: String) {
        scope.launch { characterRepository.createCharacter(name) }
    }

    fun setDarkMode(isDark: Boolean) {
        _isDarkMode.value = isDark
        scope.launch { apiSettingsRepository.updateDarkMode(isDark) }
    }
}
