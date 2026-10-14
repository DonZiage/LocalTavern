package chat.donzi.localtavern.utils

// Picked photos are downscaled and re-encoded before they are stored in the
// database or attached to an LLM request. Without limits, a full-resolution
// camera photo (10-50 MB) would be held in memory, persisted as a BLOB, and
// base64-encoded into every vision payload.
//
// The limits also bound what sync ships: message images and avatars are stored
// as BLOBs and travel inside the encrypted sync envelope, so per-message cost
// is capped at MAX_BYTES. This is a deliberate trade-off — a file-based image
// store would avoid DB growth and large sync exchanges entirely, but would
// require a schema migration, sync file transfer, and per-platform storage,
// which is not implemented.
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
