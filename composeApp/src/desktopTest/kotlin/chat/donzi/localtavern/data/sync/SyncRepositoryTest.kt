package chat.donzi.localtavern.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.donzi.localtavern.data.database.LocalTavernDB
import chat.donzi.localtavern.data.database.SyncPeer
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

    private class Device(val repo: SyncRepository, val db: LocalTavernDB) {
        val personas get() = db.localTavernDBQueries.selectAllPersonas().executeAsList()
        val personaAny get() = db.localTavernDBQueries.selectPersonaByIdAny("p1").executeAsOneOrNull()
    }

    private fun TestScope.newDevice(deviceId: String): Device {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalTavernDB.Schema.create(driver)
        val db = LocalTavernDB(driver)
        val identity = SyncIdentity(
            deviceId = deviceId,
            deviceName = deviceId,
            privateKeyBase64 = kotlin.io.encoding.Base64.encode(ByteArray(32) { 1 }),
            publicKeyBase64 = kotlin.io.encoding.Base64.encode(ByteArray(32) { 2 })
        )
        return Device(SyncRepository(db, identity), db)
    }

    private fun syncPersona(id: String, name: String, updatedAt: Long, isDeleted: Long) = SyncPersona(
        id = id, name = name, description = null, avatarData = null,
        updatedAt = updatedAt, isDeleted = isDeleted
    )

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
}
