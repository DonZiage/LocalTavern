package chat.donzi.localtavern.ui

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import chat.donzi.localtavern.controller.ChatUiState
import chat.donzi.localtavern.data.database.LocalTavernDB
import chat.donzi.localtavern.data.database.SyncPeer
import chat.donzi.localtavern.data.security.ApiKeyCipher
import chat.donzi.localtavern.data.security.DesktopPassphraseSecretCrypto
import chat.donzi.localtavern.data.security.NoopUserAuthenticator
import chat.donzi.localtavern.data.sync.SyncCrypto
import chat.donzi.localtavern.data.sync.SyncDiscovery
import chat.donzi.localtavern.data.sync.SyncIdentity
import chat.donzi.localtavern.data.sync.SyncIdentityStore
import chat.donzi.localtavern.data.sync.SyncRepository
import chat.donzi.localtavern.data.sync.SyncService
import chat.donzi.localtavern.data.network.ChatClient
import chat.donzi.localtavern.domain.Message
import chat.donzi.localtavern.ui.chat.ChatActions
import chat.donzi.localtavern.ui.chat.ChatMessageList
import chat.donzi.localtavern.ui.settings.ApiConnectionDialog
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

// Screen-level smoke tests: render the top-level screens through their state
// seams and assert the key semantic nodes exist. These catch "the screen
// crashes when composed" regressions (bad state wiring, missing params,
// layout exceptions) that unit tests on state holders cannot. Deliberately
// shallow — deep behavior is covered by the state-holder tests.
@OptIn(ExperimentalTestApi::class)
class ScreenSmokeTest {

    @Test
    fun chatScreen_rendersMessagesAndInput() = runComposeUiTest {
        val state = ChatUiState(
            messages = listOf(
                Message(
                    id = "u1", sessionId = "s1", role = "user", content = "Tell me a story",
                    timestamp = 1L, parentId = null, isActivePath = true
                ),
                Message(
                    id = "a1", sessionId = "s1", role = "assistant", content = "Once upon a time…",
                    timestamp = 2L, parentId = "u1", isActivePath = true
                )
            ),
            siblingsMap = mapOf("u1" to emptyList(), "a1" to emptyList()),
            currentSession = null,
            isGenerating = false
        )
        setContent {
            ChatMessageList(
                chatState = state,
                activeCharacter = null,
                activePersonaName = "Me",
                activePersonaAvatar = null,
                isSelectMode = false,
                selectedMessageIds = emptySet(),
                actions = ChatActions(
                    onSendMessage = { _, _ -> false },
                    onEditMessage = { _, _, _ -> },
                    onDeleteMessage = {},
                    onDeleteMessages = {},
                    onRegenerate = {},
                    onSelectVariation = {},
                    onGenerateNewVariation = {},
                    onStopGeneration = {},
                    onManageChats = {},
                    onBranchMessage = {},
                    onGoToParentChat = null,
                    onAddImageToMessage = { _, _ -> },
                    onNavigateToSettings = {},
                    onNavigateToPersonas = {},
                    onNavigateToCharacters = {}
                ),
                onSelectMessageToggle = {},
                onRequestDelete = {},
                onRequestAddImage = {}
            )
        }
        onNodeWithText("Tell me a story").assertExists()
        onNodeWithText("Once upon a time…").assertExists()
    }

    @Test
    fun secretGate_setupMode_rendersPassphraseScreen() = runComposeUiTest {
        val tempDir = Files.createTempDirectory("localtavern-gate").toFile()
        try {
            val crypto = DesktopPassphraseSecretCrypto(dataDir = tempDir)
            val state = SecretGateState(ApiKeyCipher(crypto), NoopUserAuthenticator)
            setContent { SecretGate(state) }
            onNodeWithText("Protect your API keys").assertExists()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun apiConnectionDialog_rendersEndpointStep() = runComposeUiTest {
        setContent {
            ApiConnectionDialog(
                chatClient = ChatClient(HttpClient()),
                initialConnection = null,
                onDismiss = {},
                onSave = { _, _, _, _, _, _ -> }
            )
        }
        onNodeWithText("1. Endpoint URL (Required)").assertExists()
        onNodeWithText("Next").assertExists()
    }

    @Test
    fun syncSettings_rendersDeviceAndControls() = runComposeUiTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalTavernDB.Schema.create(driver)
        val db = LocalTavernDB(driver)
        val identity = SyncIdentity(
            deviceId = "device-smoke",
            deviceName = "Smoke Laptop",
            privateKeyBase64 = kotlin.io.encoding.Base64.encode(ByteArray(32) { 1 }),
            publicKeyBase64 = kotlin.io.encoding.Base64.encode(ByteArray(32) { 2 })
        )
        val repository = SyncRepository(db, identity)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val service = SyncService(
                initialIdentity = identity,
                crypto = SyncCrypto(),
                repository = repository,
                identityStore = FakeIdentityStore(),
                httpClient = HttpClient(),
                scope = scope
            )
            val discovery = SyncDiscovery(
                identity = identity,
                scope = scope,
                syncPort = 0
            )
            setContent {
                chat.donzi.localtavern.ui.sync.SyncSettingsSection(
                    syncService = service,
                    syncRepository = repository,
                    syncDiscovery = discovery,
                    onEnsureSyncRunning = {}
                )
            }
            onNodeWithText("This device: Smoke Laptop").assertExists()
            onNodeWithText("Sync").assertExists()
            onNodeWithText("Sync Key").assertExists()
            // An empty peer list shows the pairing entry point via the Sync
            // button; no crash with a fresh (peerless) database.
            onNodeWithText("Sync All").assertDoesNotExist()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun syncSettings_rendersConflictCardWhenEventsExist() = runComposeUiTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalTavernDB.Schema.create(driver)
        val db = LocalTavernDB(driver)
        val identity = SyncIdentity(
            deviceId = "device-smoke",
            deviceName = "Smoke Laptop",
            privateKeyBase64 = kotlin.io.encoding.Base64.encode(ByteArray(32) { 1 }),
            publicKeyBase64 = kotlin.io.encoding.Base64.encode(ByteArray(32) { 2 })
        )
        val repository = SyncRepository(db, identity)
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        kotlinx.coroutines.runBlocking {
            repository.upsertPeer(
                SyncPeer(
                    deviceId = "device-phone", name = "Phone", publicKey = null,
                    lastKnownAddress = null, receivedCursor = 0L, peerReceivedCursor = 0L,
                    lastSyncAt = now, updatedAt = now, isDeleted = 0L
                )
            )
            db.localTavernDBQueries.insertConflictEventIgnore(
                rowId = "p1", tableName = "Persona", peerDeviceId = "device-phone",
                localUpdatedAt = 100L, incomingUpdatedAt = 200L, createdAt = now
            )
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val service = SyncService(
                initialIdentity = identity,
                crypto = SyncCrypto(),
                repository = repository,
                identityStore = FakeIdentityStore(),
                httpClient = HttpClient(),
                scope = scope
            )
            val discovery = SyncDiscovery(identity = identity, scope = scope, syncPort = 0)
            setContent {
                chat.donzi.localtavern.ui.sync.SyncSettingsSection(
                    syncService = service,
                    syncRepository = repository,
                    syncDiscovery = discovery,
                    onEnsureSyncRunning = {}
                )
            }
            onNodeWithText("1 edit overwritten by a paired device").assertExists()
        } finally {
            scope.cancel()
        }
    }

    // Collects a one-shot flow (the unseen-conflicts query) off the UI thread.
    private class FakeIdentityStore : SyncIdentityStore {
        private var bytes: ByteArray? = null
        override fun load(): ByteArray? = bytes
        override fun save(bytes: ByteArray) {
            this.bytes = bytes
        }
    }
}
