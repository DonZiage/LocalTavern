package chat.donzi.localtavern.utils

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageCodecTest {

    @Test
    fun serializeDeserialize_roundTripsMultipleImages() {
        val images = listOf(
            ByteArray(10) { it.toByte() },
            ByteArray(0),
            ByteArray(64) { (it * 3).toByte() }
        )
        val encoded = serializeImageList(images)
        val decoded = deserializeImageList(encoded)
        assertEquals(images.size, decoded.size)
        images.forEachIndexed { index, original ->
            assertContentEquals(original, decoded[index])
        }
    }

    @Test
    fun serialize_nullOrEmptyReturnsNull() {
        assertEquals(null, serializeImageList(null))
        assertEquals(null, serializeImageList(emptyList()))
    }

    @Test
    fun deserialize_nullOrGarbageReturnsEmpty() {
        assertTrue(deserializeImageList(null).isEmpty())
        assertTrue(deserializeImageList(ByteArray(0)).isEmpty())
        assertTrue(deserializeImageList(ByteArray(3) { 0x42.toByte() }).isEmpty())
    }

    @Test
    fun detectMimeType_recognizesPngJpegWebpGif() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0)
        assertEquals("image/png", detectMimeType(png))

        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertEquals("image/jpeg", detectMimeType(jpeg))

        val webp = byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(), 0, 0, 0, 0, 'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte())
        assertEquals("image/webp", detectMimeType(webp))

        val gif = byteArrayOf('G'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), '8'.code.toByte())
        assertEquals("image/gif", detectMimeType(gif))

        assertEquals("image/jpeg", detectMimeType(ByteArray(2)))
    }

    @Test
    fun detectMimeType_recognizesHeic() {
        val heic = ByteArray(16).also {
            "ftypheic".encodeToByteArray().copyInto(it, 4)
        }
        assertEquals("image/heic", detectMimeType(heic))

        val heif = ByteArray(16).also {
            "ftypmif1".encodeToByteArray().copyInto(it, 4)
        }
        assertEquals("image/heic", detectMimeType(heif))

        // A random ftyp brand is not an image and stays at the JPEG default.
        val mp4 = ByteArray(16).also {
            "ftypisom".encodeToByteArray().copyInto(it, 4)
        }
        assertEquals("image/jpeg", detectMimeType(mp4))
    }

    @Test
    fun deserialize_corruptCountDoesNotPreallocateHugeList() {
        // A blob claiming 0x7FFFFFFF images must not attempt a multi-GB
        // pre-allocation; it degrades to an empty list.
        val corrupt = ByteArray(8) { 0xFF.toByte() }
        assertTrue(deserializeImageList(corrupt).isEmpty())

        // A count above the 64-image cap must also be rejected.
        val overCap = ByteArray(4) { 0x41.toByte() }
        assertTrue(deserializeImageList(overCap).isEmpty())

        // Lengths running past the buffer end must also be rejected.
        val truncated = byteArrayOf(1, 0, 0, 0, 0, 0, 0, 64)
        assertTrue(deserializeImageList(truncated).isEmpty())
    }
}
