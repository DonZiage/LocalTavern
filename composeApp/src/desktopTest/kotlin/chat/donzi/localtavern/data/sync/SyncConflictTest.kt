package chat.donzi.localtavern.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.donzi.localtavern.data.database.LocalTavernDB
import chat.donzi.localtavern.data.database.LogicalClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Exercises the conflict ledger: LWW sync silently overwrites a local edit
// when a paired device's newer version wins; the ledger records an event so
// the UI can surface it. A conflict is recorded exactly when a STRICTLY newer
// incoming version replaces a local version that this peer had never
// announced (see SyncRepository.noteIncoming).
class SyncConflictTest {

    private class Device(val repo: SyncRepository, val db: LocalTavernDB)

    private fun TestScope.newDevice(deviceId: String): Device {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalTavernDB.Schema.create(driver)
        val db = LocalTavernDB(driver)
        val clock = LogicalClock(db)
        val identity = SyncIdentity(
            deviceId = deviceId,
            deviceName = deviceId,
            privateKeyBase64 = kotlin.io.encoding.Base64.encode(ByteArray(32) { 1 }),
            publicKeyBase64 = kotlin.io.encoding.Base64.encode(ByteArray(32) { 2 })
        )
        return Device(SyncRepository(db, identity, clock = clock), db)
    }

    private fun persona(id: String, name: String, updatedAt: Long, isDeleted: Long = 0L) = SyncPersona(
        id = id, name = name, description = null, avatarData = null,
        updatedAt = updatedAt, isDeleted = isDeleted
    )

    // "Local" writes go through the repository like a synced row would; the
    // seed peer is just the stand-in for this device's own local history.
    private suspend fun Device.insertLocal(persona: SyncPersona) {
        repo.applyChanges(SyncChanges(personas = listOf(persona)), peerDeviceId = "seed")
    }

    private suspend fun Device.unseenConflicts(): List<chat.donzi.localtavern.data.database.ConflictEvent> =
        repo.observeUnseenConflicts().first()

    @Test
    fun `concurrent edits on both devices record a conflict on the loser only`() = runTest {
        val a = newDevice("device-a")
        val b = newDevice("device-b")

        // Both devices edit the same row before syncing: A's edit (3000)
        // predates B's edit (4000) in LWW order.
        a.insertLocal(persona("p1", "A's edit", 3000L))
        b.insertLocal(persona("p1", "B's edit", 4000L))

        // A receives B's newer version: A's edit is silently discarded.
        a.repo.applyChanges(SyncChanges(personas = listOf(persona("p1", "B's edit", 4000L))), peerDeviceId = "device-b")
        val aConflicts = a.unseenConflicts()
        assertEquals(1, aConflicts.size, "A's unseen edit was overwritten: one event expected")
        val event = aConflicts.first()
        assertEquals("p1", event.rowId)
        assertEquals("Persona", event.tableName)
        assertEquals("device-b", event.peerDeviceId)
        assertEquals(3000L, event.localUpdatedAt, "The event must carry the discarded local stamp")
        assertEquals(4000L, event.incomingUpdatedAt, "The event must carry the winning peer stamp")
        assertEquals("B's edit", a.db.localTavernDBQueries.selectPersonaByIdAny("p1").executeAsOneOrNull()?.name)

        // B receives A's older version: B's edit wins, nothing is lost.
        b.repo.applyChanges(SyncChanges(personas = listOf(persona("p1", "A's edit", 3000L))), peerDeviceId = "device-a")
        assertTrue(b.unseenConflicts().isEmpty(), "The winning device must not record a conflict")
    }

    @Test
    fun `peer edit after a clean prior sync is convergence, not a conflict`() = runTest {
        val a = newDevice("device-a")

        // A applied B's v1 during an earlier sync (ledger = 1000).
        a.repo.applyChanges(SyncChanges(personas = listOf(persona("p1", "B v1", 1000L))), peerDeviceId = "device-b")
        // B edits to v2; A receives it. A never edited after v1, so no event.
        a.repo.applyChanges(SyncChanges(personas = listOf(persona("p1", "B v2", 2000L))), peerDeviceId = "device-b")
        assertTrue(a.unseenConflicts().isEmpty(), "A never edited: plain convergence must not record")

        // A receives a stale v1.5 echo: also convergence (incoming loses).
        a.repo.applyChanges(SyncChanges(personas = listOf(persona("p1", "B v1.5", 1500L))), peerDeviceId = "device-b")
        assertTrue(a.unseenConflicts().isEmpty())
    }

    @Test
    fun `peer echoing this device's own version never records`() = runTest {
        val a = newDevice("device-a")

        // A's own edit, echoed back by B at the SAME stamp (applied rows keep
        // the author's updatedAt). Equal stamps are not a conflict.
        a.insertLocal(persona("p1", "Mine", 2000L))
        a.repo.applyChanges(SyncChanges(personas = listOf(persona("p1", "Mine", 2000L))), peerDeviceId = "device-b")
        assertTrue(a.unseenConflicts().isEmpty(), "An echo of this device's own version must not record")
    }

    @Test
    fun `peer delete over a local edit records a conflict`() = runTest {
        val a = newDevice("device-a")
        a.insertLocal(persona("p1", "Mine", 3000L))

        a.repo.applyChanges(
            SyncChanges(personas = listOf(persona("p1", "Mine", 4000L, isDeleted = 1L))),
            peerDeviceId = "device-b"
        )
        val conflicts = a.unseenConflicts()
        assertEquals(1, conflicts.size, "A delete replacing an unseen local edit is a lost edit too")
        assertEquals("Persona", conflicts.first().tableName)
    }

    @Test
    fun `reapplying the same envelope is idempotent`() = runTest {
        val a = newDevice("device-a")
        a.insertLocal(persona("p1", "Mine", 3000L))

        val changes = SyncChanges(personas = listOf(persona("p1", "Theirs", 4000L)))
        a.repo.applyChanges(changes, peerDeviceId = "device-b")
        a.repo.applyChanges(changes, peerDeviceId = "device-b")

        assertEquals(1, a.unseenConflicts().size, "Re-applying the same envelope must not duplicate the event")
    }

    @Test
    fun `stale incoming never records, a later newer one does`() = runTest {
        val a = newDevice("device-a")
        a.insertLocal(persona("p1", "Mine", 3000L))

        // B announces a version older than A's edit: loses, no event.
        a.repo.applyChanges(SyncChanges(personas = listOf(persona("p1", "B stale", 2000L))), peerDeviceId = "device-b")
        assertTrue(a.unseenConflicts().isEmpty(), "A losing incoming never discards anything")

        // B (still unaware of A's 3000) announces 3500: A's edit is lost now.
        a.repo.applyChanges(SyncChanges(personas = listOf(persona("p1", "B newer", 3500L))), peerDeviceId = "device-b")
        val conflicts = a.unseenConflicts()
        assertEquals(1, conflicts.size)
        assertEquals(3000L, conflicts.first().localUpdatedAt)
    }

    @Test
    fun `tied stamps resolve by device id without recording`() = runTest {
        val a = newDevice("aaa")
        val z = newDevice("zzz")
        // Both edited at exactly the same stamp (pre-sync). The tie-break
        // converges both devices on "zzz"; neither edit postdates the other's
        // knowledge, so the tie is not reported as a conflict.
        a.insertLocal(persona("p1", "FromZ", 1000L))
        z.insertLocal(persona("p1", "FromZ", 1000L))
        a.repo.applyChanges(SyncChanges(personas = listOf(persona("p1", "FromZ", 1000L))), peerDeviceId = "zzz")
        z.repo.applyChanges(SyncChanges(personas = listOf(persona("p1", "FromZ", 1000L))), peerDeviceId = "aaa")

        assertTrue(a.unseenConflicts().isEmpty())
        assertTrue(z.unseenConflicts().isEmpty())
        assertEquals("FromZ", a.db.localTavernDBQueries.selectPersonaByIdAny("p1").executeAsOneOrNull()?.name)
        assertEquals("FromZ", z.db.localTavernDBQueries.selectPersonaByIdAny("p1").executeAsOneOrNull()?.name)
    }

    @Test
    fun `dismissal hides events from the unseen flow`() = runTest {
        val a = newDevice("device-a")
        a.insertLocal(persona("p1", "Mine", 3000L))
        a.repo.applyChanges(SyncChanges(personas = listOf(persona("p1", "Theirs", 4000L))), peerDeviceId = "device-b")
        a.insertLocal(persona("p2", "Mine 2", 3000L))
        a.repo.applyChanges(SyncChanges(personas = listOf(persona("p2", "Theirs 2", 4000L))), peerDeviceId = "device-b")

        assertEquals(2, a.unseenConflicts().size)

        // Dismiss one.
        val firstId = a.unseenConflicts().first().id
        a.repo.markConflictSeen(listOf(firstId))
        val remaining = a.unseenConflicts()
        assertEquals(1, remaining.size)
        assertTrue(remaining.none { it.id == firstId }, "Dismissed events must leave the unseen set")

        // Dismiss all.
        a.repo.markAllConflictsSeen()
        assertTrue(a.unseenConflicts().isEmpty())
    }

    @Test
    fun `message rows record conflicts too`() = runTest {
        val a = newDevice("device-a")
        // Local message authored at 3000.
        a.repo.applyChanges(
            SyncChanges(messages = listOf(SyncMessage(
                id = "m1", sessionId = "s1", role = "assistant", content = "mine",
                timestamp = 1L, parentId = null, isActivePath = 1L, updatedAt = 3000L,
                isDeleted = 0L, reasoningText = null, costEstimate = null
            ))),
            peerDeviceId = "seed"
        )
        a.repo.applyChanges(
            SyncChanges(messages = listOf(SyncMessage(
                id = "m1", sessionId = "s1", role = "assistant", content = "theirs",
                timestamp = 1L, parentId = null, isActivePath = 1L, updatedAt = 4000L,
                isDeleted = 0L, reasoningText = null, costEstimate = null
            ))),
            peerDeviceId = "device-b"
        )
        val conflicts = a.unseenConflicts()
        assertEquals(1, conflicts.size)
        assertEquals("Message", conflicts.first().tableName)
        assertEquals("m1", conflicts.first().rowId)
    }
}
