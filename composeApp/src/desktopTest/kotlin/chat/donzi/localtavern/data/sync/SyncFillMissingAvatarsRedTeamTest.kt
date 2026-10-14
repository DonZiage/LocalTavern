package chat.donzi.localtavern.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.donzi.localtavern.data.blob.BlobStore
import chat.donzi.localtavern.data.database.LocalTavernDB
import chat.donzi.localtavern.data.database.LogicalClock
import chat.donzi.localtavern.utils.Hashing
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Red team: filling missing out-of-band avatars must not buffer the whole
// library's bytes at once.
//
// A 128-character sync lands its rows with avatar REFS (avatars above the
// inline threshold ride out of band); the bytes arrive later and
// fillMissingAvatars resolves them. The implementation first read EVERY
// missing blob into one list and only then opened its single transaction:
// with N missing avatars of ~1 MB the call transiently pins N MB on top of
// the sync envelope decode and the character-list reload that every commit
// triggers — exactly how a big library kills a mobile app mid-sync at 70+
// received rows, and every retry (including auto-sync at launch) crashes the
// same way, leaving a database that is structurally valid but unusable
// without a data wipe.
class SyncFillMissingAvatarsRedTeamTest {

    private class TrackingBlobStore(
        private val db: LocalTavernDB,
        val blobs: Map<String, ByteArray>,
        var failOnReadIndex: Int? = null
    ) : BlobStore {
        // Rows still missing their avatar at the moment of each blob read.
        // Interleaved (bounded) filling shows this number dropping WHILE
        // reads are still happening; buffer-everything-first shows the full
        // count on every read.
        val remainingAtRead = ArrayList<Int>()
        private var reads = 0

        override suspend fun write(key: String, bytes: ByteArray) = Unit
        override suspend fun read(key: String): ByteArray? {
            remainingAtRead.add(
                db.localTavernDBQueries.selectCharactersWithMissingAvatar().executeAsList().size
            )
            if (failOnReadIndex != null && reads++ >= failOnReadIndex!!) {
                throw IOException("injected blob failure")
            }
            return blobs[key]
        }
        override suspend fun delete(key: String) = Unit
        override suspend fun listKeys(): Set<String> = blobs.keys.toSet()
    }

    private fun newDb(): LocalTavernDB {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalTavernDB.Schema.create(driver)
        return LocalTavernDB(driver)
    }

    private fun newRepository(db: LocalTavernDB): SyncRepository {
        val identity = SyncIdentity(
            deviceId = "device-a",
            deviceName = "device-a",
            privateKeyBase64 = kotlin.io.encoding.Base64.encode(ByteArray(32) { 1 }),
            publicKeyBase64 = kotlin.io.encoding.Base64.encode(ByteArray(32) { 2 })
        )
        return SyncRepository(db, identity, clock = LogicalClock(db))
    }

    private fun character(id: String, updatedAt: Long, sha: String, size: Long) = SyncCharacter(
        id = id, name = "Character $id", description = "description", personality = "personality",
        scenario = "scenario", firstMes = null, mesExample = null, creatorNotes = null,
        altGreetings = null, avatarData = null, avatarRef = SyncImageRef(sha, size),
        isAssistant = 0L, updatedAt = updatedAt, isDeleted = 0L, systemPrompt = null,
        postHistoryInstructions = null, creator = null, characterVersion = null,
        tags = null, extensions = null, characterBook = null
    )

    // Seeds [count] characters whose avatar bytes live ONLY in [storeBlobs]:
    // every row waits on its out-of-band ref, the shape of a 128-character
    // sync whose blobs have just arrived.
    private suspend fun seedMissing(db: LocalTavernDB, repo: SyncRepository, count: Int): Map<String, ByteArray> {
        val blobs = LinkedHashMap<String, ByteArray>()
        val changes = (1..count).map { i ->
            val bytes = ByteArray(32 * 1024) { (it % 251).toByte() }
            val sha = Hashing.sha256Hex(bytes)
            blobs[sha] = bytes
            character("char-$i", i.toLong(), sha, bytes.size.toLong())
        }
        repo.applyChanges(SyncChanges(characters = changes), peerDeviceId = "device-b")
        assertEquals(
            count,
            db.localTavernDBQueries.selectCharactersWithMissingAvatar().executeAsList().size,
            "All seeded rows must wait on their avatar ref"
        )
        return blobs
    }

    @Test
    fun fill_resolvesReadsAndWritesInBoundedBatches() = runTest {
        val db = newDb()
        val repo = newRepository(db)
        val blobs = seedMissing(db, repo, count = 50)
        val store = TrackingBlobStore(db, blobs)

        val filled = repo.fillMissingAvatars(store)

        assertEquals(50, filled, "Every missing avatar must be filled")
        assertTrue(
            db.localTavernDBQueries.selectCharactersWithMissingAvatar().executeAsList().isEmpty(),
            "No row may still wait on its avatar"
        )
        // Bounded buffering: by the time later blobs are read, earlier ones
        // must already be committed — the fill must never hold all 50 blobs
        // at once before its first write.
        val minRemaining = store.remainingAtRead.minOrNull() ?: 50
        assertTrue(
            minRemaining < 50,
            "Avatar bytes must be committed incrementally (bounded batches), " +
                "but every read still saw all 50 rows missing: the whole library " +
                "is buffered before the first write"
        )
    }

    @Test
    fun fillFailure_keepsTheCommittedPrefix() = runTest {
        val db = newDb()
        val repo = newRepository(db)
        val blobs = seedMissing(db, repo, count = 50)
        val store = TrackingBlobStore(db, blobs, failOnReadIndex = 25)

        val result = runCatching { repo.fillMissingAvatars(store) }
        assertTrue(result.isFailure, "The injected blob failure must propagate")

        // A kill mid-fill (OOM, power killer) must keep whatever batches
        // already committed: the next sync resumes the remainder instead of
        // restarting the whole library from zero.
        val stillMissing =
            db.localTavernDBQueries.selectCharactersWithMissingAvatar().executeAsList().size
        assertTrue(
            stillMissing < 50,
            "A mid-fill failure must keep its committed prefix, but all 50 rows are still missing"
        )
    }
}
