package chat.donzi.localtavern.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import chat.donzi.localtavern.controller.AppContainer
import chat.donzi.localtavern.data.database.DriverFactory
import chat.donzi.localtavern.ui.components.ActiveDrawer
import chat.donzi.localtavern.ui.theme.LocalTavernTheme
import chat.donzi.localtavern.ui.theme.ThemeTransition

@Composable
fun App(driverFactory: DriverFactory, onThemeChanged: (Boolean) -> Unit = {}) {
    val container = remember { AppContainer(driverFactory) }
    val appState = container.appState
    val chatController = container.chatController

    val characters by appState.characters.collectAsState()
    val personas by appState.personas.collectAsState()
    val activePersonaId by appState.activePersonaId.collectAsState()
    val darkModeFromDb by appState.isDarkMode.collectAsState()
    val isInitialized by appState.isInitialized.collectAsState()

    val systemDark = isSystemInDarkTheme()
    var activeDrawer by remember { mutableStateOf(ActiveDrawer.None) }

    if (!isInitialized) {
        LocalTavernTheme(darkTheme = systemDark) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    } else {
        ThemeTransition(
            initialThemeIsDark = darkModeFromDb,
            onThemeSaved = { darkMode ->
                appState.setDarkMode(darkMode)
            }
        ) { syncedDarkTheme, triggerTransition ->
            LaunchedEffect(syncedDarkTheme) {
                onThemeChanged(syncedDarkTheme)
            }
            MainScreen(
                chatController = chatController,
                characterRepository = container.characterRepository,
                sessionRepository = container.sessionRepository,
                apiSettingsRepository = container.apiSettingsRepository,
                chatClient = container.chatClient,
                characters = characters,
                personas = personas,
                activePersonaId = activePersonaId,
                isDarkMode = syncedDarkTheme,
                onToggleDarkMode = { _, centerOffset ->
                    triggerTransition(centerOffset)
                },
                activeDrawer = activeDrawer,
                onActiveDrawerChange = { activeDrawer = it },
                onPersonaSelect = { personaId ->
                    appState.setActivePersona(personaId)
                },
                onPersonaAdd = { name, desc, avatar ->
                    appState.addPersona(name, desc, avatar)
                },
                onPersonaUpdate = { id, name, desc, avatar ->
                    appState.updatePersona(id, name, desc, avatar)
                },
                onPersonaDelete = { personaId ->
                    appState.deletePersona(personaId)
                },
                onCharactersDelete = { ids ->
                    appState.deleteCharacters(ids)
                },
                onCharacterImport = { card, avatar ->
                    appState.importCharacter(card, avatar)
                },
                onCharacterCreate = { name ->
                    appState.createCharacter(name)
                }
            )
        }
    }
}
