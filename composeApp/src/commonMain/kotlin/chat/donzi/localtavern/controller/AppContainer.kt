package chat.donzi.localtavern.controller

import chat.donzi.localtavern.data.database.ApiSettingsRepository
import chat.donzi.localtavern.data.database.CharacterRepository
import chat.donzi.localtavern.data.database.DriverFactory
import chat.donzi.localtavern.data.database.LocalTavernDB
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

class AppContainer(driverFactory: DriverFactory) {
    val database: LocalTavernDB = LocalTavernDB(driverFactory.createDriver())
    val secretCrypto = createSecretCrypto()
    val apiKeyCipher = ApiKeyCipher(secretCrypto)
    val characterRepository: CharacterRepository = CharacterRepository(database)
    val sessionRepository: SessionRepository = SessionRepository(database)
    val apiSettingsRepository: ApiSettingsRepository = ApiSettingsRepository(database, apiKeyCipher)
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
    // Key generation is a one-shot at startup; blocking the constructor is
    // acceptable (fast, and the UI waits on app initialization anyway).
    private val syncIdentityStore: SyncIdentityStore = createSyncIdentityStore()
    private val syncIdentity: SyncIdentity = runBlocking {
        loadOrCreateSyncIdentity(syncIdentityStore)
    }
    val syncRepository = SyncRepository(database, syncIdentity, apiKeyCipher = apiKeyCipher)
    val syncService = SyncService(
        identity = syncIdentity,
        crypto = SyncCrypto(),
        repository = syncRepository,
        identityStore = syncIdentityStore,
        httpClient = httpClient,
        scope = appScope,
        localAddressesProvider = { localIpAddresses() }
    ).also { it.startServer() }

    // LAN discovery: announces this device and learns the current addresses
    // of paired devices (so a peer whose IP changed is still reachable).
    val syncDiscovery = SyncDiscovery(
        identity = syncIdentity,
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

    val chatController: ChatController = ChatController(sessionRepository, apiSettingsRepository, pricingRepository, chatClient, appScope)
    val appState: AppState = AppState(characterRepository, apiSettingsRepository, sessionRepository, appScope)

    init {
        // Auto-sync with all paired devices when the app starts.
        appScope.launch {
            if (syncRepository.getPeers().isNotEmpty()) {
                syncService.syncAllPeersAsync()
            }
        }
    }

    // Cancels all app-level coroutines (generation, flows, settings writes)
    // and releases the HTTP client. Called when the composition is disposed,
    // e.g. on Android activity recreation: without this, an in-flight
    // generation keeps running in a leaked scope and its stop button in the
    // new UI silently does nothing.
    fun close() {
        appScope.cancel()
        syncDiscovery.stop()
        syncService.stopServer()
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
