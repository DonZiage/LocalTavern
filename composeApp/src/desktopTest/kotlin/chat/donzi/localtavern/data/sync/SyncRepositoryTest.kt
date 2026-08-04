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
            updatedAt = updatedAt, isDeleted = 0L, syncSeq = 0L
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

        // The delta cut is on the device-local sync sequence, not on
        // updatedAt: each applied row was re-stamped 1, 2, 3 in arrival order.
        val delta = device.repo.collectDelta(2L)
        assertEquals(
            listOf("p2", "p3"),
            delta.personas.map { it.id },
            "Only rows at/after the sequence cursor, including tombstones"
        )
        assertEquals(
            listOf(2L, 3L),
            delta.personas.map { it.syncSeq },
            "The delta must carry each row's device-local sequence"
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
    fun peerCursors_neverRegress() = runTest {
        val device = newDevice("device-a")
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        device.repo.upsertPeer(
            SyncPeer(
                deviceId = "peer-1", name = "Laptop", publicKey = null,
                lastKnownAddress = null, receivedCursor = 0L, peerReceivedCursor = 0L,
                lastSyncAt = now, updatedAt = now, isDeleted = 0L
            )
        )
        device.repo.updatePeerCursors("peer-1", receivedCursor = 100L, peerReceivedCursor = 200L)

        // A peer restored from a backup (or a lost race) claims lower cursors;
        // the stored values must not move backwards.
        device.repo.updatePeerCursors("peer-1", receivedCursor = 0L, peerReceivedCursor = 0L)
        val peer = device.repo.getPeer("peer-1")!!
        assertEquals(100L, peer.receivedCursor, "receivedCursor must never regress")
        assertEquals(200L, peer.peerReceivedCursor, "peerReceivedCursor must never regress")

        // Higher values still advance normally.
        device.repo.updatePeerCursors("peer-1", receivedCursor = 150L, peerReceivedCursor = 250L)
        val advanced = device.repo.getPeer("peer-1")!!
        assertEquals(150L, advanced.receivedCursor)
        assertEquals(250L, advanced.peerReceivedCursor)
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
        device.insertConnection(id = "c1", apiKey = "ltv1:X(sk-1)", isActive = 1L, updatedAt = 1000L)

        // An old peer may still ship isActive=1; with an active profile already
        // in place this device must not adopt the wire flag.
        device.repo.applyChanges(
            SyncChanges(apiConnections = listOf(syncApiConnection("c2", apiKey = "sk-2", isActive = 1L, updatedAt = 2000L))),
            peerDeviceId = "device-b"
        )

        val stored = device.connAny("c2")
        assertNotNull(stored)
        assertEquals(0L, stored.isActive, "Incoming isActive=1 must be ignored when a profile is already active")
        assertEquals(1L, device.connAny("c1")!!.isActive, "The local active profile must not be flipped by the wire flag")
    }

    @Test
    fun apiKeySync_editFromPeerKeepsLocalActiveState() = runTest {
        val device = newDevice("device-a", apiKeyCipher = ApiKeyCipher(ReversibleTestSecretCrypto()))
        device.insertConnection(id = "c1", apiKey = "ltv1:X(sk-1)", isActive = 1L, updatedAt = 1000L)

        // A peer edits the same connection (its wire copy never carries the flag).
        device.repo.applyChanges(
            SyncChanges(apiConnections = listOf(syncApiConnection("c1", apiKey = "sk-2", updatedAt = 2000L))),
            peerDeviceId = "device-b"
        )

        val stored = device.connAny("c1")
        assertNotNull(stored)
        assertEquals(
            1L, stored.isActive,
            "Editing a connection on a peer must not deactivate the locally active profile"
        )
        assertEquals("ltv1:X(sk-2)", stored.apiKey, "The edit itself must still apply")
    }

    @Test
    fun apiKeySync_firstIncomingConnectionAutoActivatesLikeLocalInsert() = runTest {
        val device = newDevice("device-a", apiKeyCipher = ApiKeyCipher(ReversibleTestSecretCrypto()))

        device.repo.applyChanges(
            SyncChanges(apiConnections = listOf(syncApiConnection("c1", apiKey = "sk-1", updatedAt = 2000L))),
            peerDeviceId = "device-b"
        )

        val stored = device.connAny("c1")
        assertNotNull(stored)
        assertEquals(1L, stored.isActive, "The first connection on a device must become active, like a local insert")
    }

    @Test
    fun apiKeySync_incomingConnectionNeverStealsLocalActiveProfile() = runTest {
        val device = newDevice("device-a", apiKeyCipher = ApiKeyCipher(ReversibleTestSecretCrypto()))
        device.insertConnection(id = "c1", apiKey = "ltv1:X(sk-1)", isActive = 1L, updatedAt = 1000L)

        device.repo.applyChanges(
            SyncChanges(apiConnections = listOf(syncApiConnection("c2", apiKey = "sk-2", updatedAt = 2000L))),
            peerDeviceId = "device-b"
        )

        assertEquals(1L, device.connAny("c1")!!.isActive, "The locally active profile stays active")
        assertEquals(0L, device.connAny("c2")!!.isActive, "A new connection must not steal the active slot")
        val activeCount = device.db.localTavernDBQueries.selectAllApiConnections().executeAsList().count { it.isActive == 1L }
        assertEquals(1, activeCount, "Exactly one connection may be active at a time")
    }

    @Test
    fun apiKeySync_activeStateIsPerDeviceAndConverges() = runTest {
        // Both devices have their OWN active profile; exchanging edits must
        // never merge them into one or flip either side's choice.
        val a = newDevice("device-a", apiKeyCipher = ApiKeyCipher(ReversibleTestSecretCrypto()))
        val b = newDevice("device-b", apiKeyCipher = ApiKeyCipher(ReversibleTestSecretCrypto()))
        a.insertConnection(id = "c1", apiKey = "ltv1:X(sk-a)", isActive = 1L, updatedAt = 1000L)
        b.insertConnection(id = "c2", apiKey = "ltv1:X(sk-b)", isActive = 1L, updatedAt = 2000L)

        b.repo.applyChanges(a.repo.collectDelta(0L), peerDeviceId = "device-a")
        a.repo.applyChanges(b.repo.collectDelta(0L), peerDeviceId = "device-b")

        assertEquals(1L, a.connAny("c1")!!.isActive, "A keeps its own active profile")
        assertEquals(0L, a.connAny("c2")!!.isActive, "B's active flag never crosses the wire")
        assertEquals(0L, b.connAny("c1")!!.isActive, "A's active flag never crosses the wire")
        assertEquals(1L, b.connAny("c2")!!.isActive, "B keeps its own active profile")

        // A subsequent edit exchange must not disturb either side's choice.
        b.repo.applyChanges(a.repo.collectDelta(1000L), peerDeviceId = "device-a")
        a.repo.applyChanges(b.repo.collectDelta(2000L), peerDeviceId = "device-b")
        assertEquals(1L, a.connAny("c1")!!.isActive)
        assertEquals(1L, b.connAny("c2")!!.isActive)
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
        val delta2 = device.repo.collectDelta(delta1.maxSyncSeq + 1)

        assertEquals("Second", delta2.characters.single().name)
        assertTrue(
            delta2.characters.single().updatedAt > delta1.maxUpdatedAt,
            "The second write must carry a strictly larger logical stamp"
        )
        assertTrue(
            delta2.characters.single().syncSeq > delta1.maxSyncSeq,
            "The second write must carry a strictly larger sync sequence"
        )
    }

    // ---------- Late rows from lagging peers ----------
    //
    // A row received from a peer whose clock lags can carry an updatedAt well
    // below the cursors this device already reported. Delta queries must not
    // cut on updatedAt (such a row would be skipped forever); applied rows
    // are re-stamped with a fresh device-local sync sequence, so the late row
    // always lands at/after the peer's cursor and gets forwarded.

    @Test
    fun lateRowFromLaggingPeer_isForwardedDespiteLowStamp() = runTest {
        val a = newDevice("device-a")
        val b = newDevice("device-b")

        // First exchange: A sends its row; B's cursor for A advances past
        // the row's updatedAt stamp.
        a.insert("p1", "OnA", 1000L)
        val firstDelta = a.repo.collectDelta(0L)
        b.repo.applyChanges(firstDelta, peerDeviceId = "device-a")
        val cursorAfterFirstExchange = firstDelta.maxSyncSeq + 1

        // A row stamped at the OLD value arrives on A from a lagging third
        // device P, AFTER B's cursor already advanced past that stamp.
        a.repo.applyChanges(
            SyncChanges(personas = listOf(syncPersona("p-late", "Late", 1000L, 0L))),
            peerDeviceId = "device-p"
        )

        // A's next delta to B must still include the late row: it carries a
        // fresh device-local sequence, not its (stale) updatedAt.
        val secondDelta = a.repo.collectDelta(cursorAfterFirstExchange)
        assertEquals(
            listOf("p-late"),
            secondDelta.personas.map { it.id },
            "A row arriving late from a lagging peer must still be forwarded"
        )
        b.repo.applyChanges(secondDelta, peerDeviceId = "device-a")
        assertTrue(b.personas.any { it.id == "p-late" }, "B must receive the late row")
    }

    @Test
    fun appliedRowsAreRestampedWithLocalSequences() = runTest {
        val a = newDevice("device-a")
        val b = newDevice("device-b")

        a.insert("p1", "OnA", 1000L)
        a.insert("p2", "AlsoOnA", 2000L)
        b.repo.applyChanges(a.repo.collectDelta(0L), peerDeviceId = "device-a")

        // The received rows carry B's OWN sequences (1, 2), so B can forward
        // them regardless of their (foreign) updatedAt stamps.
        val bSeq = b.db.localTavernDBQueries.selectPersonaByIdAny("p1").executeAsOne()!!.syncSeq
        assertEquals(1L, bSeq, "Incoming rows must be re-stamped with the local sequence counter")
        assertEquals(2L, b.db.localTavernDBQueries.selectPersonaByIdAny("p2").executeAsOne()!!.syncSeq)
    }
}
