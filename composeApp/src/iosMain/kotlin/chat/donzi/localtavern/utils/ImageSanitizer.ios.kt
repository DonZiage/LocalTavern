package chat.donzi.localtavern.utils

import kotlinx.cinterop.ExperimentalForeignApi
import org.jetbrains.skia.Image
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

@OptIn(ExperimentalForeignApi::class)
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

            val scale = ImageSanitizer.MAX_DIMENSION_PX.toFloat() / largestDim
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
                    val data = snapshot.encodeToData(EncodedImageFormat.JPEG, 85) ?: return null
                    return data.bytes.takeIf { it.size <= ImageSanitizer.MAX_BYTES }
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
