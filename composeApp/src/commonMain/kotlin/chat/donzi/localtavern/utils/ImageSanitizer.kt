package chat.donzi.localtavern.utils

// Picked photos are downscaled and re-encoded before they are stored in the
// database or attached to an LLM request. Without limits, a full-resolution
// camera photo (10-50 MB) would be held in memory, persisted as a BLOB, and
// base64-encoded into every vision payload.
object ImageSanitizer {
    const val MAX_PICKED_IMAGES = 4
    const val MAX_DIMENSION_PX = 1024
    const val MAX_BYTES = 1_500_000

    fun sanitize(images: List<ByteArray>, preserveOriginal: Boolean = false): List<ByteArray> {
        if (preserveOriginal) return images.take(MAX_PICKED_IMAGES)
        return images
            .take(MAX_PICKED_IMAGES)
            .mapNotNull { downscaleImageForChat(it) }
    }
}

/**
 * Decodes [bytes], downscales it to at most [ImageSanitizer.MAX_DIMENSION_PX]
 * on the longest edge, and re-encodes it as JPEG under
 * [ImageSanitizer.MAX_BYTES]. Returns null when the image cannot be decoded.
 */
expect fun downscaleImageForChat(bytes: ByteArray): ByteArray?
