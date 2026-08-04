package chat.donzi.localtavern.ui.layout

import chat.donzi.localtavern.controller.ChatController
import chat.donzi.localtavern.data.database.ApiSettingsRepository
import chat.donzi.localtavern.data.database.CharacterRepository
import chat.donzi.localtavern.data.database.PricingRepository
import chat.donzi.localtavern.data.database.SessionRepository
import chat.donzi.localtavern.data.network.ChatClient
import chat.donzi.localtavern.data.security.ApiKeyCipher
import chat.donzi.localtavern.data.sync.SyncDiscovery
import chat.donzi.localtavern.data.sync.SyncRepository
import chat.donzi.localtavern.data.sync.SyncService

// Everything MainScreen needs from the app container, bundled so the wiring
// in App.kt stays short and the composable keeps one clear entry point.
data class MainScreenDependencies(
    val chatController: ChatController,
    val characterRepository: CharacterRepository,
    val sessionRepository: SessionRepository,
    val apiSettingsRepository: ApiSettingsRepository,
    val pricingRepository: PricingRepository,
    val apiKeyCipher: ApiKeyCipher,
    val syncService: SyncService,
    val syncRepository: SyncRepository,
    val syncDiscovery: SyncDiscovery,
    val chatClient: ChatClient,
    val onEnsureSyncRunning: () -> Unit = {}
)
