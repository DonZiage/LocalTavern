package chat.donzi.localtavern.utils

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.fail

// Red-team probe: a PNG chunk whose length field is 0x7FFFFFFF (the maximum
// positive Int). The bounds check `i + 8 + length + 4 > bytes.size` in
// PngParser.extractSillyTavernCard overflows Int and wraps negative, so the
// check passes even though the chunk can never exist in a 16-byte file. The
// walker then advances `i` past the buffer and dereferences it out of bounds.
// The parser promises graceful null for malformed chunks ("A malformed chunk
// ... must not abort the scan"); it must not throw.
class PngParserOverflowRedTeamTest {

    @Test
    fun hugeChunkLength_doesNotCrashTheParser() {
        // PNG signature (8 bytes) + one chunk header: length = 0x7FFFFFFF,
        // type = "tEXt". No chunk data follows.
        val bytes = ByteArray(16)
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        signature.copyInto(bytes, 0)
        bytes[8] = 0x7F.toByte()
        bytes[9] = 0xFF.toByte()
        bytes[10] = 0xFF.toByte()
        bytes[11] = 0xFF.toByte()
        bytes[12] = 't'.code.toByte()
        bytes[13] = 'E'.code.toByte()
        bytes[14] = 'X'.code.toByte()
        bytes[15] = 't'.code.toByte()

        try {
            val result = PngParser.extractSillyTavernCard(bytes)
            assertNull(result, "a corrupt chunk must yield null, not a card")
        } catch (e: IndexOutOfBoundsException) {
            fail("PngParser crashed with IndexOutOfBoundsException on a 16-byte PNG: ${e::class.simpleName}")
        }
    }
}
