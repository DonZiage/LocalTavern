package chat.donzi.localtavern.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.donzi.localtavern.data.blob.BlobStore
import chat.donzi.localtavern.data.database.LocalTavernDB
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Full end-to-end protocol test: two real devices (identity + crypto + sync
// repository + embedded Ktor server + HTTP client) pairing over localhost and
// converging their databases.
class SyncProtocolTest {

    private class InMemoryBlobStore : BlobStore {
        private val map = mutableMapOf<String, ByteArray>()
        override suspend fun write(key: String, bytes: ByteArray) {
            map[key] = bytes
        }

        override suspend fun read(key: String): ByteArray? = map[key]

        override suspend fun delete(key: String) {
            map.remove(key)
        }

        override suspend fun listKeys(): Set<String> = map.keys.toSet()
    }

    private class FakeIdentityStore : SyncIdentityStore {
        private var bytes: ByteArray? = null
        override fun load(): ByteArray? = bytes
        override fun save(bytes: ByteArray) {
            this.bytes = bytes
        }
    }

    private class Device(deviceId: String, name: String, port: Int) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { LocalTavernDB.Schema.create(it) }
        val db = LocalTavernDB(driver)
        val crypto = SyncCrypto()
        val identity = runBlocking {
            SyncIdentity.create(deviceName = name, crypto = crypto).copy(deviceId = deviceId)
        }
        val blobStore = InMemoryBlobStore()
        val repository = SyncRepository(db, identity, blobStore = blobStore)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val service = SyncService(
            identity = identity,
            crypto = crypto,
            repository = repository,
            identityStore = FakeIdentityStore(),
            httpClient = client(),
            scope = scope,
            port = port,
            blobStore = blobStore
        )

        fun start() = service.startServer()

        fun stop() {
            service.stopServer()
            scope.cancel()
        }

        fun personas() = db.localTavernDBQueries.selectAllPersonas().executeAsList()

        companion object {
            private fun client() = HttpClient {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; isLenient = true })
                }
            }
        }
    }

    // Fresh client for manual protocol calls in tests.
    private fun httpClient(): HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
    }

    private fun freePort(): Int {
        // Deterministic per-test ports: tests in a class run sequentially, so
        // fixed distinct ports avoid random bind collisions entirely.
        val base = 23100 + portCounter++
        return base
    }

    private companion object {
        var portCounter = 0
    }

    @Test
    fun pairAndSync_convergesBothDatabases() = runTest {
        val hostPort = freePort()

        val host = Device("host-device", "Host", hostPort)
        val guest = Device("guest-device", "Guest", hostPort + 1)
        try {
            host.start()
            guest.start()

            // Host starts pairing (shows a PIN).
            host.service.startPairing()
            val pin = host.service.state.value.pairingPin!!

            // Guest connects to host: pairing + initial sync in one step.
            val result = guest.service.connectToDevice("127.0.0.1", hostPort, pin)
            assertTrue(result.isSuccess, "Pairing must succeed: ${result.exceptionOrNull()}")
            val hostPeerId = result.getOrThrow()
            assertEquals("host-device", hostPeerId)

            // Guest now holds the host as a peer.
            assertTrue(guest.repository.getPeer("host-device") != null)
            // Host holds the guest as a peer.
            assertTrue(host.repository.getPeer("guest-device") != null)
        } finally {
            host.stop()
            guest.stop()
        }
    }

    @Test
    fun pairing_presentsMatchingFingerprints() = runTest {
        val hostPort = freePort()
        val host = Device("host-device", "Host", hostPort)
        val guest = Device("guest-device", "Guest", hostPort + 1)
        try {
            host.start()
            guest.start()

            host.service.startPairing()
            val pin = host.service.state.value.pairingPin!!
            guest.service.connectToDevice("127.0.0.1", hostPort, pin)

            // Host shows the fingerprint over BOTH keys (its own and the one
            // it received)...
            val hostFingerprint = host.service.state.value.pendingPeerFingerprint
            assertNotNull(hostFingerprint)
            assertEquals(
                hostFingerprint,
                host.crypto.pairingFingerprint(host.identity.publicKeyBytes, guest.identity.publicKeyBytes)
            )

            // ...and the guest shows the same value over the same key pair.
            val guestFingerprint = guest.service.state.value.pendingPeerFingerprint
            assertNotNull(guestFingerprint)
            assertEquals(
                guestFingerprint,
                guest.crypto.pairingFingerprint(guest.identity.publicKeyBytes, host.identity.publicKeyBytes)
            )

            // Both screens present the SAME string, so a user comparing them
            // can detect a swapped key (which would change the value).
            assertEquals(hostFingerprint, guestFingerprint)
            assertEquals("A1B2-C3D4-E5F6-G7H8".length, hostFingerprint.length)
        } finally {
            host.stop()
            guest.stop()
        }
    }

    @Test
    fun wrongPin_isRejected() = runTest {
        val hostPort = freePort()
        val host = Device("host-device", "Host", hostPort)
        val guest = Device("guest-device", "Guest", hostPort + 1)
        try {
            host.start()
            host.service.startPairing()
            val pin = host.service.state.value.pairingPin!!

            val result = guest.service.connectToDevice("127.0.0.1", hostPort, pin.reversed())
            assertTrue(result.isFailure, "Wrong PIN must be rejected")
            assertTrue(host.repository.getPeer("guest-device") == null, "Failed pairing must not create a peer")
        } finally {
            host.stop()
            guest.stop()
        }
    }

    @Test
    fun repeatedWrongPins_lockOutPairingEvenWithCorrectPin() = runTest {
        val hostPort = freePort()
        val host = Device("host-device", "Host", hostPort)
        val guest = Device("guest-device", "Guest", hostPort + 1)
        try {
            host.start()
            host.service.startPairing()
            val pin = host.service.state.value.pairingPin!!

            // Exhaust the attempt budget with wrong pins.
            repeat(5) {
                val result = guest.service.connectToDevice("127.0.0.1", hostPort, pin.reversed())
                assertTrue(result.isFailure)
            }

            // The correct PIN is now refused: online guessing cannot proceed
            // without restarting the pairing session (which rotates the PIN).
            val lockedOut = guest.service.connectToDevice("127.0.0.1", hostPort, pin)
            assertTrue(lockedOut.isFailure, "Pairing must lock out after repeated failures")
            assertTrue(lockedOut.exceptionOrNull()?.message?.contains("Too many failed", ignoreCase = true) == true)
            assertTrue(host.repository.getPeer("guest-device") == null)

            // Restarting pairing resets the budget.
            host.service.cancelPairing()
            host.service.startPairing()
            val newPin = host.service.state.value.pairingPin!!
            val retry = guest.service.connectToDevice("127.0.0.1", hostPort, newPin)
            assertTrue(retry.isSuccess, "Restarting pairing must clear the lockout")
        } finally {
            host.stop()
            guest.stop()
        }
    }

    @Test
    fun fullSync_movesRowsBothWays() = runTest {
        val hostPort = freePort()
        val host = Device("host-device", "Host", hostPort)
        val guest = Device("guest-device", "Guest", hostPort + 1)
        try {
            host.start()
            guest.start()

            // Pre-seed data on both devices (via raw SQL to control timestamps).
            seedPersona(host, "p-host", "Host Persona", 1000L)
            seedPersona(guest, "p-guest", "Guest Persona", 2000L)

            host.service.startPairing()
            val pin = host.service.state.value.pairingPin!!
            guest.service.connectToDevice("127.0.0.1", hostPort, pin)
            host.service.confirmFingerprint()
            guest.service.confirmFingerprint()

            // Guest initiates the sync.
            val syncResult = guest.service.syncNow("host-device")
            assertTrue(syncResult.isSuccess, "Sync must succeed: ${syncResult.exceptionOrNull()}")

            // Guest received host's persona.
            val guestPersonas = guest.personas()
            assertEquals(
                setOf("p-host", "p-guest"),
                guestPersonas.map { it.id }.toSet(),
                "Guest must have both personas after sync"
            )

            // Host received guest's persona.
            val hostPersonas = host.personas()
            assertEquals(
                setOf("p-host", "p-guest"),
                hostPersonas.map { it.id }.toSet(),
                "Host must have both personas after sync"
            )
            assertEquals("Host Persona", hostPersonas.first { it.id == "p-host" }.name)
        } finally {
            host.stop()
            guest.stop()
        }
    }

    @Test
    fun syncAfterPeerAddressChange_stillSucceeds() = runTest {
        val hostPort = freePort()
        val host = Device("host-device", "Host", hostPort)
        val guest = Device("guest-device", "Guest", hostPort + 1)
        try {
            host.start()
            guest.start()
            seedPersona(host, "p-host", "Host Persona", 1000L)

            host.service.startPairing()
            val pin = host.service.state.value.pairingPin!!
            guest.service.connectToDevice("127.0.0.1", hostPort, pin)
            host.service.confirmFingerprint()
            guest.service.confirmFingerprint()

            // Simulate the peer having changed IP (DHCP): the stored address
            // is wrong, discovery would fix it, but a manual update must be
            // picked up by syncNow.
            guest.repository.updatePeerAddress("host-device", "127.0.0.1:$hostPort")
            val syncResult = guest.service.syncNow("host-device")
            assertTrue(syncResult.isSuccess, "Sync must succeed after address update: ${syncResult.exceptionOrNull()}")
            assertTrue(guest.personas().any { it.id == "p-host" })
        } finally {
            host.stop()
            guest.stop()
        }
    }

    @Test
    fun rotatedIdentityKey_invalidatesOldPairings() = runTest {
        val hostPort = freePort()
        val host = Device("host-device", "Host", hostPort)
        val guest = Device("guest-device", "Guest", hostPort + 1)
        try {
            host.start()
            guest.start()
            seedPersona(host, "p-host", "Host Persona", 1000L)

            host.service.startPairing()
            val pin = host.service.state.value.pairingPin!!
            guest.service.connectToDevice("127.0.0.1", hostPort, pin)
            host.service.confirmFingerprint()
            guest.service.confirmFingerprint()

            // Host rotates its key: all pairings must be dropped.
            val rotateResult = host.service.rotateIdentityKey()
            assertTrue(rotateResult.isSuccess, "Rotation must succeed: ${rotateResult.exceptionOrNull()}")
            assertTrue(host.repository.getPeers().isEmpty(), "Rotation must clear the host's peers")
            assertTrue(host.service.identity.publicKeyBase64 != host.identity.publicKeyBase64, "Rotation must generate a new key")

            // The guest's stored (old) key no longer authenticates: the host
            // rejects the exchange.
            val syncResult = guest.service.syncNow("host-device")
            assertTrue(syncResult.isFailure, "Sync with a rotated host key must fail")
        } finally {
            host.stop()
            guest.stop()
        }
    }

    @Test
    fun rePairAfterRotation_succeeds() = runTest {
        val hostPort = freePort()
        val host = Device("host-device", "Host", hostPort)
        val guest = Device("guest-device", "Guest", hostPort + 1)
        try {
            host.start()
            guest.start()

            host.service.startPairing()
            val pin = host.service.state.value.pairingPin!!
            guest.service.connectToDevice("127.0.0.1", hostPort, pin)
            host.service.rotateIdentityKey()

            // Both sides must drop the stale peer before re-pairing; the host
            // cleared its own store on rotation, the guest forgets the host.
            guest.repository.deletePeer("host-device")

            host.service.startPairing()
            val newPin = host.service.state.value.pairingPin!!
            val result = guest.service.connectToDevice("127.0.0.1", hostPort, newPin)
            assertTrue(result.isSuccess, "Re-pairing after rotation must succeed: ${result.exceptionOrNull()}")
            host.service.confirmFingerprint()
            guest.service.confirmFingerprint()

            seedPersona(host, "p-host", "Host Persona", 1000L)
            val syncResult = guest.service.syncNow("host-device")
            assertTrue(syncResult.isSuccess, "Sync after re-pairing must succeed: ${syncResult.exceptionOrNull()}")
        } finally {
            host.stop()
            guest.stop()
        }
    }

    @Test
    fun renamedDevice_propagatesNewNameOverAuthenticatedExchange() = runTest {
        val hostPort = freePort()
        val host = Device("host-device", "Host", hostPort)
        val guest = Device("guest-device", "Guest", hostPort + 1)
        try {
            host.start()
            guest.start()

            host.service.startPairing()
            val pin = host.service.state.value.pairingPin!!
            guest.service.connectToDevice("127.0.0.1", hostPort, pin)
            host.service.confirmFingerprint()
            guest.service.confirmFingerprint()

            // At pairing time the host records the name the guest sent.
            assertEquals("Guest", host.repository.getPeer("guest-device")!!.name)

            // Guest renames: its id and keypair are untouched, so the
            // pairing stays valid.
            val before = guest.service.identity
            val rename = guest.service.renameDevice("  Tablet   Zero  ")
            assertTrue(rename.isSuccess, "Rename must succeed: ${rename.exceptionOrNull()}")
            assertEquals("Tablet Zero", rename.getOrThrow())
            assertEquals(before.deviceId, guest.service.identity.deviceId)
            assertEquals(before.privateKeyBase64, guest.service.identity.privateKeyBase64)
            assertEquals("Tablet Zero", guest.service.deviceName.value)

            // The new name rides inside the encrypted, AAD-bound envelope:
            // the host only trusts it because the exchange authenticated the
            // sender as the holder of the paired key.
            val syncResult = guest.service.syncNow("host-device")
            assertTrue(syncResult.isSuccess, "Sync after rename must succeed: ${syncResult.exceptionOrNull()}")

            val stored = host.repository.getPeer("guest-device")
            assertEquals("Tablet Zero", stored!!.name, "Host must adopt the authenticated new name")
            // Renaming must not disturb sync state: cursors still advance.
            val syncAgain = guest.service.syncNow("host-device")
            assertTrue(syncAgain.isSuccess, "Second sync must succeed: ${syncAgain.exceptionOrNull()}")
        } finally {
            host.stop()
            guest.stop()
        }
    }

    @Test
    fun pairing_storesBothPeerNames() = runTest {
        val hostPort = freePort()
        val host = Device("host-device", "Host", hostPort)
        val guest = Device("guest-device", "Guest", hostPort + 1)
        try {
            host.start()
            guest.start()

            host.service.startPairing()
            val pin = host.service.state.value.pairingPin!!
            guest.service.connectToDevice("127.0.0.1", hostPort, pin)

            assertEquals("Host", guest.repository.getPeer("host-device")!!.name)
            assertEquals("Guest", host.repository.getPeer("guest-device")!!.name)
        } finally {
            host.stop()
            guest.stop()
        }
    }

    @Test
    fun fullSync_lateRowFromLaggingPeerIsForwarded() = runTest {
        val hostPort = freePort()
        val host = Device("host-device", "Host", hostPort)
        val guest = Device("guest-device", "Guest", hostPort + 1)
        try {
            host.start()
            guest.start()
            seedPersona(guest, "p-guest", "Guest Persona", 1000L)

            host.service.startPairing()
            val pin = host.service.state.value.pairingPin!!
            guest.service.connectToDevice("127.0.0.1", hostPort, pin)
            host.service.confirmFingerprint()
            guest.service.confirmFingerprint()

            // First exchange: guest's row reaches the host and both sides'
            // cursors advance past its stamp.
            var syncResult = guest.service.syncNow("host-device")
            assertTrue(syncResult.isSuccess, "First sync must succeed: ${syncResult.exceptionOrNull()}")
            assertTrue(host.personas().any { it.id == "p-guest" })

            // A row stamped at the OLD value arrives on the guest from a
            // lagging third device, after the host's cursor already passed
            // that stamp. The sequence re-stamp must make it forwardable.
            guest.repository.applyChanges(
                SyncChanges(personas = listOf(SyncPersona(id = "p-late", name = "Late", description = null, avatarData = null, updatedAt = 1000L, isDeleted = 0L))),
                peerDeviceId = "device-p"
            )

            // Second exchange: the late row must still reach the host.
            syncResult = guest.service.syncNow("host-device")
            assertTrue(syncResult.isSuccess, "Second sync must succeed: ${syncResult.exceptionOrNull()}")
            assertTrue(host.personas().any { it.id == "p-late" }, "A late row from a lagging peer must be forwarded")
        } finally {
            host.stop()
            guest.stop()
        }
    }

    @Test
    fun imageBlobs_areFetchedOutOfBandAfterSync() = runTest {
        val hostPort = freePort()
        val host = Device("host-device", "Host", hostPort)
        val guest = Device("guest-device", "Guest", hostPort + 1)
        try {
            host.start()
            guest.start()
            host.service.startPairing()
            val pin = host.service.state.value.pairingPin!!
            guest.service.connectToDevice("127.0.0.1", hostPort, pin)
            host.service.confirmFingerprint()
            guest.service.confirmFingerprint()

            // Host holds a message whose image blob is larger than one chunk,
            // so the fetch must reassemble several pieces.
            val img = ByteArray(600_000) { (it % 251).toByte() }
            val hash = runBlocking { chat.donzi.localtavern.utils.Hashing.sha256Hex(img) }
            runBlocking { host.blobStore.write(hash, img) }
            host.repository.applyChanges(
                SyncChanges(messages = listOf(
                    SyncMessage(
                        id = "m1", sessionId = "s1", role = "assistant", content = "Hello",
                        timestamp = 1L, parentId = null, isActivePath = 1L,
                        updatedAt = 1000L, isDeleted = 0L,
                        imageRefs = listOf(SyncImageRef(hash, img.size.toLong())),
                        reasoningText = null, costEstimate = null
                    )
                )),
                peerDeviceId = "device-p"
            )

            // Guest syncs: the row arrives and the blob is pulled out of band
            // during the same call.
            val syncResult = guest.service.syncNow("host-device")
            assertTrue(syncResult.isSuccess, "Sync must succeed: ${syncResult.exceptionOrNull()}")

            val fetched = guest.blobStore.read(hash)
            assertNotNull(fetched, "The referenced blob must be fetched from the peer")
            assertEquals(img.toList(), fetched.toList(), "The reassembled blob must be byte-identical")

            // The row on the guest references the blob.
            val row = guest.db.localTavernDBQueries.selectMessageByIdAny("m1").executeAsOne()!!
            assertTrue(row.imageRefs!!.contains(hash), "The row must carry the fetched ref")
        } finally {
            host.stop()
            guest.stop()
        }
    }

    @Test
    fun imageBlobNotServedByPeer_isSkippedGracefully() = runTest {
        val hostPort = freePort()
        val host = Device("host-device", "Host", hostPort)
        val guest = Device("guest-device", "Guest", hostPort + 1)
        try {
            host.start()
            guest.start()
            host.service.startPairing()
            val pin = host.service.state.value.pairingPin!!
            guest.service.connectToDevice("127.0.0.1", hostPort, pin)
            host.service.confirmFingerprint()
            guest.service.confirmFingerprint()

            // The host references a blob it does not actually have (e.g. it
            // never fetched it from its own upstream peer). The ref is a
            // well-formed SHA-256 key, so it passes ingest validation.
            val missingHash = runBlocking { chat.donzi.localtavern.utils.Hashing.sha256Hex(ByteArray(64) { 7 }) }
            host.repository.applyChanges(
                SyncChanges(messages = listOf(
                    SyncMessage(
                        id = "m1", sessionId = "s1", role = "assistant", content = "Hello",
                        timestamp = 1L, parentId = null, isActivePath = 1L,
                        updatedAt = 1000L, isDeleted = 0L,
                        imageRefs = listOf(SyncImageRef(missingHash, 42L)),
                        reasoningText = null, costEstimate = null
                    )
                )),
                peerDeviceId = "device-p"
            )

            val syncResult = guest.service.syncNow("host-device")
            assertTrue(syncResult.isSuccess, "A missing blob must not fail the sync: ${syncResult.exceptionOrNull()}")
            assertNull(guest.blobStore.read(missingHash), "An unservable blob must simply stay absent")

            // A second sync must not error either (the ref is remembered).
            val again = guest.service.syncNow("host-device")
            assertTrue(again.isSuccess, "Second sync must succeed: ${again.exceptionOrNull()}")
        } finally {
            host.stop()
            guest.stop()
        }
    }

    @Test
    fun legacyInlineImages_areStoredAndRefdOnTheReceivingSide() = runTest {
        val hostPort = freePort()
        val host = Device("host-device", "Host", hostPort)
        val guest = Device("guest-device", "Guest", hostPort + 1)
        try {
            host.start()
            guest.start()
            host.service.startPairing()
            val pin = host.service.state.value.pairingPin!!
            guest.service.connectToDevice("127.0.0.1", hostPort, pin)
            host.service.confirmFingerprint()
            guest.service.confirmFingerprint()

            // A pre-blob-store peer ships the serialized image bytes inline.
            val img = ByteArray(64) { 3 }
            val legacyBlob = chat.donzi.localtavern.utils.serializeImageList(listOf(img))!!
            host.repository.applyChanges(
                SyncChanges(messages = listOf(
                    SyncMessage(
                        id = "m1", sessionId = "s1", role = "assistant", content = "Hello",
                        timestamp = 1L, parentId = null, isActivePath = 1L,
                        updatedAt = 1000L, isDeleted = 0L,
                        imageData = legacyBlob,
                        reasoningText = null, costEstimate = null
                    )
                )),
                peerDeviceId = "device-p"
            )

            val syncResult = guest.service.syncNow("host-device")
            assertTrue(syncResult.isSuccess, "Sync must succeed: ${syncResult.exceptionOrNull()}")

            // The bytes arrived inline and were converted to a stored blob.
            val hash = runBlocking { chat.donzi.localtavern.utils.Hashing.sha256Hex(img) }
            val stored = guest.blobStore.read(hash)
            assertNotNull(stored, "Legacy inline bytes must be stored content-addressed")
            assertEquals(img.toList(), stored.toList())

            val row = guest.db.localTavernDBQueries.selectMessageByIdAny("m1").executeAsOne()!!
            assertTrue(row.imageRefs!!.contains(hash), "The receiving side must adopt refs for legacy images")
        } finally {
            host.stop()
            guest.stop()
        }
    }

    @Test
    fun syncIsRefusedUntilPairingFingerprintIsConfirmed() = runTest {
        val hostPort = freePort()
        val host = Device("host-device", "Host", hostPort)
        val guest = Device("guest-device", "Guest", hostPort + 1)
        try {
            host.start()
            guest.start()
            seedPersona(host, "p-host", "Host Persona", 1000L)

            host.service.startPairing()
            val pin = host.service.state.value.pairingPin!!
            guest.service.connectToDevice("127.0.0.1", hostPort, pin)

            // Neither device has confirmed the out-of-band fingerprint yet:
            // the peer is not trusted, so sync must be refused.
            val early = guest.service.syncNow("host-device")
            assertTrue(early.isFailure, "Sync must be refused before the fingerprint is confirmed")
            assertTrue(
                early.exceptionOrNull()?.message?.contains("fingerprint", ignoreCase = true) == true,
                "The refusal must point at the fingerprint: ${early.exceptionOrNull()?.message}"
            )

            // Confirming on both sides opens the exchange.
            host.service.confirmFingerprint()
            guest.service.confirmFingerprint()
            val syncResult = guest.service.syncNow("host-device")
            assertTrue(syncResult.isSuccess, "Sync must succeed after confirmation: ${syncResult.exceptionOrNull()}")
            assertTrue(guest.personas().any { it.id == "p-host" })
        } finally {
            host.stop()
            guest.stop()
        }
    }

    @Test
    fun replayedExchangeRequest_isRejected() = runTest {
        val hostPort = freePort()
        val host = Device("host-device", "Host", hostPort)
        val guest = Device("guest-device", "Guest", hostPort + 1)
        try {
            host.start()
            guest.start()
            host.service.startPairing()
            val pin = host.service.state.value.pairingPin!!
            guest.service.connectToDevice("127.0.0.1", hostPort, pin)
            host.service.confirmFingerprint()
            guest.service.confirmFingerprint()

            // Build one exchange request exactly as syncNow would, but with a
            // FIXED exchange id so it can be sent twice verbatim.
            val exchangeId = "fixed-replay-id"
            val channelKeys = SyncChannelKeys(guest.crypto) { guest.identity }
            val envelope = SyncEnvelope(
                fromDeviceId = guest.identity.deviceId,
                cursor = 0L,
                changes = SyncChanges(),
                fromDeviceName = guest.identity.deviceName
            )
            val json = Json { ignoreUnknownKeys = true; isLenient = true }
            val channelKey = runBlocking { channelKeys.outboundChannelKey(host.identity.publicKeyBytes) }
            val payload = runBlocking {
                encodeBase64(
                    guest.crypto.encrypt(
                        channelKey.key,
                        aad(guest.identity.deviceId, host.identity.deviceId, exchangeId),
                        json.encodeToString(SyncEnvelope.serializer(), envelope).encodeToByteArray()
                    )
                )
            }
            val request = ExchangeRequest(
                fromDeviceId = guest.identity.deviceId,
                exchangeId = exchangeId,
                payload = payload,
                ephemeralPublicKey = encodeBase64(channelKey.ephemeralPublicKey)
            )
            suspend fun post(): ExchangeResponse = httpClient().post("http://127.0.0.1:$hostPort/exchange") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body()

            val first = post()
            assertTrue(first.ok, "First exchange must succeed: ${first.message}")
            val second = post()
            assertFalse(second.ok, "A verbatim replay of the exchange must be rejected")
            assertTrue(second.message.contains("Replayed", ignoreCase = true), "Unexpected rejection message: ${second.message}")
        } finally {
            host.stop()
            guest.stop()
        }
    }

    @Test
    fun invalidBlobRefs_fromWireAreDroppedAtIngest() = runTest {
        val hostPort = freePort()
        val host = Device("host-device", "Host", hostPort)
        val guest = Device("guest-device", "Guest", hostPort + 1)
        try {
            host.start()
            guest.start()
            host.service.startPairing()
            val pin = host.service.state.value.pairingPin!!
            guest.service.connectToDevice("127.0.0.1", hostPort, pin)
            host.service.confirmFingerprint()
            guest.service.confirmFingerprint()

            // A hostile peer ships a path-traversal ref next to a valid one;
            // only the well-formed SHA-256 key may survive ingest.
            val validHash = runBlocking { chat.donzi.localtavern.utils.Hashing.sha256Hex(ByteArray(16) { 9 }) }
            guest.repository.applyChanges(
                SyncChanges(messages = listOf(
                    SyncMessage(
                        id = "m1", sessionId = "s1", role = "assistant", content = "Hello",
                        timestamp = 1L, parentId = null, isActivePath = 1L,
                        updatedAt = 1000L, isDeleted = 0L,
                        imageRefs = listOf(
                            SyncImageRef("../../../../etc/passwd", 1L),
                            SyncImageRef(validHash, 16L)
                        ),
                        reasoningText = null, costEstimate = null
                    )
                )),
                peerDeviceId = "device-p"
            )

            val row = guest.db.localTavernDBQueries.selectMessageByIdAny("m1").executeAsOne()!!
            val refs = chat.donzi.localtavern.utils.deserializeImageRefs(row.imageRefs)
            assertEquals(1, refs.size, "The path-traversal ref must be dropped")
            assertEquals(validHash, refs[0].sha256)
        } finally {
            host.stop()
            guest.stop()
        }
    }

    private fun seedPersona(device: Device, id: String, name: String, updatedAt: Long) {
        device.db.localTavernDBQueries.insertPersonaFull(
            id = id, name = name, description = null, avatarData = null,
            updatedAt = updatedAt, isDeleted = 0L, syncSeq = 0L
        )
    }
}
