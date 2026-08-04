package chat.donzi.localtavern.ui
import chat.donzi.localtavern.ui.layout.MainScreenDependencies
import chat.donzi.localtavern.ui.layout.MainScreen
import chat.donzi.localtavern.ui.layout.ActiveDrawer

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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import chat.donzi.localtavern.controller.AppContainer
import chat.donzi.localtavern.data.database.DriverFactory
import chat.donzi.localtavern.isDesktop
import chat.donzi.localtavern.ui.theme.LocalTavernTheme
import chat.donzi.localtavern.ui.theme.ThemeTransition
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlinx.coroutines.delay

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
    val sendWithCtrlEnter by appState.sendWithCtrlEnter.collectAsState()
    val autoSyncOnLaunch by appState.autoSyncOnLaunch.collectAsState()
    val confirmBeforeDelete by appState.confirmBeforeDelete.collectAsState()
    val isInitialized by appState.isInitialized.collectAsState()
    val initError by appState.initError.collectAsState()
    // Sync bootstraps in the background (identity load/keygen); the main
    // screen must not be shown until it is ready, since MainScreen consumes
    // the sync components directly. The sync server and discovery do NOT
    // start here — they only run once the user engages with sync.
    val syncReady by container.syncReady.collectAsState()
    val syncError by container.syncError.collectAsState()

    val systemDark = isSystemInDarkTheme()
    var activeDrawer by remember { mutableStateOf(ActiveDrawer.None) }

    // Unlock gate: rendered BEFORE the loading spinner and the main screen.
    // Desktop: API keys are plaintext until the passphrase is set, so the
    // setup step is obligatory. Mobile: the device's own unlock method
    // (PIN/password/fingerprint) is asked for via the OS prompt; devices
    // without any unlock method open directly (a one-time recommendation
    // dialog is shown over the main UI instead).
    val secretGate = remember {
        SecretGateState(
            apiKeyCipher = container.apiKeyCipher,
            userAuthenticator = container.userAuthenticator,
            onProtected = { container.apiSettingsRepository.reencryptAllApiKeys() }
        )
    }

    when {
        secretGate.mode != SecretGateMode.Open -> {
            LocalTavernTheme(darkTheme = systemDark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SecretGate(secretGate)
                }
            }
        }
        !isInitialized || !syncReady -> {
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
        } else -> {
        // Idle auto-lock (desktop): after autoLockIdleMinutes of inactivity
        // the derived passphrase key is forgotten and the unlock gate
        // reappears. Activity = any pointer event or key press anywhere in
        // the app. 0 disables auto-lock.
        val autoLockMinutes by appState.autoLockIdleMinutes.collectAsState()
        var lastActivity by remember {
            mutableStateOf(TimeSource.Monotonic.markNow())
        }
        // Mobile first-launch screen-lock recommendation: only when the
        // device has no unlock method and the dialog has not been dismissed
        // yet. Checked here (after init) so the persisted flag is already
        // loaded and returning users never see it flash.
        val lockRecommendationShown by appState.lockRecommendationShown.collectAsState()
        if (!isDesktop && !container.userAuthenticator.isLockConfigured && !lockRecommendationShown) {
            DeviceLockRecommendationDialog(
                onContinue = { appState.setLockRecommendationShown() }
            )
        }
        LaunchedEffect(secretGate.mode, autoLockMinutes) {
            if (secretGate.mode == SecretGateMode.Open && autoLockMinutes > 0) {
                while (true) {
                    delay(30_000)
                    val idle = TimeSource.Monotonic.markNow() - lastActivity
                    if (idle >= autoLockMinutes.minutes) {
                        secretGate.lock()
                        break
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent()
                            lastActivity = TimeSource.Monotonic.markNow()
                        }
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        lastActivity = TimeSource.Monotonic.markNow()
                    }
                    false
                }
        ) {
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
                    messageRepository = container.messageRepository,
                    apiSettingsRepository = container.apiSettingsRepository,
                    apiKeyCipher = container.apiKeyCipher,
                    syncService = container.syncService,
                    syncRepository = container.syncRepository,
                    syncDiscovery = container.syncDiscovery,
                    chatClient = container.chatClient,
                    onEnsureSyncRunning = container::ensureSyncRunning
                ),
                characters = characters,
                personas = personas,
                activePersonaId = activePersonaId,
                isDarkMode = syncedDarkTheme,
                onToggleDarkMode = { _, centerOffset ->
                    triggerTransition(centerOffset)
                },
                autoSyncOnLaunch = autoSyncOnLaunch,
                onAutoSyncOnLaunchChange = { appState.setAutoSyncOnLaunch(it) },
                sendWithCtrlEnter = sendWithCtrlEnter,
                onSendWithCtrlEnterChange = { appState.setSendWithCtrlEnter(it) },
                confirmBeforeDelete = confirmBeforeDelete,
                onConfirmBeforeDeleteChange = { appState.setConfirmBeforeDelete(it) },
                autoLockIdleMinutes = autoLockMinutes,
                onAutoLockIdleMinutesChange = { appState.setAutoLockIdleMinutes(it) },
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
                onCharacterImport = { result ->
                    appState.importCharacters(result)
                },
                onCharacterCreate = { name ->
                    appState.createCharacter(name)
                }
            )
        }
        }
        }
    }
}
