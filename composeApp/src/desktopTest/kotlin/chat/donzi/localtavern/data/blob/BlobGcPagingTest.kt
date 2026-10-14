package chat.donzi.localtavern.data.blob

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.donzi.localtavern.data.database.LocalTavernDB
import chat.donzi.localtavern.data.database.LogicalClock
import chat.donzi.localtavern.data.sync.AVATAR_REF_THRESHOLD_BYTES
import chat.donzi.localtavern.domain.ImageRef
import chat.donzi.localtavern.utils.Hashing
import chat.donzi.localtavern.utils.serializeImageRefs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Blob-store garbage collection must scan the live set in bounded pages, no
// matter how many rows reference blobs.
//
// GC runs on every startup: it hashed every large inline avatar in one go
// (two unbounded SELECTs materializing every multi-hundred-KB avatar at
// once) plus every live ref list. A heavy library pins hundreds of MB on the
// heap before the first screen renders — an OOM kill at launch, every
// launch. Paging keeps each step flat while the collected key set stays
// exact.
class BlobGcPagingTest {

    private class InMemoryBlobStore : BlobStore {
        val map = LinkedHashMap<String, ByteArray>()
        override suspend fun write(key: String, bytes: ByteArray) { map[key] = bytes }
        override suspend fun read(key: String): ByteArray? = map[key]
        override suspend fun delete(key: String) { map.remove(key) }
        override suspend fun listKeys(): Set<String> = map.keys.toSet()
    }

    @Test
    fun gc_collectsExactKeySetAcrossManyPages() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalTavernDB.Schema.create(driver)
        val db = LocalTavernDB(driver)
        val queries = db.localTavernDBQueries
        val store = InMemoryBlobStore()

        // More live avatars than any single page, on both tables, each above
        // the inline threshold so the blob scan must see them.
        val liveKeys = LinkedHashSet<String>()
        repeat(130) { i ->
            val bytes = ByteArray(600 * 1024) { ((it + i) % 251).toByte() }
            val hash = Hashing.sha256Hex(bytes)
            store.write(hash, bytes)
            liveKeys.add(hash)
            queries.insertCharacter(
                id = "char-$i", name = "Character $i", description = null, personality = "",
                scenario = "", firstMes = null, mesExample = null, creatorNotes = null,
                altGreetings = null, avatarData = bytes, isAssistant = 0L, updatedAt = i.toLong(),
                isDeleted = 0L, systemPrompt = null, postHistoryInstructions = null, creator = null,
                characterVersion = null, tags = null, extensions = null, characterBook = null,
                syncSeq = i.toLong()
            )
        }
        repeat(130) { i ->
            val bytes = ByteArray(600 * 1024) { ((it + i * 7) % 251).toByte() }
            val hash = Hashing.sha256Hex(bytes)
            store.write(hash, bytes)
            liveKeys.add(hash)
            queries.insertPersona(
                id = "persona-$i", name = "Persona $i", description = null,
                avatarData = bytes, updatedAt = i.toLong(), isDeleted = 0L, syncSeq = (10_000 + i).toLong()
            )
        }
        // Live message-image refs plus orphan blobs that must be collected.
        val imageBytes = ByteArray(1024) { 7 }
        val imageHash = Hashing.sha256Hex(imageBytes)
        store.write(imageHash, imageBytes)
        liveKeys.add(imageHash)
        queries.insertMessageWithParent(
            id = "msg-1", sessionId = "session-1", role = "user", content = "hi",
            timestamp = 1L, parentId = null, isActivePath = 1L, updatedAt = 1L, isDeleted = 0L,
            imageRefs = serializeImageRefs(listOf(ImageRef(imageHash, imageBytes.size.toLong()))),
            reasoningText = null, costEstimate = null, syncSeq = 99_999L
        )
        val orphanKeys = List(10) { i ->
            val bytes = ByteArray(64) { i.toByte() }
            // Unique key per orphan without touching the live set.
            val unique = Hashing.sha256Hex(("orphan-$i").encodeToByteArray() + bytes)
            store.write(unique, bytes)
            unique
        }

        runBlobGc(db, store)

        liveKeys.forEach { key ->
            assertTrue(store.map.containsKey(key), "Live blob $key must survive GC")
        }
        orphanKeys.forEach { key ->
            assertFalse(store.map.containsKey(key), "Orphan blob must be collected")
        }
        assertEquals(liveKeys.size, store.map.size, "Nothing live may be lost, nothing dead kept")
    }

    @Test
    fun gc_pagedAvatarScansSeeEveryRow() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalTavernDB.Schema.create(driver)
        val db = LocalTavernDB(driver)
        val queries = db.localTavernDBQueries
        repeat(75) { i ->
            queries.insertPersona(
                id = "persona-$i", name = "Persona $i", description = null,
                avatarData = ByteArray(600 * 1024) { ((it + i) % 251).toByte() },
                updatedAt = i.toLong(), isDeleted = 0L, syncSeq = i.toLong()
            )
        }
        // The paged scan must cover the whole table, not just its first page.
        var seen = 0
        var offset = 0L
        while (true) {
            val page = queries.selectAllLivePersonaAvatarsPaged(AVATAR_REF_THRESHOLD_BYTES, 50L, offset).executeAsList()
            if (page.isEmpty()) break
            seen += page.size
            offset += page.size
        }
        assertEquals(75, seen, "Paged avatar scans must cover every live row")
    }
}
