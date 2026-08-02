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
        val list = ArrayList<ByteArray>(count)
        for (i in 0 until count) {
            val size = bytes.readInt(offset)
            offset += 4
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

public fun detectMimeType(bytes: ByteArray): String {
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
    return "image/jpeg"
}
