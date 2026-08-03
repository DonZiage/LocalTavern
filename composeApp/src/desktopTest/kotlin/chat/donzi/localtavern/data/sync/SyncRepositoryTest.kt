package chat.donzi.localtavern.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.donzi.localtavern.data.database.CharacterRepository
import chat.donzi.localtavern.data.database.LocalTavernDB
import chat.donzi.localtavern.data.database.LogicalClock
import chat.donzi.localtavern.data.database.SyncPeer
import chat.donzi.localtavern.data.models.SillyTavernCardV2
import chat.donzi.localtavern.data.security.ApiKeyCipher
import chat.donzi.localtavern.data.security.ReversibleTestSecretCrypto
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Exercises delta collection + LWW merge with two in-memory databases playing
// the roles of the two devices.
class SyncRepositoryTest {

    private class Device(
        val repo: SyncRepository,
        val db: LocalTavernDB,
        val clock: LogicalClock
    ) {
        val personas get() = db.localTavernDBQueries.selectAllPersonas().executeAsList()
        val personaAny get() = db.localTavernDBQueries.selectPersonaByIdAny("p1").executeAsOneOrNull()
        fun connAny(id: String) = db.localTavernDBQueries.selectApiConnectionByIdAny(id).executeAsOneOrNull()
        fun charRepo() = CharacterRepository(db, clock = clock)
    }

    private fun TestScope.newDevice(
        deviceId: String,
        apiKeyCipher: ApiKeyCipher? = null,
        wallClock: (() -> Long)? = null
    ): Device {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalTavernDB.Schema.create(driver)
        val db = LocalTavernDB(driver)
        val clock = if (wallClock != null) LogicalClock(db, wallClock) else LogicalClock(db)
        val identity = SyncIdentity(
            deviceId = deviceId,
            deviceName = deviceId,
            privateKeyBase64 = kotlin.io.encoding.Base64.encode(ByteArray(32) { 1 }),
            publicKeyBase64 = kotlin.io.encoding.Base64.encode(ByteArray(32) { 2 })
        )
        return Device(SyncRepository(db, identity, apiKeyCipher = apiKeyCipher, clock = clock), db, clock)
    }

    private fun syncPersona(id: String, name: String, updatedAt: Long, isDeleted: Long) = SyncPersona(
        id = id, name = name, description = null, avatarData = null,
        updatedAt = updatedAt, isDeleted = isDeleted
    )

    private fun syncApiConnection(id: String, apiKey: String?, isActive: Long = 0L, updatedAt: Long = 1000L) =
        SyncApiConnection(
            id = id, provider = "openai", name = "Conn", baseUrl = "http://localhost",
            apiKey = apiKey, model = "gpt-4o", isActive = isActive, isChatCompletion = 1L,
            lastUsed = 0L, temperature = 1.0, topP = 1.0, topK = 0L, presencePenalty = 0.0,
            frequencyPenalty = 0.0, contextLimit = 4096L, responseLimit = 1024L,
            displayOrder = 0L, timeoutLimit = 60L, reasoningOverride = 0L,
            updatedAt = updatedAt, isDeleted = 0L
        )

    private fun Device.insertConnection(id: String, apiKey: String?, isActive: Long, updatedAt: Long) {
        db.localTavernDBQueries.insertApiConnectionFull(
            id = id, provider = "openai", name = "Conn", baseUrl = "http://localhost",
            apiKey = apiKey, model = "gpt-4o", inferenceProvider = null, quantization = null, isActive = isActive, isChatCompletion = 1L,
            lastUsed = 0L, temperature = 1.0, topP = 1.0, topK = 0L, presencePenalty = 0.0,
            frequencyPenalty = 0.0, contextLimit = 4096L, responseLimit = 1024L,
            displayOrder = 0L, timeoutLimit = 60L, reasoningOverride = 0L,
            updatedAt = updatedAt, isDeleted = 0L
        )
    }

    private suspend fun Device.insert(id: String, name: String, updatedAt: Long, isDeleted: Long = 0L) {
        repo.applyChanges(SyncChanges(personas = listOf(syncPersona(id, name, updatedAt, isDeleted))), peerDeviceId = "seed")
    }

    @Test
    fun collectDelta_returnsOnlyNewRowsAndTombstones() = runTest {
        val device = newDevice("device-a")
        device.insert("p1", "Alice", 1000L)
        device.insert("p2", "Bob", 2000L)
        device.insert("p3", "Carol", 3000L, isDeleted = 1L)

        val delta = device.repo.collectDelta(1500L)
        assertEquals(
            listOf("p2", "p3"),
            delta.personas.map { it.id },
            "Only rows newer than the cursor, including tombstones"
        )
    }

    @Test
    fun applyChanges_newerRowWins() = runTest {
        val device = newDevice("device-a")
        device.insert("p1", "Old", 1000L)

        device.repo.applyChanges(
            SyncChanges(personas = listOf(syncPersona("p1", "New", 2000L, 0L))),
            peerDeviceId = "device-b"
        )

        val stored = device.personas
        assertEquals(1, stored.size)
        assertEquals("New", stored.first().name)
        assertEquals(2000L, stored.first().updatedAt)
    }

    @Test
    fun applyChanges_olderRowLoses() = runTest {
        val device = newDevice("device-a")
        device.insert("p1", "Local", 3000L)

        device.repo.applyChanges(
            SyncChanges(personas = listOf(syncPersona("p1", "Stale", 1000L, 0L))),
            peerDeviceId = "device-b"
        )

        assertEquals("Local", device.personas.first().name, "A stale remote row must not overwrite a newer local one")
    }

    @Test
    fun applyChanges_tombstoneDeletesRow() = runTest {
        val device = newDevice("device-a")
        device.insert("p1", "Alice", 1000L)

        device.repo.applyChanges(
            SyncChanges(personas = listOf(syncPersona("p1", "Alice", 2000L, 1L))),
            peerDeviceId = "device-b"
        )

        // App-facing query hides it; the tombstone row remains for sync.
        assertTrue(device.personas.isEmpty())
        val any = device.personaAny
        assertNotNull(any)
        assertEquals(1L, any.isDeleted)
    }

    @Test
    fun applyChanges_tiedTimestamps_resolveDeterministically() = runTest {
        val a = newDevice("aaa")
        val z = newDevice("zzz")

        // Conflicting tied updates: peer "zzz" sorts after "aaa" so its
        // version wins on both devices.
        a.repo.applyChanges(
            SyncChanges(personas = listOf(syncPersona("p1", "FromZ", 1000L, 0L))),
            peerDeviceId = "zzz"
        )
        z.repo.applyChanges(
            SyncChanges(personas = listOf(syncPersona("p1", "FromZ", 1000L, 0L))),
            peerDeviceId = "aaa"
        )

        assertEquals("FromZ", a.personas.first().name, "Lexicographically larger peer wins the tie on A")
        assertEquals("FromZ", z.personas.first().name, "Both sides must converge on the same winner")
    }

    @Test
    fun peerStore_upsertGetDelete() = runTest {
        val device = newDevice("device-a")
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()

        device.repo.upsertPeer(
            SyncPeer(
                deviceId = "peer-1", name = "Laptop", publicKey = ByteArray(32) { 1 },
                lastKnownAddress = "10.0.0.2:47324",
                receivedCursor = 0L, peerReceivedCursor = 0L, lastSyncAt = now, updatedAt = now, isDeleted = 0L
            )
        )
        val peer = device.repo.getPeer("peer-1")
        assertNotNull(peer)
        assertEquals("Laptop", peer.name)

        // Upsert again (existing row must be updated, not duplicated).
        device.repo.upsertPeer(
            SyncPeer(
                deviceId = "peer-1", name = "Laptop Pro", publicKey = ByteArray(32) { 1 },
                lastKnownAddress = "10.0.0.2:47324",
                receivedCursor = 0L, peerReceivedCursor = 0L, lastSyncAt = now, updatedAt = now, isDeleted = 0L
            )
        )
        assertEquals("Laptop Pro", device.repo.getPeer("peer-1")?.name)

        device.repo.deletePeer("peer-1")
        assertNull(device.repo.getPeer("peer-1"), "Deleted peers must not be returned by app-facing queries")
    }

    @Test
    fun peerCursors_persist() = runTest {
        val device = newDevice("device-a")
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        device.repo.upsertPeer(
            SyncPeer(
                deviceId = "peer-1", name = "Laptop", publicKey = null,
                lastKnownAddress = null, receivedCursor = 0L, peerReceivedCursor = 0L,
                lastSyncAt = now, updatedAt = now, isDeleted = 0L
            )
        )
        device.repo.updatePeerCursors("peer-1", receivedCursor = 123L, peerReceivedCursor = 456L)
        val peer = device.repo.getPeer("peer-1")!!
        assertEquals(123L, peer.receivedCursor)
        assertEquals(456L, peer.peerReceivedCursor)
    }

    @Test
    fun apiKeySync_incomingPlaintextIsReencryptedUnderLocalBackend() = runTest {
        val cipher = ApiKeyCipher(ReversibleTestSecretCrypto())
        val device = newDevice("device-a", apiKeyCipher = cipher)

        // The peer ships the key as portable plaintext inside the E2E envelope.
        device.repo.applyChanges(
            SyncChanges(apiConnections = listOf(syncApiConnection("c1", apiKey = "sk-123", updatedAt = 2000L))),
            peerDeviceId = "device-b"
        )

        val stored = device.connAny("c1")
        assertNotNull(stored)
        assertEquals("ltv1:X(sk-123)", stored.apiKey, "Incoming plaintext key must be stored encrypted under the LOCAL backend")
        assertEquals("sk-123", cipher.decryptFromStorage(stored.apiKey), "The locally stored blob must round-trip")
    }

    @Test
    fun apiKeySync_sendSideShipsPortablePlaintextAndNoActiveFlag() = runTest {
        val cipher = ApiKeyCipher(ReversibleTestSecretCrypto())
        val device = newDevice("device-a", apiKeyCipher = cipher)
        device.insertConnection(id = "c1", apiKey = "ltv1:X(sk-456)", isActive = 1L, updatedAt = 3000L)

        val delta = device.repo.collectDelta(0L)

        val wired = delta.apiConnections.single()
        assertEquals("sk-456", wired.apiKey, "The locally decryptable key must travel as portable plaintext")
        assertEquals(0L, wired.isActive, "The active flag is a per-device preference and must not cross the wire")
    }

    @Test
    fun apiKeySync_undecryptableLocalKeyIsWithheld() = runTest {
        val cipher = ApiKeyCipher(ReversibleTestSecretCrypto())
        val device = newDevice("device-a", apiKeyCipher = cipher)
        // A blob the local backend cannot decrypt (unknown format, locked-out
        // backend) must not be shipped: the peer could never use it.
        device.insertConnection(id = "c1", apiKey = "ltv1:garbage-not-x(...)", isActive = 0L, updatedAt = 3000L)

        val delta = device.repo.collectDelta(0L)

        assertNull(delta.apiConnections.single().apiKey, "An undecryptable key must be withheld, not shipped")
    }

    @Test
    fun apiKeySync_withheldOrForeignKeyKeepsExistingStoredKey() = runTest {
        val cipher = ApiKeyCipher(ReversibleTestSecretCrypto())
        val device = newDevice("device-a", apiKeyCipher = cipher)
        device.insertConnection(id = "c1", apiKey = "ltv1:X(my-key)", isActive = 0L, updatedAt = 1000L)

        // A newer row whose key was withheld (null): keep the working local key.
        device.repo.applyChanges(
            SyncChanges(apiConnections = listOf(syncApiConnection("c1", apiKey = null, updatedAt = 2000L))),
            peerDeviceId = "device-b"
        )
        assertEquals("ltv1:X(my-key)", device.connAny("c1")!!.apiKey)

        // A newer row carrying a FOREIGN encrypted blob (older peer version):
        // unusable here, so the local key must survive the LWW update too.
        device.repo.applyChanges(
            SyncChanges(apiConnections = listOf(syncApiConnection("c1", apiKey = "ltv1:foreign-blob", updatedAt = 3000L))),
            peerDeviceId = "device-b"
        )
        assertEquals("ltv1:X(my-key)", device.connAny("c1")!!.apiKey, "A foreign blob must not clobber the local key")
    }

    @Test
    fun apiKeySync_incomingActiveFlagIsIgnored() = runTest {
        val device = newDevice("device-a", apiKeyCipher = ApiKeyCipher(ReversibleTestSecretCrypto()))

        // An old peer may still ship isActive=1; this device must not adopt it.
        device.repo.applyChanges(
            SyncChanges(apiConnections = listOf(syncApiConnection("c1", apiKey = "sk-1", isActive = 1L, updatedAt = 2000L))),
            peerDeviceId = "device-b"
        )

        val stored = device.connAny("c1")
        assertNotNull(stored)
        assertEquals(0L, stored.isActive, "Incoming isActive=1 must be ignored so sync cannot flip the local profile")
    }

    // ---------- Clock-skew resilience ----------
    //
    // The LWW comparison runs on updatedAt values. If those were raw wall
    // clocks, a device whose clock is behind a peer's would lose its own
    // edits to the peer's future-dated versions even though the edits are
    // newer. The logical clock stamps local writes above every timestamp
    // ever observed, so causality wins regardless of clock drift.

    @Test
    fun editBySlowClockDeviceAfterSyncingFastClockVersion_wins() = runTest {
        // A's wall clock is far ahead of B's (fake clocks freeze the drift).
        val a = newDevice("device-a", wallClock = { 2_000_000L })
        val b = newDevice("device-b", wallClock = { 0L })

        // A creates a character; the row is stamped with A's future time.
        val id = a.charRepo().upsertCharacter(SillyTavernCardV2(name = "Original"))

        // A syncs to B. B absorbs the future-dated stamp into its clock.
        b.repo.applyChanges(a.repo.collectDelta(0L), peerDeviceId = "device-a")
        assertEquals("Original", b.charRepo().getCharacterById(id)?.name)

        // B edits the character. Its wall clock is still at 0, but the edit
        // must out-stamp the version it was caused by.
        b.charRepo().updateCharacter(id = id, name = "Edited On B", personality = "p", scenario = "s", description = null, firstMes = null)

        // B syncs back to A: the edit must win, not be rejected as stale.
        a.repo.applyChanges(b.repo.collectDelta(0L), peerDeviceId = "device-b")
        assertEquals("Edited On B", a.charRepo().getCharacterById(id)?.name)
    }

    @Test
    fun editAfterReceivingPeerEdit_winsOnBothDevices() = runTest {
        val a = newDevice("device-a", wallClock = { 1_000_000L })
        val b = newDevice("device-b", wallClock = { 100L })

        val id = a.charRepo().upsertCharacter(SillyTavernCardV2(name = "V1"))
        b.repo.applyChanges(a.repo.collectDelta(0L), peerDeviceId = "device-a")

        // B edits, then A edits after seeing B's version. Each device's new
        // edit must out-stamp the version it observed.
        b.charRepo().updateCharacter(id = id, name = "V2 From B", personality = "p", scenario = "s", description = null, firstMes = null)
        a.repo.applyChanges(b.repo.collectDelta(0L), peerDeviceId = "device-b")
        a.charRepo().updateCharacter(id = id, name = "V3 From A", personality = "p", scenario = "s", description = null, firstMes = null)
        b.repo.applyChanges(a.repo.collectDelta(0L), peerDeviceId = "device-a")

        assertEquals("V3 From A", b.charRepo().getCharacterById(id)?.name, "A's causally-later edit wins on B")
        assertEquals("V3 From A", a.charRepo().getCharacterById(id)?.name, "And on A itself")
    }

    @Test
    fun syncedRowsAreStampedByLocalLogicalClockNotWallClock() = runTest {
        val device = newDevice("device-a", wallClock = { 500L })

        // Two local writes in the same frozen wall-clock instant must still
        // be distinguishable and strictly ordered for the sync cursors.
        device.charRepo().upsertCharacter(SillyTavernCardV2(name = "First"))
        val delta1 = device.repo.collectDelta(0L)
        device.charRepo().upsertCharacter(SillyTavernCardV2(name = "Second"))
        val delta2 = device.repo.collectDelta(delta1.maxUpdatedAt)

        assertEquals("Second", delta2.characters.single().name)
        assertTrue(
            delta2.characters.single().updatedAt > delta1.maxUpdatedAt,
            "The second write must carry a strictly larger logical stamp"
        )
    }
}
