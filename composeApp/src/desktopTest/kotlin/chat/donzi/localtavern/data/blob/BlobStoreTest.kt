package chat.donzi.localtavern.data.blob

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Exercises the desktop blob store against a temporary user home (the store
// resolves its directory from ~/.localtavern/blobs, same as the database).
class BlobStoreTest {

    private fun tempDir(): File = Files.createTempDirectory("localtavern-blob-test").toFile()

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
    fun writeRead_delete_roundTrips() = withStore { store ->
        val bytes = ByteArray(64) { it.toByte() }
        store.write("a1b2", bytes)
        assertEquals(bytes.toList(), store.read("a1b2")!!.toList(), "Written bytes must read back unchanged")

        store.write("a1b2", ByteArray(4))
        assertEquals(4, store.read("a1b2")!!.size, "Overwrite must replace the stored value")

        store.delete("a1b2")
        assertNull(store.read("a1b2"), "Deleted keys must read back null")
        store.delete("a1b2") // deleting a missing key is a no-op
    }

    @Test
    fun read_missingKey_returnsNull() = withStore { store ->
        assertNull(store.read("deadbeef"))
    }

    @Test
    fun listKeys_returnsAllStored() = withStore { store ->
        store.write("k1", ByteArray(1))
        store.write("k2", ByteArray(2))
        assertEquals(setOf("k1", "k2"), store.listKeys())
        store.delete("k1")
        assertEquals(setOf("k2"), store.listKeys())
    }

    @Test
    fun gcBlobStore_keepsUsedKeysOnly() = withStore { store ->
        store.write("keep-a", ByteArray(1))
        store.write("keep-b", ByteArray(1))
        store.write("dead", ByteArray(1))

        gcBlobStore(store, usedKeys = setOf("keep-a", "keep-b"))

        assertEquals(setOf("keep-a", "keep-b"), store.listKeys(), "Unreferenced blobs must be deleted by GC")
    }

    @Test
    fun gcBlobStore_emptyUsedKeys_emptiesStore() = withStore { store ->
        store.write("x", ByteArray(1))
        gcBlobStore(store, usedKeys = emptySet())
        assertTrue(store.listKeys().isEmpty())
    }

    @Test
    fun invalidKeys_areRejectedWithoutFiles() = withStore { store ->
        store.write("", ByteArray(1))
        store.write("a".repeat(200), ByteArray(1))
        store.write("../escape", ByteArray(1))
        assertTrue(store.listKeys().isEmpty(), "Blank, oversized and path-traversal keys must be rejected")
    }
}
