package chat.donzi.localtavern.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

actual fun downscaleImageForChat(bytes: ByteArray): ByteArray? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        val largestDim = maxOf(bounds.outWidth, bounds.outHeight)
        while (largestDim / (sampleSize * 2) >= ImageSanitizer.MAX_DIMENSION_PX) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: return null
        if (maxOf(bitmap.width, bitmap.height) > ImageSanitizer.MAX_DIMENSION_PX) {
            val scale = ImageSanitizer.MAX_DIMENSION_PX.toFloat() / maxOf(bitmap.width, bitmap.height)
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
            if (scaled != bitmap) {
                bitmap.recycle()
                bitmap = scaled
            }
        }

        val encoded = encodeJpegWithinLimit(bitmap)
        bitmap.recycle()
        encoded
    } catch (e: Exception) {
        null
    }
}

private fun encodeJpegWithinLimit(bitmap: Bitmap): ByteArray? {
    var quality = 85
    while (quality >= 50) {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        val result = outputStream.toByteArray()
        if (result.size <= ImageSanitizer.MAX_BYTES) return result
        quality -= 15
    }
    return null
}
