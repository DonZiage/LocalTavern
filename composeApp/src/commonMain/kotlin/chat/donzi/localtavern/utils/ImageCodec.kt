package chat.donzi.localtavern.utils

internal fun Int.writeTo(bytes: ByteArray, offset: Int) {
    bytes[offset] = (this shr 24).toByte()
    bytes[offset + 1] = (this shr 16).toByte()
    bytes[offset + 2] = (this shr 8).toByte()
    bytes[offset + 3] = this.toByte()
}

private fun ByteArray.readInt(offset: Int): Int {
    return ((this[offset].toInt() and 0xFF) shl 24) or
            ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or
            (this[offset + 3].toInt() and 0xFF)
}

internal fun serializeImageList(images: List<ByteArray>?): ByteArray? {
    if (images.isNullOrEmpty()) return null
    val totalSize = 4 + images.sumOf { 4 + it.size }
    val result = ByteArray(totalSize)
    var offset = 0
    images.size.writeTo(result, offset)
    offset += 4
    for (image in images) {
        image.size.writeTo(result, offset)
        offset += 4
        image.copyInto(result, destinationOffset = offset)
        offset += image.size
    }
    return result
}

public fun deserializeImageList(bytes: ByteArray?): List<ByteArray> {
    if (bytes == null || bytes.isEmpty()) return emptyList()
    try {
        var offset = 0
        val count = bytes.readInt(offset)
        offset += 4
        // Guard against a corrupt blob claiming a huge image count: the
        // pre-allocated list would OOM the process (OutOfMemoryError is not
        // an Exception and escapes the catch below).
        if (count < 0 || count > 64) return emptyList()
        val list = ArrayList<ByteArray>(count)
        for (i in 0 until count) {
            val size = bytes.readInt(offset)
            offset += 4
            if (size < 0 || offset + size > bytes.size) return emptyList()
            val img = ByteArray(size)
            bytes.copyInto(img, destinationOffset = 0, startIndex = offset, endIndex = offset + size)
            offset += size
            list.add(img)
        }
        return list
    } catch (e: Exception) {
        return emptyList()
    }
}

public fun detectMimeType(bytes: ByteArray): String? {
    if (bytes.size >= 4 &&
        bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) {
        return "image/png"
    }
    if (bytes.size >= 3 &&
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
        bytes[2] == 0xFF.toByte()) {
        return "image/jpeg"
    }
    if (bytes.size >= 12 &&
        bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
        bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
        bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
    ) {
        return "image/webp"
    }
    if (bytes.size >= 3 &&
        bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte()
    ) {
        return "image/gif"
    }
    // HEIC/HEIF (iPhone photos): ISO BMFF files start with a "ftyp" box at
    // offset 4; otherwise they would silently be labeled JPEG below.
    if (bytes.size >= 12 &&
        bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
        bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()
    ) {
        val brand = bytes.copyOfRange(8, 12).decodeToString()
        if (brand == "heic" || brand == "heix" || brand == "hevc" || brand == "mif1") {
            return "image/heic"
        }
    }
    // Unknown format: report it as unknown instead of lying that it is a JPEG,
    // so the caller can drop it from vision payloads (a mislabeled image would
    // be rejected by the API with a confusing error anyway).
    return null
}

// Reads the pixel dimensions from an image header WITHOUT decoding the full
// file, so platform sanitizers can decide whether to re-encode while keeping
// memory bounded (a 48MP photo is never materialized just to read its size).
// Returns null for formats it cannot parse (unknown or truncated headers).
public fun readImageDimensions(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size >= 24 &&
        bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() &&
        bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
        bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte()
    ) {
        val width = ((bytes[16].toInt() and 0xFF) shl 24) or
                ((bytes[17].toInt() and 0xFF) shl 16) or
                ((bytes[18].toInt() and 0xFF) shl 8) or
                (bytes[19].toInt() and 0xFF)
        val height = ((bytes[20].toInt() and 0xFF) shl 24) or
                ((bytes[21].toInt() and 0xFF) shl 16) or
                ((bytes[22].toInt() and 0xFF) shl 8) or
                (bytes[23].toInt() and 0xFF)
        if (width > 0 && height > 0) return width to height
        return null
    }

    if (bytes.size >= 3 &&
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
        bytes[2] == 0xFF.toByte()
    ) {
        // Walk the JPEG segment markers to the first SOF frame, which carries
        // the encoded height and width (precisely what matters for downscale
        // decisions — the decoded orientation never changes pixel counts).
        var offset = 2
        while (offset + 9 <= bytes.size) {
            if (bytes[offset] != 0xFF.toByte()) return null
            val marker = bytes[offset + 1].toInt() and 0xFF
            if (marker == 0xD9) return null
            if (marker == 0x01 || marker == 0xD8 || marker in 0xD0..0xD7) {
                offset += 2
                continue
            }
            val length = ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
            if (length < 2 || offset + 2 + length > bytes.size) return null
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                val height = ((bytes[offset + 5].toInt() and 0xFF) shl 8) or (bytes[offset + 6].toInt() and 0xFF)
                val width = ((bytes[offset + 7].toInt() and 0xFF) shl 8) or (bytes[offset + 8].toInt() and 0xFF)
                return width to height
            }
            offset += 2 + length
        }
        return null
    }

    // WebP: the VP8X extended header stores width/height minus one as 24-bit
    // little-endian values at fixed offsets.
    if (bytes.size >= 30 &&
        bytes[12] == 'V'.code.toByte() && bytes[13] == 'P'.code.toByte() &&
        bytes[14] == '8'.code.toByte() && bytes[15] == 'X'.code.toByte()
    ) {
        val width = 1 + ((bytes[24].toInt() and 0xFF) or ((bytes[25].toInt() and 0xFF) shl 8) or ((bytes[26].toInt() and 0xFF) shl 16))
        val height = 1 + ((bytes[27].toInt() and 0xFF) or ((bytes[28].toInt() and 0xFF) shl 8) or ((bytes[29].toInt() and 0xFF) shl 16))
        if (width > 0 && height > 0) return width to height
        return null
    }

    return null
}
