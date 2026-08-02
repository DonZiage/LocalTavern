package chat.donzi.localtavern.utils

import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataCreateMutable
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberDoubleType
import platform.CoreFoundation.kCFStringEncodingASCII
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.ImageIO.CGImageDestinationAddImage
import platform.ImageIO.CGImageDestinationCreateWithData
import platform.ImageIO.CGImageDestinationFinalize
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.kCGImageDestinationLossyCompressionQuality
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceCreateThumbnailWithTransform
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize
import platform.posix.memcpy

private val JPEG_QUALITY_LADDER = intArrayOf(85, 70, 55)

// ImageIO decodes thumbnails with bounded memory (it reads the compressed
// stream directly, never materializing a full-resolution pixel buffer), which
// Skia's Image.makeFromEncoded cannot do — a 48MP camera photo would otherwise
// spike ~190MB of RAM before being scaled down.
@OptIn(ExperimentalForeignApi::class)
actual fun downscaleImageForChat(bytes: ByteArray): ByteArray? {
    if (bytes.isEmpty()) return null
    return try {
        // Under the byte budget: keep the original untouched ONLY when it also
        // fits the dimension cap. A 3000px-wide photo under 1.5 MB would
        // otherwise reach the API at full resolution (huge base64 payloads,
        // vision models rejecting oversized dimensions). readImageDimensions
        // reads only the header, so no full decode is needed for the decision.
        if (bytes.size <= ImageSanitizer.MAX_BYTES) {
            val dims = readImageDimensions(bytes)
            if (dims == null || maxOf(dims.first, dims.second) <= ImageSanitizer.MAX_DIMENSION_PX) {
                return bytes
            }
        }

        memScoped {
            val sourceData = bytes.usePinned { pinned ->
                CFDataCreate(null, pinned.addressOf(0).reinterpret<UByteVar>(), bytes.size.convert())
            } ?: return null
            val source = CGImageSourceCreateWithData(sourceData, null) ?: return null

            val maxPixelSize = alloc<DoubleVar>()
            maxPixelSize.value = ImageSanitizer.MAX_DIMENSION_PX.toDouble()
            val pixelSizeNumber = CFNumberCreate(null, kCFNumberDoubleType, maxPixelSize.ptr) ?: return null

            val options = CFDictionaryCreateMutable(
                null, 3, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr
            ) ?: return null
            CFDictionarySetValue(options, kCGImageSourceThumbnailMaxPixelSize, pixelSizeNumber)
            CFDictionarySetValue(options, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)
            CFDictionarySetValue(options, kCGImageSourceCreateThumbnailWithTransform, kCFBooleanTrue)

            val thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0uL, options) ?: return null

            val jpegType = CFStringCreateWithCString(null, "public.jpeg", kCFStringEncodingASCII)
                ?: return null

            // Quality ladder: a large-bytes-but-small-dimension image (or a
            // noisy photo) may not fit the budget at 85%; re-encode at lower
            // quality instead of silently dropping the image.
            var smallest: ByteArray? = null
            for (quality in JPEG_QUALITY_LADDER) {
                val qualityValue = alloc<DoubleVar>()
                qualityValue.value = quality.toDouble()
                val qualityNumber = CFNumberCreate(null, kCFNumberDoubleType, qualityValue.ptr) ?: continue

                val properties = CFDictionaryCreateMutable(
                    null, 1, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr
                ) ?: continue
                CFDictionarySetValue(properties, kCGImageDestinationLossyCompressionQuality, qualityNumber)

                val output = CFDataCreateMutable(null, 0) ?: continue
                val destination = CGImageDestinationCreateWithData(output, jpegType, 1uL, null) ?: continue
                CGImageDestinationAddImage(destination, thumbnail, properties)
                if (!CGImageDestinationFinalize(destination)) continue

                val length = CFDataGetLength(output)
                if (length <= 0) continue
                val encoded = ByteArray(length.toInt())
                val dataPtr = CFDataGetBytePtr(output) ?: continue
                encoded.usePinned { pinned ->
                    memcpy(pinned.addressOf(0), dataPtr, length.toULong())
                }
                if (smallest == null || encoded.size < smallest.size) {
                    smallest = encoded
                }
                if (encoded.size <= ImageSanitizer.MAX_BYTES) return encoded
            }
            // Nothing fit the budget; return the smallest encode rather than
            // making the photo disappear with no feedback.
            smallest
        }
    } catch (e: Exception) {
        null
    }
}
