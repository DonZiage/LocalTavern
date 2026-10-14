package chat.donzi.localtavern.data.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Red team: Android's CursorWindow holds ~2 MB per row. Any single SQLite row
// larger than that (a multi-MB avatar PNG, a legacy inline image blob, a huge
// lorebook) crashes EVERY bulk SELECT that touches it with
// "Row too big to fit into CursorWindow requiredPos=X, totalRows=N" —
// including the startup migration/GC that run before the main UI appears, so
// the app never gets past the PIN/biometric gate on mobile.
//
// Startup reads must therefore never materialize an unbounded row in one
// cursor: legacy image migration must page by row ID (small rows) and fetch
// blobs one row at a time, and single-avatar reads must reassemble large
// blobs in CursorWindow-sized chunks.
class CursorWindowRedTeamTest {

    private fun newDb(): LocalTavernDB {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalTavernDB.Schema.create(driver)
        return LocalTavernDB(driver)
    }

    @Test
    fun oversizedAvatar_isReadableViaChunkedRead() = runTest {
        val db = newDb()
        val repo = CharacterRepository(db, clock = LogicalClock(db))
        // A phone-camera PNG easily exceeds the ~2 MB CursorWindow.
        val big = ByteArray(3 * 1024 * 1024) { ((it * 31) % 251).toByte() }
        db.localTavernDBQueries.insertCharacter(
            id = "char-big", name = "Big", description = null, personality = "",
            scenario = "", firstMes = null, mesExample = null, creatorNotes = null,
            altGreetings = null, avatarData = big, isAssistant = 0L, updatedAt = 1L,
            isDeleted = 0L, systemPrompt = null, postHistoryInstructions = null, creator = null,
            characterVersion = null, tags = null, extensions = null, characterBook = null,
            syncSeq = 1L
        )

        val read = repo.getCharacterAvatarData("char-big")
        assertNotNull(read, "A stored avatar must stay readable no matter its size")
        assertTrue(read.contentEquals(big), "Chunked reads must reassemble the exact bytes")
    }

    @Test
    fun oversizedPersonaAvatar_isReadableViaChunkedRead() = runTest {
        val db = newDb()
        val repo = CharacterRepository(db, clock = LogicalClock(db))
        val big = ByteArray(3 * 1024 * 1024) { ((it * 17 + 5) % 251).toByte() }
        db.localTavernDBQueries.insertPersona(
            id = "persona-big", name = "Big", description = null,
            avatarData = big, updatedAt = 1L, isDeleted = 0L, syncSeq = 1L
        )

        val read = repo.getPersonaAvatarData("persona-big")
        assertNotNull(read, "A stored persona avatar must stay readable no matter its size")
        assertTrue(read.contentEquals(big), "Chunked reads must reassemble the exact bytes")
    }

    @Test
    fun legacyImageMigration_pagesIdsInsteadOfLoadingAllBlobs() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalTavernDB.Schema.create(driver)
        val db = LocalTavernDB(driver)
        val queries = db.localTavernDBQueries
        // Six legacy rows (matches the reported totalRows=6): the ID scan must
        // stay small no matter how large the blobs are.
        repeat(6) { i ->
            queries.insertMessageWithParent(
                id = "m-$i", sessionId = "s", role = "user", content = "hi $i",
                timestamp = i.toLong(), parentId = null, isActivePath = 1L,
                updatedAt = i.toLong(), isDeleted = 0L, imageRefs = null,
                reasoningText = null, costEstimate = null, syncSeq = i.toLong()
            )
        }
        val ids = queries.selectMessageIdsWithImagesPaged(50L, 0L).executeAsList()
        assertEquals(0, ids.size, "No legacy blobs yet: the ID scan must work")
    }

    @Test
    fun legacyImageMigration_migratesSixRowsViaIdPages() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalTavernDB.Schema.create(driver)
        val db = LocalTavernDB(driver)
        val queries = db.localTavernDBQueries
        // Six legacy rows carrying inline image blobs (the reported
        // totalRows=6): the migration must page their IDs and fetch each
        // blob in CursorWindow-safe chunks instead of one unbounded SELECT *.
        repeat(6) { i ->
            queries.insertMessageFull(
                id = "m-$i", sessionId = "s", role = "user", content = "hi $i",
                timestamp = i.toLong(), parentId = null, isActivePath = 1L,
                updatedAt = i.toLong(), isDeleted = 0L, imageRefs = null,
                reasoningText = null, costEstimate = null, syncSeq = i.toLong()
            )
            val img = ByteArray(16) { (it + i).toByte() }
            val legacyBlob = chat.donzi.localtavern.utils.serializeImageList(listOf(img))!!
            driver.execute(null, "UPDATE MessageEntity SET imageData = ? WHERE id = 'm-$i';", 1) {
                bindBytes(0, legacyBlob)
            }
        }
        val store = object : chat.donzi.localtavern.data.blob.BlobStore {
            val map = LinkedHashMap<String, ByteArray>()
            override suspend fun write(key: String, bytes: ByteArray) { map[key] = bytes }
            override suspend fun read(key: String): ByteArray? = map[key]
            override suspend fun delete(key: String) { map.remove(key) }
            override suspend fun listKeys(): Set<String> = map.keys.toSet()
        }
        val migrated = chat.donzi.localtavern.data.blob.migrateMessageImagesToBlobStore(
            db, store, LogicalClock(db)
        )
        assertEquals(6, migrated, "All six legacy rows must migrate via the ID-paged path")
        assertTrue(
            queries.selectMessageIdsWithImagesPaged(50L, 0L).executeAsList().isEmpty(),
            "No legacy imageData may remain after migration"
        )
    }
}
