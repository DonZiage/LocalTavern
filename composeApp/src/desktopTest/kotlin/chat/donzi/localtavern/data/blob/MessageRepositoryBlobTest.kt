package chat.donzi.localtavern.data.blob

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.donzi.localtavern.data.database.LocalTavernDB
import chat.donzi.localtavern.data.database.LogicalClock
import chat.donzi.localtavern.data.database.MessageRepository
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
class MessageRepositoryBlobTest {

    private fun tempDir(): File = Files.createTempDirectory("localtavern-blob-repo-test").toFile()

    private class Repos(val session: SessionRepository, val message: MessageRepository)

    private fun newRepos(store: BlobStore? = null): Repos {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalTavernDB.Schema.create(driver)
        val db = LocalTavernDB(driver)
        val sessionRepository = SessionRepository(db, clock = LogicalClock(db))
        return Repos(sessionRepository, MessageRepository(db, clock = LogicalClock(db), blobStore = store, sessionRepository = sessionRepository))
    }

    private fun newReposWithExtraction(): Triple<JdbcSqliteDriver, LocalTavernDB, Repos> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalTavernDB.Schema.create(driver)
        val db = LocalTavernDB(driver)
        val sessionRepository = SessionRepository(db, clock = LogicalClock(db))
        return Triple(driver, db, Repos(sessionRepository, MessageRepository(db, clock = LogicalClock(db), sessionRepository = sessionRepository)))
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
        val repos = newRepos(store)
        val imgA = ByteArray(10) { 1 }
        val imgB = ByteArray(20) { 2 }

        val sessionId = repos.session.getOrCreateSession("char-1", "persona-1")
        repos.message.insertMessage(sessionId, "user", "Hi", null, imageDataList = listOf(imgA, imgB))

        val messages = repos.message.getMessagesForSession(sessionId)
        val userMsg = messages.single()
        assertEquals(2, userMsg.images.size, "Images must be hydrated from the store")
        assertEquals(imgA.toList(), userMsg.images[0].toList())
        assertEquals(imgB.toList(), userMsg.images[1].toList())
        assertEquals(2, userMsg.imageRefs.size)
        assertEquals(2, store.listKeys().size, "Each distinct image is stored once")
    }

    @Test
    fun identicalImages_areDeduplicatedInStore() = withStore { store ->
        val repos = newRepos(store)
        val img = ByteArray(8) { 7 }

        val sessionId = repos.session.getOrCreateSession("char-1", "persona-1")
        repos.message.insertMessage(sessionId, "user", "One", null, imageDataList = listOf(img))
        repos.message.insertMessage(sessionId, "user", "Two", null, imageDataList = listOf(img))

        assertEquals(1, store.listKeys().size, "Content-addressed storage must deduplicate identical images")
        val messages = repos.message.getMessagesForSession(sessionId)
        messages.forEach { assertEquals(img.toList(), it.images.single().toList()) }
    }

    @Test
    fun updateAndAppend_replaceAndExtendRefs() = withStore { store ->
        val repos = newRepos(store)
        val imgOld = ByteArray(4) { 1 }
        val imgNew = ByteArray(6) { 2 }

        val sessionId = repos.session.getOrCreateSession("char-1", "persona-1")
        val msgId = repos.message.insertMessage(sessionId, "user", "Hi", null, imageDataList = listOf(imgOld))

        repos.message.updateMessageImage(msgId, listOf(imgNew))
        var msg = repos.message.getMessagesForSession(sessionId).single()
        assertEquals(listOf(imgNew.toList()), msg.images.map { it.toList() }, "Update must replace the images")

        repos.message.appendImagesToMessage(sessionId, msgId, listOf(imgOld))
        msg = repos.message.getMessagesForSession(sessionId).single()
        assertEquals(listOf(imgNew.toList(), imgOld.toList()), msg.images.map { it.toList() }, "Append must extend the images")
    }

    @Test
    fun branchSession_reusesContentAddressedBlobs() = withStore { store ->
        val repos = newRepos(store)
        val img = ByteArray(5) { 9 }
        val sessionId = repos.session.getOrCreateSession("char-1", "persona-1")
        val msgId = repos.message.insertMessage(sessionId, "user", "Hi", null, imageDataList = listOf(img))

        val branchId = repos.session.branchSession(sessionId, msgId, repos.message.getMessagesForSession(sessionId), "Branch")
        val branched = repos.message.getMessagesForSession(branchId).single()
        assertEquals(img.toList(), branched.images.single().toList(), "Branch must share the stored blobs")
        assertEquals(1, store.listKeys().size, "Branching must not duplicate blob files")
    }

    @Test
    fun missingBlob_yieldsEmptyImagesButKeepsRefs() = withStore { store ->
        val repos = newRepos(store)
        val img = ByteArray(5) { 3 }
        val sessionId = repos.session.getOrCreateSession("char-1", "persona-1")
        val msgId = repos.message.insertMessage(sessionId, "user", "Hi", null, imageDataList = listOf(img))

        val hash = repos.message.getMessagesForSession(sessionId).single().imageRefs.single().sha256
        store.delete(hash) // simulate a blob that never arrived from a peer

        val msg = repos.message.getMessagesForSession(sessionId).single()
        assertTrue(msg.images.isEmpty(), "Missing blobs must hydrate to no bytes")
        assertEquals(1, msg.imageRefs.size, "The refs must survive so the UI can show a placeholder")
    }

    @Test
    fun runBlobGc_removesFilesOfDeletedMessages() = withStore { store ->
        val repos = newRepos(store)
        val img = ByteArray(5) { 4 }
        val sessionId = repos.session.getOrCreateSession("char-1", "persona-1")
        val msgId = repos.message.insertMessage(sessionId, "user", "Hi", null, imageDataList = listOf(img))

        // Deleting a message tombstones the row; the blob is only dropped by GC.
        repos.message.deleteMessage(msgId)
        assertEquals(1, store.listKeys().size, "Blob survives the tombstone")
        runBlobGc(repos.message.database, store)
        assertTrue(store.listKeys().isEmpty(), "GC must delete blobs of tombstoned/missing rows")
    }

    @Test
    fun migration_offloadsLegacyImageDataIntoStore() = withStore { store ->
        val (driver, db, _) = newReposWithExtraction()
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
