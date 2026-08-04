package chat.donzi.localtavern.data.blob

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.donzi.localtavern.data.database.LocalTavernDB
import chat.donzi.localtavern.data.database.LogicalClock
import chat.donzi.localtavern.data.database.SessionRepository
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Message images round-trip through the repository into the content-addressed
// blob store (never into the database), with hydration, dedup and GC.
class SessionRepositoryBlobTest {

    private fun tempDir(): File = Files.createTempDirectory("localtavern-blob-repo-test").toFile()

    private fun newRepo(store: BlobStore): SessionRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalTavernDB.Schema.create(driver)
        val db = LocalTavernDB(driver)
        return SessionRepository(db, clock = LogicalClock(db), blobStore = store)
    }

    private fun newRepoWithExtraction(): Triple<JdbcSqliteDriver, LocalTavernDB, SessionRepository> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalTavernDB.Schema.create(driver)
        val db = LocalTavernDB(driver)
        return Triple(driver, db, SessionRepository(db, clock = LogicalClock(db)))
    }

    private fun withStore(block: suspend (BlobStore) -> Unit) = runTest {
        val tempDir = tempDir()
        try {
            val oldUserHome = System.getProperty("user.home")
            System.setProperty("user.home", tempDir.absolutePath)
            try {
                block(createBlobStore())
            } finally {
                System.setProperty("user.home", oldUserHome)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun insertAndRead_hydratesImagesFromStore() = withStore { store ->
        val repo = newRepo(store)
        val imgA = ByteArray(10) { 1 }
        val imgB = ByteArray(20) { 2 }

        val sessionId = repo.getOrCreateSession("char-1", "persona-1")
        repo.insertMessage(sessionId, "user", "Hi", null, imageDataList = listOf(imgA, imgB))

        val messages = repo.getMessagesForSession(sessionId)
        val userMsg = messages.single()
        assertEquals(2, userMsg.images.size, "Images must be hydrated from the store")
        assertEquals(imgA.toList(), userMsg.images[0].toList())
        assertEquals(imgB.toList(), userMsg.images[1].toList())
        assertEquals(2, userMsg.imageRefs.size)
        assertEquals(2, store.listKeys().size, "Each distinct image is stored once")
    }

    @Test
    fun identicalImages_areDeduplicatedInStore() = withStore { store ->
        val repo = newRepo(store)
        val img = ByteArray(8) { 7 }

        val sessionId = repo.getOrCreateSession("char-1", "persona-1")
        repo.insertMessage(sessionId, "user", "One", null, imageDataList = listOf(img))
        repo.insertMessage(sessionId, "user", "Two", null, imageDataList = listOf(img))

        assertEquals(1, store.listKeys().size, "Content-addressed storage must deduplicate identical images")
        val messages = repo.getMessagesForSession(sessionId)
        messages.forEach { assertEquals(img.toList(), it.images.single().toList()) }
    }

    @Test
    fun updateAndAppend_replaceAndExtendRefs() = withStore { store ->
        val repo = newRepo(store)
        val imgOld = ByteArray(4) { 1 }
        val imgNew = ByteArray(6) { 2 }

        val sessionId = repo.getOrCreateSession("char-1", "persona-1")
        val msgId = repo.insertMessage(sessionId, "user", "Hi", null, imageDataList = listOf(imgOld))

        repo.updateMessageImage(msgId, listOf(imgNew))
        var msg = repo.getMessagesForSession(sessionId).single()
        assertEquals(listOf(imgNew.toList()), msg.images.map { it.toList() }, "Update must replace the images")

        repo.appendImagesToMessage(sessionId, msgId, listOf(imgOld))
        msg = repo.getMessagesForSession(sessionId).single()
        assertEquals(listOf(imgNew.toList(), imgOld.toList()), msg.images.map { it.toList() }, "Append must extend the images")
    }

    @Test
    fun branchSession_reusesContentAddressedBlobs() = withStore { store ->
        val repo = newRepo(store)
        val img = ByteArray(5) { 9 }
        val sessionId = repo.getOrCreateSession("char-1", "persona-1")
        val msgId = repo.insertMessage(sessionId, "user", "Hi", null, imageDataList = listOf(img))

        val branchId = repo.branchSession(sessionId, msgId, repo.getMessagesForSession(sessionId), "Branch")
        val branched = repo.getMessagesForSession(branchId).single()
        assertEquals(img.toList(), branched.images.single().toList(), "Branch must share the stored blobs")
        assertEquals(1, store.listKeys().size, "Branching must not duplicate blob files")
    }

    @Test
    fun missingBlob_yieldsEmptyImagesButKeepsRefs() = withStore { store ->
        val repo = newRepo(store)
        val img = ByteArray(5) { 3 }
        val sessionId = repo.getOrCreateSession("char-1", "persona-1")
        val msgId = repo.insertMessage(sessionId, "user", "Hi", null, imageDataList = listOf(img))

        val hash = repo.getMessagesForSession(sessionId).single().imageRefs.single().sha256
        store.delete(hash) // simulate a blob that never arrived from a peer

        val msg = repo.getMessagesForSession(sessionId).single()
        assertTrue(msg.images.isEmpty(), "Missing blobs must hydrate to no bytes")
        assertEquals(1, msg.imageRefs.size, "The refs must survive so the UI can show a placeholder")
    }

    @Test
    fun runBlobGc_removesFilesOfDeletedMessages() = withStore { store ->
        val repo = newRepo(store)
        val img = ByteArray(5) { 4 }
        val sessionId = repo.getOrCreateSession("char-1", "persona-1")
        val msgId = repo.insertMessage(sessionId, "user", "Hi", null, imageDataList = listOf(img))

        // Deleting a message tombstones the row; the blob is only dropped by GC.
        repo.deleteMessage(msgId)
        assertEquals(1, store.listKeys().size, "Blob survives the tombstone")
        runBlobGc(repo.database, store)
        assertTrue(store.listKeys().isEmpty(), "GC must delete blobs of tombstoned/missing rows")
    }

    @Test
    fun migration_offloadsLegacyImageDataIntoStore() = withStore { store ->
        val (driver, db, _) = newRepoWithExtraction()
        // Simulate a pre-blob-store row: inline serialized images in imageData.
        val imgA = ByteArray(7) { 5 }
        val imgB = ByteArray(9) { 6 }
        val legacyBlob = chat.donzi.localtavern.utils.serializeImageList(listOf(imgA, imgB))!!
        db.localTavernDBQueries.insertMessageFull(
            id = "m1", sessionId = "s1", role = "assistant", content = "Hello", timestamp = 1L,
            parentId = null, isActivePath = 1L, updatedAt = 1000L, isDeleted = 0L,
            imageRefs = null, reasoningText = null, costEstimate = null, syncSeq = 1L
        )
        driver.execute(null, "UPDATE MessageEntity SET imageData = ? WHERE id = 'm1';", 1) {
            bindBytes(0, legacyBlob)
        }

        val clock = LogicalClock(db)
        val migrated = migrateMessageImagesToBlobStore(db, store, clock)

        assertEquals(1, migrated, "Exactly one row must be migrated")
        val row = db.localTavernDBQueries.selectMessageById("m1").executeAsOneOrNull()
        assertNotNull(row)
        assertEquals(2, chat.donzi.localtavern.utils.deserializeImageRefs(row.imageRefs).size, "Refs must replace the bytes")
        assertTrue(db.localTavernDBQueries.selectMessagesWithImages().executeAsList().isEmpty(), "imageData must be cleared")

        val storeKeys = store.listKeys()
        assertEquals(2, storeKeys.size, "Both images must now live in the blob store")

        // Idempotent: a second run migrates nothing.
        val again = migrateMessageImagesToBlobStore(db, store, clock)
        assertEquals(0, again, "The migration must be idempotent")
    }
}
