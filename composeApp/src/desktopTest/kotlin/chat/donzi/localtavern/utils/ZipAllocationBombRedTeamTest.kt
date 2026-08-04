package chat.donzi.localtavern.utils

import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

// Red-team probe: a ZIP archive whose central directory claims an
// uncompressed size of 0x7FFFFFFF (just under 2 GiB) for an entry whose
// compressed payload is 7 bytes. Zip.readArchive passes the claimed size
// straight into inflateDeflate, which allocates ByteArray(expectedSize)
// BEFORE the MAX_ENTRY_SIZE (64 MiB) cap is checked, so the import of a
// hostile archive exhausts memory instead of rejecting the entry.
class ZipAllocationBombRedTeamTest {

    private fun u16(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(), ((value ushr 8) and 0xFF).toByte()
    )

    private fun u32(value: Long): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(), ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(), ((value ushr 24) and 0xFF).toByte()
    )

    private fun craftBombArchive(): ByteArray {
        val name = "bomb.txt".encodeToByteArray()
        val payload = "hello".encodeToByteArray()

        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(payload)
        deflater.finish()
        val compressedBuf = ByteArray(64)
        val compressedLen = deflater.deflate(compressedBuf)
        deflater.end()
        val compressed = compressedBuf.copyOf(compressedLen)

        // One tiny (uncompressed) payload, but the central directory claims a
        // 2 GiB - 1 byte uncompressed size (fits in Int, exceeds the max
        // array size: the JVM rejects the allocation with OOM).
        val claimedUncompressed = 0x7FFFFFFFL

        // ---- local file header ----
        val local = ByteArray(30)
        u32(0x04034b50L).copyInto(local, 0)
        u16(20).copyInto(local, 4)          // version needed
        u16(0).copyInto(local, 6)           // flags
        u16(8).copyInto(local, 8)           // method: deflate
        u16(0).copyInto(local, 10)          // mod time
        u16(0x21).copyInto(local, 12)       // mod date
        u32(0L).copyInto(local, 14)         // crc (not verified by reader)
        u32(compressed.size.toLong()).copyInto(local, 18)  // compressed size
        u32(claimedUncompressed).copyInto(local, 22)       // uncompressed size
        u16(name.size).copyInto(local, 26)
        u16(0).copyInto(local, 28)          // extra length
        val localEntry = local + name + compressed

        // ---- central directory ----
        val central = ByteArray(46)
        u32(0x02014b50L).copyInto(central, 0) // signature
        u16(20).copyInto(central, 4)          // version made by
        u16(20).copyInto(central, 6)          // version needed
        u16(0).copyInto(central, 8)           // flags
        u16(8).copyInto(central, 10)          // method: deflate
        u16(0).copyInto(central, 12)          // mod time
        u16(0x21).copyInto(central, 14)       // mod date
        u32(0L).copyInto(central, 16)         // crc
        u32(compressed.size.toLong()).copyInto(central, 20) // compressed size
        u32(claimedUncompressed).copyInto(central, 24)      // uncompressed size
        u16(name.size).copyInto(central, 28)
        u16(0).copyInto(central, 30)          // extra length
        u16(0).copyInto(central, 32)          // comment length
        u16(0).copyInto(central, 34)          // disk number
        u16(0).copyInto(central, 36)          // internal attributes
        u32(0L).copyInto(central, 38)         // external attributes
        u32(0L).copyInto(central, 42)         // local header offset
        val centralEntry = central + name
        val centralOffset = localEntry.size

        // ---- end of central directory ----
        val eocd = ByteArray(22)
        u32(0x06054b50L).copyInto(eocd, 0)
        u16(0).copyInto(eocd, 4)              // disk number
        u16(0).copyInto(eocd, 6)              // disk with central dir
        u16(1).copyInto(eocd, 8)              // entries on this disk
        u16(1).copyInto(eocd, 10)             // total entries
        u32(centralEntry.size.toLong()).copyInto(eocd, 12) // central dir size
        u32(centralOffset.toLong()).copyInto(eocd, 16)     // central dir offset
        u16(0).copyInto(eocd, 20)             // comment length

        return localEntry + centralEntry + eocd
    }

    @Test
    fun misdeclaredUncompressedSize_isRejectedWithoutExhaustingMemory() {
        val archive = craftBombArchive()
        try {
            val entries = Zip.readArchive(archive)
            assertTrue(
                entries.isEmpty(),
                "an entry claiming 2 GiB uncompressed must be rejected by the 64 MiB cap"
            )
        } catch (e: OutOfMemoryError) {
            fail("Zip.readArchive exhausted memory on a misdeclared entry: ${e.message}")
        }
    }
}
