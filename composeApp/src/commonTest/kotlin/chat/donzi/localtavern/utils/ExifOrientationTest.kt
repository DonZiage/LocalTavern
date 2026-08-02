package chat.donzi.localtavern.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class ExifOrientationTest {

    private fun buildJpegWithExif(orientation: Int, littleEndian: Boolean = true): ByteArray {
        val tiff = buildTiff(orientation, littleEndian)
        val exifHeader = "Exif\u0000\u0000".encodeToByteArray()
        val app1Payload = exifHeader + tiff
        val app1Length = 2 + app1Payload.size
        val app1 = byteArrayOf(
            0xFF.toByte(), 0xE1.toByte(),
            (app1Length shr 8).toByte(), (app1Length and 0xFF).toByte()
        ) + app1Payload
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app1 + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }

    private fun buildTiff(orientation: Int, littleEndian: Boolean): ByteArray {
        val out = ArrayList<Byte>()
        fun u16(value: Int) {
            if (littleEndian) {
                out.add((value and 0xFF).toByte())
                out.add(((value shr 8) and 0xFF).toByte())
            } else {
                out.add(((value shr 8) and 0xFF).toByte())
                out.add((value and 0xFF).toByte())
            }
        }
        fun u32(value: Int) {
            for (i in 0 until 4) {
                val shift = if (littleEndian) i * 8 else (3 - i) * 8
                out.add(((value shr shift) and 0xFF).toByte())
            }
        }
        out.add(if (littleEndian) 'I'.code.toByte() else 'M'.code.toByte())
        out.add(if (littleEndian) 'I'.code.toByte() else 'M'.code.toByte())
        u16(0x2A)
        u32(8)
        u16(1)
        u16(0x0112)
        u16(3)
        u32(1)
        u16(orientation)
        u16(0)
        u32(0)
        return out.toByteArray()
    }

    @Test
    fun parsesLittleEndianOrientation() {
        for (orientation in 2..8) {
            assertEquals(orientation, readJpegExifOrientation(buildJpegWithExif(orientation, littleEndian = true)),
                "Little-endian orientation $orientation")
        }
    }

    @Test
    fun parsesBigEndianOrientation() {
        for (orientation in 2..8) {
            assertEquals(orientation, readJpegExifOrientation(buildJpegWithExif(orientation, littleEndian = false)),
                "Big-endian orientation $orientation")
        }
    }

    @Test
    fun orientationOneIsDefault() {
        assertEquals(1, readJpegExifOrientation(buildJpegWithExif(1, littleEndian = true)))
        assertEquals(1, readJpegExifOrientation(buildJpegWithExif(1, littleEndian = false)))
    }

    @Test
    fun nonJpegReturnsOne() {
        assertEquals(1, readJpegExifOrientation("not an image".encodeToByteArray()))
        assertEquals(1, readJpegExifOrientation(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
        assertEquals(1, readJpegExifOrientation(byteArrayOf()))
    }

    @Test
    fun plainJpegWithoutExifReturnsOne() {
        val plain = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        assertEquals(1, readJpegExifOrientation(plain))
    }

    @Test
    fun truncatedExifReturnsOne() {
        val full = buildJpegWithExif(6)
        val truncated = full.copyOfRange(0, full.size - 4)
        assertEquals(1, readJpegExifOrientation(truncated))
    }

    @Test
    fun exifAfterOtherMarkersIsFound() {
        val comment = byteArrayOf(
            0xFF.toByte(), 0xFE.toByte(), 0x00, 0x0A, 'h'.code.toByte(), 'e'.code.toByte(),
            'l'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte(), '!'.code.toByte(), 0x00, 0x00
        )
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + comment + buildJpegWithExif(6)
        assertEquals(6, readJpegExifOrientation(jpeg))
    }
}
