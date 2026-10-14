package chat.donzi.localtavern.utils

import org.jetbrains.skia.Image
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.Surface

internal data class OrientationTransform(
    val width: Int,
    val height: Int,
    val matrix: Matrix33,
    val isIdentity: Boolean
)

// Builds the affine transform that maps source pixel (x, y) onto the
// correctly oriented display: X = a*x + c*y + e, Y = b*x + d*y + f, where
// (width, height) is the display size. Mirrors the transform Android applies
// for the same EXIF orientation values so both platforms display identically.
internal fun orientationTransform(orientation: Int, width: Int, height: Int): OrientationTransform {
    val w = width.toFloat()
    val h = height.toFloat()
    return when (orientation) {
        2 -> OrientationTransform(width, height, Matrix33(-1f, 0f, w, 0f, 1f, 0f, 0f, 0f, 1f), false)
        3 -> OrientationTransform(width, height, Matrix33(-1f, 0f, w, 0f, -1f, h, 0f, 0f, 1f), false)
        4 -> OrientationTransform(width, height, Matrix33(1f, 0f, 0f, 0f, -1f, h, 0f, 0f, 1f), false)
        5 -> OrientationTransform(height, width, Matrix33(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f), false)
        6 -> OrientationTransform(height, width, Matrix33(0f, -1f, h, 1f, 0f, 0f, 0f, 0f, 1f), false)
        7 -> OrientationTransform(height, width, Matrix33(0f, -1f, h, -1f, 0f, w, 0f, 0f, 1f), false)
        8 -> OrientationTransform(height, width, Matrix33(0f, 1f, 0f, -1f, 0f, w, 0f, 0f, 1f), false)
        else -> OrientationTransform(width, height, Matrix33(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), true)
    }
}

actual fun downscaleImageForChat(bytes: ByteArray): ByteArray? {
    return try {
        val image = Image.makeFromEncoded(bytes)
        try {
            val width = image.width
            val height = image.height
            if (width <= 0 || height <= 0) return null

            // Image.makeFromEncoded ignores the EXIF orientation tag; rotate
            // the pixels explicitly so portrait photos are not stored
            // sideways. The re-encode bakes the rotation in and drops the
            // EXIF metadata that would otherwise rotate the pixels twice.
            val orientation = readJpegExifOrientation(bytes)
            val transform = orientationTransform(orientation, width, height)

            val largestDim = maxOf(transform.width, transform.height)
            if (largestDim <= ImageSanitizer.MAX_DIMENSION_PX && bytes.size <= ImageSanitizer.MAX_BYTES && transform.isIdentity) {
                return bytes
            }

            val scale = (ImageSanitizer.MAX_DIMENSION_PX.toFloat() / largestDim).coerceAtMost(1f)
            val targetWidth = (transform.width * scale).toInt().coerceAtLeast(1)
            val targetHeight = (transform.height * scale).toInt().coerceAtLeast(1)

            val surface = Surface.makeRasterN32Premul(targetWidth, targetHeight)
            try {
                surface.canvas.scale(scale, scale)
                surface.canvas.concat(transform.matrix)
                surface.canvas.drawImage(image, 0f, 0f)
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
