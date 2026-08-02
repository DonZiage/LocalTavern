package chat.donzi.localtavern.utils

import org.jetbrains.skia.Image
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

actual fun downscaleImageForChat(bytes: ByteArray): ByteArray? {
    return try {
        val image = Image.makeFromEncoded(bytes)
        try {
            val width = image.width
            val height = image.height
            if (width <= 0 || height <= 0) return null

            val largestDim = maxOf(width, height)
            if (largestDim <= ImageSanitizer.MAX_DIMENSION_PX && bytes.size <= ImageSanitizer.MAX_BYTES) {
                return bytes
            }

            // Never upscale: a small-dimension image that exceeds the byte
            // budget only shrinks by re-encoding, not by enlarging the canvas.
            val scale = (ImageSanitizer.MAX_DIMENSION_PX.toFloat() / largestDim).coerceAtMost(1f)
            val targetWidth = (width * scale).toInt().coerceAtLeast(1)
            val targetHeight = (height * scale).toInt().coerceAtLeast(1)

            val surface = Surface.makeRasterN32Premul(targetWidth, targetHeight)
            try {
                surface.canvas.drawImageRect(
                    image,
                    Rect.makeWH(width.toFloat(), height.toFloat()),
                    Rect.makeWH(targetWidth.toFloat(), targetHeight.toFloat()),
                    Paint()
                )
                val snapshot = surface.makeImageSnapshot()
                try {
                    // Quality ladder: a large-bytes-but-small-dimension image
                    // (or a noisy photo) may not fit the budget at 85%;
                    // re-encode at lower quality instead of silently dropping it.
                    var smallest: ByteArray? = null
                    for (quality in intArrayOf(85, 70, 55)) {
                        val data = snapshot.encodeToData(EncodedImageFormat.JPEG, quality) ?: continue
                        val encoded = data.bytes
                        if (smallest == null || encoded.size < smallest.size) {
                            smallest = encoded
                        }
                        if (encoded.size <= ImageSanitizer.MAX_BYTES) return encoded
                    }
                    // Nothing fit the budget; return the smallest encode rather
                    // than making the photo disappear with no feedback.
                    return smallest
                } finally {
                    snapshot.close()
                }
            } finally {
                surface.close()
            }
        } finally {
            image.close()
        }
    } catch (e: Exception) {
        null
    }
}
