package chat.donzi.localtavern.utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Compression.COMPRESSION_ZLIB
import platform.Compression.compression_decode_buffer

// The iOS Compression framework decodes raw DEFLATE (PNG IDAT style) with
// COMPRESSION_ZLIB, which is exactly the stream stored in ZIP method 8.
// A scratch buffer of at least 64 KiB is recommended by the framework docs.
@OptIn(ExperimentalForeignApi::class)
actual fun inflateDeflate(bytes: ByteArray, expectedSize: Int): ByteArray? {
    if (expectedSize <= 0) return null
    val output = ByteArray(expectedSize)
    val scratch = ByteArray(64 * 1024)
    val written = bytes.usePinned { src ->
        output.usePinned { dst ->
            scratch.usePinned { scratchBuf ->
                compression_decode_buffer(
                    dst.addressOf(0),
                    output.size.toULong(),
                    src.addressOf(0),
                    bytes.size.toULong(),
                    COMPRESSION_ZLIB,
                    scratchBuf.addressOf(0),
                    scratch.size.toULong()
                )
            }
        }
    }
    return if (written.toLong() == expectedSize.toLong()) output else null
}
