package chat.donzi.localtavern.controller

import chat.donzi.localtavern.data.blob.BlobStore
import chat.donzi.localtavern.data.blob.createBlobStore
import chat.donzi.localtavern.data.blob.migrateMessageImagesToBlobStore
import chat.donzi.localtavern.data.blob.runBlobGc
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
import chat.donzi.localtavern.data.sync.DeviceName
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
import io.ktor.client.plugins.HttpTimeoutConfig
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
    private val driver = driverFactory.createDriver()
    val database: LocalTavernDB = LocalTavernDB(driver)
    // Content-addressed store for message image blobs (never in SQLite).
    val blobStore: BlobStore = createBlobStore()
    // All DB access must go through ONE thread: the JVM driver opens a separate
    // SQLite connection per thread (JdbcSqliteDriver.ThreadedConnectionManager),
    // so concurrent transactions on Dispatchers.IO run on different connections
    // and one of them fails with "SQL is busy" (SQLITE_BUSY) — e.g. switching
    // characters fast overlaps getOrCreateSession/ensureInitialGreetings writes.
    // Serializing on a single dispatcher keeps every statement on one connection.
    private val databaseDispatcher = Dispatchers.IO.limitedParallelism(1)
    val secretCrypto = createSecretCrypto()
    val apiKeyCipher = ApiKeyCipher(secretCrypto)
    // One logical clock shared by every repository: the sync layer advances
    // it with every received timestamp and all local writes stamp from it,
    // keeping LWW immune to wall-clock skew between devices.
    val logicalClock = LogicalClock(database)
    val characterRepository: CharacterRepository = CharacterRepository(database, clock = logicalClock, ioDispatcher = databaseDispatcher)
    val sessionRepository: SessionRepository = SessionRepository(database, ioDispatcher = databaseDispatcher, clock = logicalClock, blobStore = blobStore)
    val apiSettingsRepository: ApiSettingsRepository = ApiSettingsRepository(database, apiKeyCipher, databaseDispatcher, clock = logicalClock)
    val pricingRepository: PricingRepository = PricingRepository(database, databaseDispatcher)

    val httpClient: HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(HttpTimeout) {
            // Total request timeout is disabled so long-running SSE streams are not killed.
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
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
    // the sync stack (repository + service + discovery). Nothing listens or
    // broadcasts yet: the server and discovery only start on demand via
    // ensureSyncRunning(). Failures surface through syncError instead of
    // leaving the app on a permanent loading spinner; retry() re-runs the
    // sequence.
    fun retrySyncBootstrap() {
        _syncError.value = null
        appScope.launch {
            try {
                val identity = withContext(Dispatchers.Default) {
                    loadOrCreateSyncIdentity(syncIdentityStore)
                }
                // One-time migration: v10 kept the legacy imageData BLOB column
                // so the actual offload could run in app code (the .sqm files
                // run atomically with no hook between statements). Reads every
                // row that still carries inline images, writes the blobs to the
                // content-addressed store and replaces the bytes with refs.
                // Idempotent: rows are processed until none remain, so it is
                // safe to run on every start.
                val migratedImages = migrateMessageImagesToBlobStore(database, blobStore, logicalClock)
                if (migratedImages > 0) {
                    // Reclaim the freed database pages (the column contents
                    // were the bulk of the file).
                    withContext(databaseDispatcher) { driver.execute(null, "VACUUM", 0) }
                }
                // Unreferenced blobs (deleted/edited messages) are dropped now
                // that the store is consistent with the database.
                runBlobGc(database, blobStore)
                syncRepository = SyncRepository(database, identity, ioDispatcher = databaseDispatcher, apiKeyCipher = apiKeyCipher, clock = logicalClock, blobStore = blobStore)
                syncService = SyncService(
                    identity = identity,
                    crypto = SyncCrypto(),
                    repository = syncRepository,
                    identityStore = syncIdentityStore,
                    httpClient = httpClient,
                    scope = appScope,
                    localAddressesProvider = { localIpAddresses() },
                    // Stop LAN discovery in lockstep with the server: an idle
                    // server must not keep announcing a dead port on the LAN.
                    onServerStopped = { if (::syncDiscovery.isInitialized) syncDiscovery.stop() }
                )

                // LAN discovery: announces this device and learns the current
                // addresses of paired devices (so a peer whose IP changed is
                // still reachable). Created here, but only started once the
                // user actually engages with sync (see ensureSyncRunning).
                syncDiscovery = SyncDiscovery(
                    identity = identity,
                    scope = appScope,
                    syncPort = SYNC_PORT,
                    // Read the name live so a rename is reflected in LAN
                    // announcements without recreating discovery.
                    deviceNameProvider = { syncService.identity.deviceName },
                    onPeerSeen = { discovered ->
                        appScope.launch {
                            val peer = syncRepository.getPeer(discovered.deviceId)
                            val newAddress = "${discovered.address}:${discovered.syncPort}"
                            if (peer != null && peer.lastKnownAddress != newAddress) {
                                syncRepository.updatePeerAddress(discovered.deviceId, newAddress)
                            }
                        }
                    }
                )

                _syncReady.value = true

                // Auto-sync (opt-out in App Settings): engage the sync stack
                // and exchange changes with every paired device right away.
                if (apiSettingsRepository.getAppSettings().autoSyncOnLaunch != 0L) {
                    syncService.startServer()
                    if (::syncDiscovery.isInitialized) syncDiscovery.start()
                    syncService.syncAllPeersAsync()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _syncError.value = e.message ?: "Failed to initialize sync."
            }
        }
    }

    // The sync server and LAN discovery only run while the user is actually
    // using sync: an idle app must not listen on the sync port or broadcast
    // on the LAN, and SyncService's idle watchdog shuts both down after five
    // minutes without sync activity. Every sync UI action calls this first
    // (idempotent) to bring them back up.
    fun ensureSyncRunning() {
        if (!::syncService.isInitialized) return
        syncService.startServer()
        if (::syncDiscovery.isInitialized) syncDiscovery.start()
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
    if (existing != null) {
        // Migrate legacy installs whose name was hardcoded (or never set):
        // give them a friendly generated name instead. The id and keypair
        // are untouched, so pairings stay valid.
        val usable = SyncIdentity.migratedName(existing.deviceName)
        if (usable != null) return existing
        val renamed = existing.withName(DeviceName.generate())
        store.save(SyncIdentity.serialize(renamed))
        return renamed
    }
    val created = SyncIdentity.createDefault(SyncCrypto())
    store.save(SyncIdentity.serialize(created))
    return created
}
