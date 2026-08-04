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

    // The store only accepts canonical SHA-256 hex keys (that is what guards
    // it against path traversal), so tests use well-formed keys.
    private fun key(seed: Int): String = seed.toString(16).padStart(64, '0')

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
        val key = key(1)
        store.write(key, bytes)
        assertEquals(bytes.toList(), store.read(key)!!.toList(), "Written bytes must read back unchanged")

        store.write(key, ByteArray(4))
        assertEquals(4, store.read(key)!!.size, "Overwrite must replace the stored value")

        store.delete(key)
        assertNull(store.read(key), "Deleted keys must read back null")
        store.delete(key) // deleting a missing key is a no-op
    }

    @Test
    fun read_missingKey_returnsNull() = withStore { store ->
        assertNull(store.read(key(99)))
    }

    @Test
    fun listKeys_returnsAllStored() = withStore { store ->
        store.write(key(1), ByteArray(1))
        store.write(key(2), ByteArray(2))
        assertEquals(setOf(key(1), key(2)), store.listKeys())
        store.delete(key(1))
        assertEquals(setOf(key(2)), store.listKeys())
    }

    @Test
    fun gcBlobStore_keepsUsedKeysOnly() = withStore { store ->
        store.write(key(1), ByteArray(1))
        store.write(key(2), ByteArray(1))
        store.write(key(3), ByteArray(1))

        gcBlobStore(store, usedKeys = setOf(key(1), key(2)))

        assertEquals(setOf(key(1), key(2)), store.listKeys(), "Unreferenced blobs must be deleted by GC")
    }

    @Test
    fun gcBlobStore_emptyUsedKeys_emptiesStore() = withStore { store ->
        store.write(key(7), ByteArray(1))
        gcBlobStore(store, usedKeys = emptySet())
        assertTrue(store.listKeys().isEmpty())
    }

    @Test
    fun invalidKeys_areRejectedWithoutFiles() = withStore { store ->
        store.write("", ByteArray(1))
        store.write("a".repeat(200), ByteArray(1))
        store.write("../escape", ByteArray(1))
        store.write("../../etc/passwd", ByteArray(1))
        store.write("0".repeat(63) + "g", ByteArray(1))
        assertTrue(store.listKeys().isEmpty(), "Blank, oversized, non-hex and path-traversal keys must be rejected")
    }
}
