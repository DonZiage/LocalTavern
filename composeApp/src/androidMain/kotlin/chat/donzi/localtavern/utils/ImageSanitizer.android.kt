package chat.donzi.localtavern.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream

actual fun downscaleImageForChat(bytes: ByteArray): ByteArray? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val largestDim = maxOf(bounds.outWidth, bounds.outHeight)
        val isJpeg = bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        // BitmapFactory ignores the EXIF orientation tag; rotating the pixels
        // later bakes the rotation in. When nothing needs to change, keep the
        // original bytes instead of re-encoding: this preserves PNG
        // transparency / GIF animation / WebP and avoids a lossy round-trip,
        // matching the desktop and iOS behavior.
        val needsExifRotation = isJpeg && readJpegExifOrientation(bytes) != 1
        if (largestDim <= ImageSanitizer.MAX_DIMENSION_PX && bytes.size <= ImageSanitizer.MAX_BYTES && !needsExifRotation) {
            return bytes
        }

        var sampleSize = 1
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

        // Apply the EXIF rotation read above (a JPEG that needs rotating was
        // excluded from the early return). The re-encode below then bakes the
        // rotation in and drops the EXIF metadata that would otherwise rotate
        // the pixels a second time.
        val orientation = if (isJpeg) readJpegExifOrientation(bytes) else 1
        if (orientation != 1) {
            val matrix = Matrix().apply {
                when (orientation) {
                    2 -> postScale(-1f, 1f)
                    3 -> postRotate(180f)
                    4 -> {
                        postRotate(180f)
                        postScale(-1f, 1f)
                    }
                    5 -> {
                        postRotate(-90f)
                        postScale(-1f, 1f)
                    }
                    6 -> postRotate(90f)
                    7 -> {
                        postRotate(90f)
                        postScale(-1f, 1f)
                    }
                    8 -> postRotate(-90f)
                }
            }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) {
                bitmap.recycle()
                bitmap = rotated
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
    var smallest: ByteArray? = null
    while (quality >= 50) {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        val result = outputStream.toByteArray()
        if (smallest == null || result.size < smallest.size) {
            smallest = result
        }
        if (result.size <= ImageSanitizer.MAX_BYTES) return result
        quality -= 15
    }
    // Nothing fit the budget; return the smallest encode rather than making
    // the photo disappear with no feedback (matching desktop/iOS).
    return smallest
}
