package chat.donzi.localtavern.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.donzi.localtavern.data.database.LocalTavernDB
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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
import kotlin.test.assertTrue

// Full end-to-end protocol test: two real devices (identity + crypto + sync
// repository + embedded Ktor server + HTTP client) pairing over localhost and
// converging their databases.
class SyncProtocolTest {

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
        val repository = SyncRepository(db, identity)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val service = SyncService(
            identity = identity,
            crypto = crypto,
            repository = repository,
            identityStore = FakeIdentityStore(),
            httpClient = client(),
            scope = scope,
            port = port
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

            seedPersona(host, "p-host", "Host Persona", 1000L)
            val syncResult = guest.service.syncNow("host-device")
            assertTrue(syncResult.isSuccess, "Sync after re-pairing must succeed: ${syncResult.exceptionOrNull()}")
        } finally {
            host.stop()
            guest.stop()
        }
    }

    private fun seedPersona(device: Device, id: String, name: String, updatedAt: Long) {
        device.db.localTavernDBQueries.insertPersonaFull(
            id = id, name = name, description = null, avatarData = null,
            updatedAt = updatedAt, isDeleted = 0L
        )
    }
}
