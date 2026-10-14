package chat.donzi.localtavern.utils

// Reads the EXIF Orientation tag (0x0112) from a JPEG's APP1 segment so
// platform decoders that do not apply it (Android BitmapFactory, Skia on
// desktop) can rotate the pixels themselves. Returns 1 (no transform) for
// any input that is not a JPEG with a parseable orientation.
internal fun readJpegExifOrientation(bytes: ByteArray): Int {
    if (bytes.size < 4) return 1
    if (bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return 1
    var offset = 2
    while (offset + 4 <= bytes.size) {
        if (bytes[offset] != 0xFF.toByte()) return 1
        val marker = bytes[offset + 1].toInt() and 0xFF
        if (marker == 0xD9) return 1
        if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
            offset += 2
            continue
        }
        val length = ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
        if (length < 2 || offset + 2 + length > bytes.size) return 1
        if (marker == 0xE1 && length >= 16) {
            val exifStart = offset + 4
            if (bytes[exifStart] == 'E'.code.toByte() && bytes[exifStart + 1] == 'x'.code.toByte() &&
                bytes[exifStart + 2] == 'i'.code.toByte() && bytes[exifStart + 3] == 'f'.code.toByte() &&
                bytes[exifStart + 4] == 0.toByte() && bytes[exifStart + 5] == 0.toByte()
            ) {
                val orientation = parseTiffOrientation(bytes, exifStart + 6, exifStart + length)
                if (orientation != 1) return orientation
            }
        }
        offset += 2 + length
    }
    return 1
}

private fun parseTiffOrientation(bytes: ByteArray, start: Int, end: Int): Int {
    if (start + 8 > end) return 1
    val littleEndian = when (bytes[start].toInt()) {
        'I'.code -> true
        'M'.code -> false
        else -> return 1
    }
    val magic = readU16(bytes, start + 2, littleEndian, end) ?: return 1
    if (magic != 0x2A) return 1
    val ifd0Offset = readU32(bytes, start + 4, littleEndian, end) ?: return 1
    var ifd = start + ifd0Offset
    val entryCount = readU16(bytes, ifd, littleEndian, end) ?: return 1
    ifd += 2
    for (i in 0 until entryCount) {
        val tag = readU16(bytes, ifd, littleEndian, end) ?: return 1
        val type = readU16(bytes, ifd + 2, littleEndian, end) ?: return 1
        if (tag == 0x0112) {
            if (type != 3) return 1
            return readU16(bytes, ifd + 8, littleEndian, end) ?: 1
        }
        ifd += 12
    }
    return 1
}

private fun readU16(bytes: ByteArray, offset: Int, littleEndian: Boolean, end: Int): Int? {
    if (offset < 0 || offset + 2 > end) return null
    val b0 = bytes[offset].toInt() and 0xFF
    val b1 = bytes[offset + 1].toInt() and 0xFF
    return if (littleEndian) (b1 shl 8) or b0 else (b0 shl 8) or b1
}

private fun readU32(bytes: ByteArray, offset: Int, littleEndian: Boolean, end: Int): Int? {
    if (offset < 0 || offset + 4 > end) return null
    var value = 0
    for (i in 0 until 4) {
        val byteIndex = if (littleEndian) offset + 3 - i else offset + i
        value = (value shl 8) or (bytes[byteIndex].toInt() and 0xFF)
    }
    return value
}
