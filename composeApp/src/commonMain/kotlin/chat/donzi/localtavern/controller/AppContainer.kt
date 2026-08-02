package chat.donzi.localtavern.controller

import chat.donzi.localtavern.data.database.ApiSettingsRepository
import chat.donzi.localtavern.data.database.CharacterRepository
import chat.donzi.localtavern.data.database.DriverFactory
import chat.donzi.localtavern.data.database.LocalTavernDB
import chat.donzi.localtavern.data.database.LogicalClock
import chat.donzi.localtavern.data.database.PricingRepository
import chat.donzi.localtavern.data.database.SessionRepository
import chat.donzi.localtavern.data.network.ChatClient
import chat.donzi.localtavern.data.security.ApiKeyCipher
import chat.donzi.localtavern.data.security.createSecretCrypto
import chat.donzi.localtavern.data.sync.SyncCrypto
import chat.donzi.localtavern.data.sync.SyncDiscovery
import chat.donzi.localtavern.data.sync.SyncIdentity
import chat.donzi.localtavern.data.sync.SyncIdentityStore
import chat.donzi.localtavern.data.sync.SyncRepository
import chat.donzi.localtavern.data.sync.SyncService
import chat.donzi.localtavern.data.sync.SYNC_PORT
import chat.donzi.localtavern.data.sync.createSyncIdentityStore
import chat.donzi.localtavern.data.sync.localIpAddresses
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class AppContainer(driverFactory: DriverFactory) {
    val database: LocalTavernDB = LocalTavernDB(driverFactory.createDriver())
    val secretCrypto = createSecretCrypto()
    val apiKeyCipher = ApiKeyCipher(secretCrypto)
    // One logical clock shared by every repository: the sync layer advances
    // it with every received timestamp and all local writes stamp from it,
    // keeping LWW immune to wall-clock skew between devices.
    val logicalClock = LogicalClock(database)
    val characterRepository: CharacterRepository = CharacterRepository(database, clock = logicalClock)
    val sessionRepository: SessionRepository = SessionRepository(database, clock = logicalClock)
    val apiSettingsRepository: ApiSettingsRepository = ApiSettingsRepository(database, apiKeyCipher, clock = logicalClock)
    val pricingRepository: PricingRepository = PricingRepository(database)

    val httpClient: HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(HttpTimeout) {
            // Total request timeout is disabled so long-running SSE streams are not killed.
            requestTimeoutMillis = 0
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
    }
    val chatClient: ChatClient = ChatClient(httpClient)

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Device identity for P2P sync: created once, stored platform-privately.
    // Loading it (or generating the X25519 keypair on first run) used to block
    // the constructor on the main thread at startup; it now runs in the
    // background and the UI's init gate (App) waits for syncReady before
    // showing the main screen.
    private val syncIdentityStore: SyncIdentityStore = createSyncIdentityStore()

    // Initialized by retrySyncBootstrap() once the identity resolves; the UI
    // must not touch them before syncReady is true.
    lateinit var syncRepository: SyncRepository
    lateinit var syncService: SyncService
    lateinit var syncDiscovery: SyncDiscovery

    private val _syncReady = MutableStateFlow(false)
    val syncReady: StateFlow<Boolean> = _syncReady.asStateFlow()

    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

    init {
        retrySyncBootstrap()
    }

    // Loads or creates the sync identity off the main thread, then wires up
    // the sync stack. Failures surface through syncError instead of leaving
    // the app on a permanent loading spinner; retry() re-runs the sequence.
    fun retrySyncBootstrap() {
        _syncError.value = null
        appScope.launch {
            try {
                val identity = withContext(Dispatchers.Default) {
                    loadOrCreateSyncIdentity(syncIdentityStore)
                }
                syncRepository = SyncRepository(database, identity, apiKeyCipher = apiKeyCipher, clock = logicalClock)
                syncService = SyncService(
                    identity = identity,
                    crypto = SyncCrypto(),
                    repository = syncRepository,
                    identityStore = syncIdentityStore,
                    httpClient = httpClient,
                    scope = appScope,
                    localAddressesProvider = { localIpAddresses() }
                ).also { it.startServer() }

                // LAN discovery: announces this device and learns the current
                // addresses of paired devices (so a peer whose IP changed is
                // still reachable).
                syncDiscovery = SyncDiscovery(
                    identity = identity,
                    scope = appScope,
                    syncPort = SYNC_PORT,
                    onPeerSeen = { discovered ->
                        appScope.launch {
                            val peer = syncRepository.getPeer(discovered.deviceId)
                            val newAddress = "${discovered.address}:${discovered.syncPort}"
                            if (peer != null && peer.lastKnownAddress != newAddress) {
                                syncRepository.updatePeerAddress(discovered.deviceId, newAddress)
                            }
                        }
                    }
                ).also { it.start() }

                _syncReady.value = true

                // Auto-sync with all paired devices when the app starts.
                if (syncRepository.getPeers().isNotEmpty()) {
                    syncService.syncAllPeersAsync()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _syncError.value = e.message ?: "Failed to initialize sync."
            }
        }
    }

    val chatController: ChatController = ChatController(sessionRepository, apiSettingsRepository, pricingRepository, chatClient, appScope)
    val appState: AppState = AppState(characterRepository, apiSettingsRepository, sessionRepository, appScope)

    // Cancels all app-level coroutines (generation, flows, settings writes)
    // and releases the HTTP client. Called when the composition is disposed,
    // e.g. on Android activity recreation: without this, an in-flight
    // generation keeps running in a leaked scope and its stop button in the
    // new UI silently does nothing.
    fun close() {
        appScope.cancel()
        if (::syncDiscovery.isInitialized) syncDiscovery.stop()
        if (::syncService.isInitialized) syncService.stopServer()
        httpClient.close()
    }
}

private suspend fun loadOrCreateSyncIdentity(store: SyncIdentityStore): SyncIdentity {
    val existing = store.load()?.let { SyncIdentity.deserialize(it) }
    if (existing != null) return existing
    val created = SyncIdentity.create("Device", SyncCrypto())
    store.save(SyncIdentity.serialize(created))
    return created
}
