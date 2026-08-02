package chat.donzi.localtavern.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.controller.AppContainer
import chat.donzi.localtavern.data.database.DriverFactory
import chat.donzi.localtavern.ui.components.ActiveDrawer
import chat.donzi.localtavern.ui.theme.LocalTavernTheme
import chat.donzi.localtavern.ui.theme.ThemeTransition

@Composable
fun App(driverFactory: DriverFactory, onThemeChanged: (Boolean) -> Unit = {}) {
    val container = remember { AppContainer(driverFactory) }
    DisposableEffect(container) {
        onDispose {
            container.close()
        }
    }
    val appState = container.appState
    val chatController = container.chatController

    val characters by appState.characters.collectAsState()
    val personas by appState.personas.collectAsState()
    val activePersonaId by appState.activePersonaId.collectAsState()
    val darkModeFromDb by appState.isDarkMode.collectAsState()
    val isInitialized by appState.isInitialized.collectAsState()
    val initError by appState.initError.collectAsState()
    // Sync bootstraps in the background (identity load/keygen); the main
    // screen must not be shown until it is ready, since MainScreen consumes
    // the sync components directly.
    val syncReady by container.syncReady.collectAsState()
    val syncError by container.syncError.collectAsState()

    val systemDark = isSystemInDarkTheme()
    var activeDrawer by remember { mutableStateOf(ActiveDrawer.None) }

    if (!isInitialized || !syncReady) {
        LocalTavernTheme(darkTheme = systemDark) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                val initError = initError ?: syncError
                if (initError != null) {
                    // Surface initialization failures instead of spinning
                    // forever with no explanation.
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = initError,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 32.dp)
                            )
                            Button(onClick = {
                                appState.retry()
                                container.retrySyncBootstrap()
                            }) {
                                Text("Retry")
                            }
                        }
                    }
                } else {
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
                deps = MainScreenDependencies(
                    chatController = chatController,
                    characterRepository = container.characterRepository,
                    sessionRepository = container.sessionRepository,
                    apiSettingsRepository = container.apiSettingsRepository,
                    pricingRepository = container.pricingRepository,
                    apiKeyCipher = container.apiKeyCipher,
                    syncService = container.syncService,
                    syncRepository = container.syncRepository,
                    syncDiscovery = container.syncDiscovery,
                    chatClient = container.chatClient
                ),
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
