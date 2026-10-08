package chat.donzi.localtavern.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

// Red-team probe: a corrupt blob whose image-size field is 0x7FFFFFFF. The
// guard in deserializeImageList is `size < 0 || offset + size > bytes.size`;
// 8 + 0x7FFFFFFF overflows Int and wraps negative, so the guard passes and
// the code allocates ByteArray(0x7FFFFFFF) (2 GiB - 1), which the JVM rejects
// with OutOfMemoryError. OutOfMemoryError is an Error, so the surrounding
// `catch (e: Exception)` cannot contain it — the "defensive" parser crashes
// the process on an 8-byte corrupt blob. Refs can arrive from the wire
// (legacy sync envelopes), so this is attacker-adjacent.
class ImageCodecAllocationRedTeamTest {

    @Test
    fun oversizedImageSizeField_isRejectedWithoutExhaustingMemory() {
        // blob layout: count(4, big-endian) = 1, then size(4) = 0x7FFFFFFF, no data.
        val blob = ByteArray(8)
        blob[0] = 0
        blob[1] = 0
        blob[2] = 0
        blob[3] = 1
        blob[4] = 0x7F.toByte()
        blob[5] = 0xFF.toByte()
        blob[6] = 0xFF.toByte()
        blob[7] = 0xFF.toByte()

        try {
            val images = deserializeImageList(blob)
            assertEquals(
                emptyList<ByteArray>(),
                images,
                "a corrupt blob must degrade to an empty list, never an allocation"
            )
        } catch (e: OutOfMemoryError) {
            fail("deserializeImageList exhausted memory on an 8-byte corrupt blob: ${e.message}")
        }
    }
}
