package chat.donzi.localtavern.utils

// Inflates a raw DEFLATE stream (ZIP method 8). Returns null when the input
// is corrupt or does not decompress to exactly [expectedSize] bytes (the
// central directory size is authoritative).
internal expect fun inflateDeflate(bytes: ByteArray, expectedSize: Int): ByteArray?
