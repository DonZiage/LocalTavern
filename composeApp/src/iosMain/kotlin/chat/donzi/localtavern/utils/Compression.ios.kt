package chat.donzi.localtavern.utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.zlib.Z_FINISH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2
import platform.zlib.z_stream_s

// zlib's inflateInit2() with a negative window size decodes raw DEFLATE
// (RFC 1951, ZIP method 8) — exactly the stream stored in ZIP entries.
// A 64 KiB scratch buffer is not needed: zlib manages its own window.
@OptIn(ExperimentalForeignApi::class)
actual fun inflateDeflate(bytes: ByteArray, expectedSize: Int): ByteArray? {
    if (expectedSize <= 0) return null
    return memScoped {
        val stream = alloc<z_stream_s>()
        // windowBits = -15 -> raw deflate, no zlib header, no gzip header.
        if (inflateInit2(stream.ptr, -15) != Z_OK) return@memScoped null
        try {
            val output = ByteArray(expectedSize)
            val result = bytes.usePinned { src ->
                output.usePinned { dst ->
                    stream.next_in = src.addressOf(0).reinterpret()
                    stream.avail_in = bytes.size.toUInt()
                    stream.next_out = dst.addressOf(0).reinterpret()
                    stream.avail_out = expectedSize.toUInt()
                    inflate(stream.ptr, Z_FINISH)
                }
            }
            // The central directory size is authoritative: accept only a
            // complete stream that decompressed to exactly that many bytes.
            if (result == Z_STREAM_END && stream.total_out == expectedSize.toULong()) output else null
        } finally {
            inflateEnd(stream.ptr)
        }
    }
}
