package chat.donzi.localtavern.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.donzi.localtavern.data.database.LocalTavernDB
import chat.donzi.localtavern.data.database.LogicalClock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Exercises the bounded delta batching that keeps large libraries (hundreds
// of characters with avatars) from riding in one monolithic envelope: the
// batch cut must be exact (no gaps, no duplicates), atomic per syncSeq group
// (rows sharing a sequence can never be split across batches), and global
// across all six synced tables.
class SyncBatchingTest {

    private class Device(deviceId: String) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { LocalTavernDB.Schema.create(it) }
        val db = LocalTavernDB(driver)
        val repo = SyncRepository(
            db,
            SyncIdentity(
                deviceId = deviceId,
                deviceName = deviceId,
                privateKeyBase64 = kotlin.io.encoding.Base64.encode(ByteArray(32) { 1 }),
                publicKeyBase64 = kotlin.io.encoding.Base64.encode(ByteArray(32) { 2 })
            ),
            clock = LogicalClock(db)
        )
    }

    private fun persona(id: String, name: String, updatedAt: Long) = SyncPersona(
        id = id, name = name, description = null, avatarData = null,
        updatedAt = updatedAt, isDeleted = 0L
    )

    private fun character(id: String, name: String, updatedAt: Long, avatarSize: Int = 0) = SyncCharacter(
        id = id, name = name, description = "description", personality = "personality",
        scenario = "scenario", firstMes = null, mesExample = null, creatorNotes = null,
        altGreetings = null, avatarData = if (avatarSize > 0) ByteArray(avatarSize) { 7 } else null,
        isAssistant = 0L, updatedAt = updatedAt, isDeleted = 0L, systemPrompt = null,
        postHistoryInstructions = null, creator = null, characterVersion = null,
        tags = null, extensions = null, characterBook = null
    )

    private suspend fun seed(device: Device, rows: List<SyncChanges>) {
        rows.forEach { device.repo.applyChanges(it, peerDeviceId = "seed") }
    }

    /** Drains a device's delta with [repo]'s batches, replicating the exchange loop. */
    private suspend fun drain(device: Device, budget: Long): List<SyncChanges> {
        val batches = mutableListOf<SyncChanges>()
        var cursor = 0L
        while (true) {
            val batch = device.repo.collectDeltaBatched(cursor, budget)
            batches += batch.changes
            if (batch.changes.isEmpty) break
            cursor = batch.changes.maxSyncSeq + 1
            if (!batch.hasMore) {
                // After the last row is shipped, a further query must return
                // nothing: the cut is exact.
                assertTrue(device.repo.collectDelta(cursor).isEmpty, "No rows may remain after the final batch")
                break
            }
        }
        return batches
    }

    @Test
    fun batchesTileTheDeltaWithoutGapsOrDuplicates() = runTest {
        val device = Device("device-a")
        seed(device, (1..50).map { SyncChanges(personas = listOf(persona("p$it", "Persona $it", it * 100L))) })

        val budget = 3_000L // ~5 personas per batch given the fixed row overhead
        val batches = drain(device, budget)

        assertTrue(batches.size >= 2, "A small budget must split the delta into multiple batches, got ${batches.size}")
        val ids = batches.flatMap { it.personas }.map { it.id }
        assertEquals((1..50).map { "p$it" }, ids, "Union of batches must equal the delta exactly, in syncSeq order")
        assertEquals(50, ids.size, "No duplicates")

        batches.zipWithNext().forEach { (first, second) ->
            assertTrue(
                second.personas.first().syncSeq > first.maxSyncSeq,
                "Each batch must resume exactly past the previous cut (no gaps, no overlap)"
            )
        }
        assertFalse(batches.last().personas.isEmpty())
    }

    @Test
    fun rowsSharingASequenceShipTogether() = runTest {
        val device = Device("device-a")
        // Seed rows normally so they get distinct sequences, then force two
        // personas to share one sequence (as SQLite's serialized stamping can
        // produce on a torn read-modify-write).
        seed(device, listOf(
            SyncChanges(personas = listOf(persona("p1", "One", 100L))),
            SyncChanges(personas = listOf(persona("p2", "Two", 200L))),
            SyncChanges(personas = listOf(persona("p3", "Three", 300L)))
        ))
        device.db.localTavernDBQueries.upsertPersonaFull(
            name = "Two", description = null, avatarData = null, updatedAt = 200L,
            isDeleted = 0L, syncSeq = 3L, id = "p2"
        )
        device.db.localTavernDBQueries.upsertPersonaFull(
            name = "Three", description = null, avatarData = null, updatedAt = 300L,
            isDeleted = 0L, syncSeq = 3L, id = "p3"
        )

        // Budget smaller than a single row: the first group (seq 1) still
        // ships, and the equal-seq pair (seq 3) must ship together in one
        // batch — never split — or the cursor would strand one of them.
        val batch = device.repo.collectDeltaBatched(0L, budgetBytes = 1L)
        assertTrue(batch.hasMore, "Rows above the cut must report hasMore")
        assertEquals(listOf("p1"), batch.changes.personas.map { it.id })

        val second = device.repo.collectDeltaBatched(batch.changes.maxSyncSeq + 1, budgetBytes = 1L)
        assertEquals(setOf("p2", "p3"), second.changes.personas.map { it.id }.toSet(), "Equal-seq rows must never be split across batches")
        assertFalse(second.hasMore, "The equal-seq pair is the last group: nothing remains above the cut")
    }

    @Test
    fun cutIsGlobalAcrossTables() = runTest {
        val device = Device("device-a")
        seed(device, (1..20).map {
            SyncChanges(
                personas = listOf(persona("p$it", "Persona $it", it * 100L)),
                characters = listOf(character("c$it", "Character $it", it * 100L + 1))
            )
        })
        // 20 personas + 20 characters interleaved; a budget of ~4 rows per
        // batch must cut in GLOBAL sequence order, not per table.
        val batches = drain(device, budget = 4_000L)

        assertTrue(batches.size >= 3, "Expected multiple cross-table batches, got ${batches.size}")
        val allRows = batches.flatMap { it.personas.map { p -> "p" to p.id } + it.characters.map { c -> "c" to c.id } }
        val expected = (1..20).flatMap { listOf("p$it" to "p$it", "c$it" to "c$it") }.map { (k, v) -> k to v }
        assertEquals(expected.map { it.second }.toSet(), allRows.map { it.second }.toSet(), "Every row must arrive exactly once")
        assertEquals(40, allRows.size, "No duplicates across batches")
    }

    @Test
    fun oversizedRowStillShipsAsItsOwnAtomicGroup() = runTest {
        val device = Device("device-a")
        seed(device, listOf(
            SyncChanges(personas = listOf(persona("p1", "Small", 100L))),
            SyncChanges(characters = listOf(character("c1", "Huge", 200L, avatarSize = 5 * 1024 * 1024))),
            SyncChanges(personas = listOf(persona("p2", "Tail", 300L)))
        ))

        // A single 5MB character dwarfs the 100KB budget. The cut lands
        // BEFORE a group that would exceed the budget, so the oversized row
        // ships as its own batch (it is the atomic minimum of its batch),
        // and everything before and after it tiles normally.
        val first = device.repo.collectDeltaBatched(0L, budgetBytes = 100L * 1024)
        assertEquals(listOf("p1"), first.changes.personas.map { it.id })
        assertTrue(first.changes.characters.isEmpty(), "The oversized row must wait for its own batch")
        assertTrue(first.hasMore, "Rows above the cut must remain")

        val second = device.repo.collectDeltaBatched(first.changes.maxSyncSeq + 1, budgetBytes = 100L * 1024)
        assertEquals(listOf("c1"), second.changes.characters.map { it.id }, "The oversized row must not be dropped")
        assertTrue(second.hasMore, "The tail row must still be pending")

        val third = device.repo.collectDeltaBatched(second.changes.maxSyncSeq + 1, budgetBytes = 100L * 1024)
        assertEquals(listOf("p2"), third.changes.personas.map { it.id })
        assertFalse(third.hasMore)
    }

    @Test
    fun drainReassemblesLibraryOfHundredsOfCharacters() = runTest {
        val device = Device("device-a")
        // ~250 characters with 32KB avatars (~8MB) + 100 sessions: the shape
        // of the reported "hundreds of characters" failure.
        val avatar = ByteArray(32 * 1024) { (it % 251).toByte() }
        seed(device, (1..250).map { i ->
            SyncChanges(
                characters = listOf(SyncCharacter(
                    id = "char-$i", name = "Character $i", description = null,
                    personality = "p", scenario = "s", firstMes = null, mesExample = null,
                    creatorNotes = null, altGreetings = null, avatarData = avatar,
                    isAssistant = 0L, updatedAt = i.toLong(), isDeleted = 0L, systemPrompt = null,
                    postHistoryInstructions = null, creator = null, characterVersion = null,
                    tags = null, extensions = null, characterBook = null
                ))
            )
        })

        val batches = drain(device, budget = DELTA_BUDGET_BYTES)
        assertTrue(batches.size >= 2, "A multi-MB library must span multiple bounded batches, got ${batches.size}")

        val ids = batches.flatMap { it.characters }.map { it.id }
        assertEquals(250, ids.size, "Every character must arrive exactly once")
        assertEquals((1..250).map { "char-$it" }, ids, "Batches must tile the library in order")
        val finalRow = device.db.localTavernDBQueries.selectCharacterByIdAny("char-250").executeAsOne()!!
        assertEquals(avatar.toList(), finalRow.avatarData!!.toList(), "Avatars must survive the trip")
    }

    @Test
    fun tombstonesFlowThroughBatches() = runTest {
        val device = Device("device-a")
        seed(device, (1..30).map { SyncChanges(personas = listOf(persona("p$it", "Persona $it", it * 100L))) })
        // Delete two personas AFTER the initial seed: the tombstones get
        // fresh sequences and must arrive in later batches.
        seed(device, listOf(
            SyncChanges(personas = listOf(SyncPersona(id = "p10", name = "Persona 10", description = null, avatarData = null, updatedAt = 10_000L, isDeleted = 1L))),
            SyncChanges(personas = listOf(SyncPersona(id = "p20", name = "Persona 20", description = null, avatarData = null, updatedAt = 20_000L, isDeleted = 1L)))
        ))

        val batches = drain(device, budget = 3_000L)
        val tombstoneBatches = batches.filter { b -> b.personas.any { it.isDeleted == 1L } }
        assertEquals(2, tombstoneBatches.sumOf { it.personas.count { p -> p.isDeleted == 1L } }, "Both tombstones must arrive")

        val all = batches.flatMap { it.personas }
        assertEquals(30, all.size, "Tombstones must not duplicate their live rows")
        val tombstoneIds = all.filter { it.isDeleted == 1L }.map { it.id }
        assertTrue(tombstoneIds.containsAll(listOf("p10", "p20")))
    }

    @Test
    fun modernSendsCarryRefsButNotInlineImageBytes() = runTest {
        val device = Device("device-a")
        val store = object : chat.donzi.localtavern.data.blob.BlobStore {
            private val map = mutableMapOf<String, ByteArray>()
            override suspend fun write(key: String, bytes: ByteArray) { map[key] = bytes }
            override suspend fun read(key: String): ByteArray? = map[key]
            override suspend fun delete(key: String) { map.remove(key) }
            override suspend fun listKeys(): Set<String> = map.keys.toSet()
        }
        val img = ByteArray(200_000) { (it % 251).toByte() }
        val hash = chat.donzi.localtavern.utils.Hashing.sha256Hex(img)
        store.write(hash, img)

        val repo = SyncRepository(device.db, SyncIdentity(
            deviceId = "device-a", deviceName = "device-a",
            privateKeyBase64 = kotlin.io.encoding.Base64.encode(ByteArray(32) { 1 }),
            publicKeyBase64 = kotlin.io.encoding.Base64.encode(ByteArray(32) { 2 })
        ), blobStore = store)
        repo.applyChanges(
            SyncChanges(messages = listOf(SyncMessage(
                id = "m1", sessionId = "s1", role = "assistant", content = "Hello",
                timestamp = 1L, parentId = null, isActivePath = 1L, updatedAt = 1000L,
                isDeleted = 0L, imageRefs = listOf(SyncImageRef(hash, img.size.toLong())),
                reasoningText = null, costEstimate = null
            ))),
            peerDeviceId = "seed"
        )

        val delta = repo.collectDelta(0L)
        val wired = delta.messages.single()
        assertNull(wired.imageData, "Modern sends must not inline image bytes into the envelope")
        assertEquals(1, wired.imageRefs.size)
        assertEquals(hash, wired.imageRefs.single().sha256)
    }

    private fun assertNull(value: ByteArray?, message: String) {
        if (value != null) throw AssertionError(message)
    }

    private fun assertFalse(value: Boolean) {
        if (value) throw AssertionError("Expected false")
    }
}
