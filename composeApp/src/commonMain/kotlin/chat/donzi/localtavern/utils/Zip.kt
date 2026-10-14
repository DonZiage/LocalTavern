package chat.donzi.localtavern.utils

// Minimal ZIP archive support for batch character import/export. The writer
// only uses method 0 (stored, no compression) so it needs no platform
// deflate code; the reader resolves entry metadata through the central
// directory (accurate sizes even for streamed zips with data descriptors)
// and deflates method-8 entries through the platform inflate actuals.

data class ZipEntry(
    val name: String,
    val data: ByteArray
)

object Zip {
    // Sanity caps: a single character card is a few MB at most; anything
    // bigger is a corrupt archive and must not be materialized.
    const val MAX_ENTRY_SIZE = 64L * 1024 * 1024
    const val MAX_TOTAL_SIZE = 512L * 1024 * 1024
    // Upper bound on the skipped-entry names one walk reports: a hostile
    // archive could otherwise make the caller accumulate tens of thousands
    // of names. Overflow is flagged via ArchiveWalk.truncated.
    const val MAX_SKIPPED_NAMES = 100

    fun createArchive(entries: List<ZipEntry>): ByteArray {
        val locals = entries.map { entry ->
            LocalEntry(
                nameBytes = entry.name.encodeToByteArray(),
                data = entry.data,
                crc = crc32Of(entry.data)
            )
        }

        val localTotal = locals.sumOf { 30 + it.nameBytes.size + it.data.size }
        val centralTotal = locals.sumOf { 46 + it.nameBytes.size }
        val out = ByteArray(localTotal + centralTotal + 22)

        var pos = 0
        val localOffsets = IntArray(locals.size)
        locals.forEachIndexed { index, entry ->
            localOffsets[index] = pos
            writeU32(out, pos, 0x04034b50L)          // local file header
            writeU16(out, pos + 4, 20)               // version needed
            writeU16(out, pos + 6, 0)                // flags
            writeU16(out, pos + 8, 0)                // method: stored
            writeU16(out, pos + 10, 0)               // mod time
            writeU16(out, pos + 12, 0x21)            // DOS date 1980-01-01
            writeU32(out, pos + 14, entry.crc)
            writeU32(out, pos + 18, entry.data.size.toLong())
            writeU32(out, pos + 22, entry.data.size.toLong())
            writeU16(out, pos + 26, entry.nameBytes.size)
            writeU16(out, pos + 28, 0)               // extra length
            entry.nameBytes.copyInto(out, pos + 30)
            pos += 30 + entry.nameBytes.size
            entry.data.copyInto(out, pos)
            pos += entry.data.size
        }

        val centralDirStart = pos
        locals.forEachIndexed { index, entry ->
            writeU32(out, pos, 0x02014b50L)          // central directory header
            writeU16(out, pos + 4, 20)               // version made by
            writeU16(out, pos + 6, 20)               // version needed
            writeU16(out, pos + 8, 0)                // flags
            writeU16(out, pos + 10, 0)               // method: stored
            writeU16(out, pos + 12, 0)               // mod time
            writeU16(out, pos + 14, 0x21)            // DOS date
            writeU32(out, pos + 16, entry.crc)
            writeU32(out, pos + 20, entry.data.size.toLong())
            writeU32(out, pos + 24, entry.data.size.toLong())
            writeU16(out, pos + 28, entry.nameBytes.size)
            writeU16(out, pos + 30, 0)               // extra length
            writeU16(out, pos + 32, 0)               // comment length
            writeU16(out, pos + 34, 0)               // disk number start
            writeU16(out, pos + 36, 0)               // internal attributes
            writeU32(out, pos + 38, 0)               // external attributes
            writeU32(out, pos + 42, localOffsets[index].toLong())
            entry.nameBytes.copyInto(out, pos + 46)
            pos += 46 + entry.nameBytes.size
        }
        val centralSize = pos - centralDirStart

        writeU32(out, pos, 0x06054b50L)              // end of central directory
        writeU16(out, pos + 4, 0)                    // disk number
        writeU16(out, pos + 6, 0)                    // disk with central dir
        writeU16(out, pos + 8, locals.size)          // entries on this disk
        writeU16(out, pos + 10, locals.size)         // total entries
        writeU32(out, pos + 12, centralSize.toLong())
        writeU32(out, pos + 16, centralDirStart.toLong())
        writeU16(out, pos + 20, 0)                   // comment length

        return out
    }

    fun readArchive(bytes: ByteArray): List<ZipEntry> {
        val eocd = findEocd(bytes) ?: return emptyList()
        val entryCount = readU16(bytes, eocd + 10)
        val centralSize = readU32(bytes, eocd + 12)
        val centralOffset = readU32(bytes, eocd + 16)
        if (centralOffset < 0 || centralSize < 0 ||
            centralOffset + centralSize > bytes.size
        ) {
            return emptyList()
        }

        val result = mutableListOf<ZipEntry>()
        var pos = centralOffset.toInt()
        var remaining = centralSize.toInt()
        var totalSize = 0L
        repeat(entryCount) {
            if (remaining < 46 || readU32(bytes, pos) != 0x02014b50L) {
                return@repeat
            }
            val method = readU16(bytes, pos + 10)
            val compressedSize = readU32(bytes, pos + 20)
            val uncompressedSize = readU32(bytes, pos + 24)
            val nameLength = readU16(bytes, pos + 28)
            val extraLength = readU16(bytes, pos + 30)
            val commentLength = readU16(bytes, pos + 32)
            val localOffset = readU32(bytes, pos + 42)
            val headerSize = 46 + nameLength + extraLength + commentLength
            if (remaining < headerSize || pos + headerSize > bytes.size) {
                return@repeat
            }
            val name = bytes.decodeToString(pos + 46, pos + 46 + nameLength)
            pos += headerSize
            remaining -= headerSize

            // Directory entries carry no data; skip them so callers never
            // receive zero-byte pseudo-cards.
            if (name.endsWith("/")) {
                return@repeat
            }

            val raw = extractEntryData(bytes, localOffset, compressedSize) ?: return@repeat
            // Reject a claimed size above the cap BEFORE inflating: the
            // platform inflaters allocate the full expected size up front, so
            // a hostile archive claiming a multi-GiB entry would exhaust
            // memory before the post-inflate cap check below could run.
            if (uncompressedSize > MAX_ENTRY_SIZE) return@repeat
            val data = when (method) {
                0 -> raw
                8 -> inflateDeflate(raw, uncompressedSize.toInt())
                else -> null
            } ?: return@repeat

            if (data.size > MAX_ENTRY_SIZE) return@repeat
            if (totalSize + data.size > MAX_TOTAL_SIZE) return@repeat
            totalSize += data.size
            result.add(ZipEntry(name, data))
        }
        return result
    }

    /**
     * Streams every entry of [bytes] to [onEntry], one at a time, so a large
     * archive never materializes the whole decompressed library in memory
     * (the batch-import path uses this; a mobile device cannot hold both the
     * archive bytes and every entry simultaneously). The walk suspends between
     * entries, so the consumer can persist each entry (or a bounded chunk)
     * before the next one is extracted. The same sanity caps as [readArchive]
     * apply per entry and in total; the walk stops at the cap. Corrupt or
     * over-cap entries are never delivered: their names are reported in
     * [ArchiveWalk.skipped] (bounded) so the caller can report exactly which
     * cards did not make it instead of losing them silently, and
     * [ArchiveWalk.truncated] tells whether the caps cut the walk short.
     */
    suspend fun readArchiveEntries(bytes: ByteArray, onEntry: suspend (ZipEntry) -> Unit): ArchiveWalk {
        fun empty(): ArchiveWalk = ArchiveWalk(0, truncated = false, skipped = emptyList())
        val eocd = findEocd(bytes) ?: return empty()
        val entryCount = readU16(bytes, eocd + 10)
        val centralSize = readU32(bytes, eocd + 12)
        val centralOffset = readU32(bytes, eocd + 16)
        if (centralOffset < 0 || centralSize < 0 ||
            centralOffset + centralSize > bytes.size
        ) {
            return empty()
        }

        var delivered = 0
        var truncated = false
        val skipped = ArrayList<String>()
        fun skip(name: String) {
            // Bound the skipped-name memory against hostile archives with
            // tens of thousands of bad entries; overflow is flagged so the
            // caller still knows cards are missing.
            if (skipped.size < MAX_SKIPPED_NAMES) skipped.add(name)
            else truncated = true
        }
        var pos = centralOffset.toInt()
        var remaining = centralSize.toInt()
        var totalSize = 0L
        repeat(entryCount) {
            if (remaining < 46 || readU32(bytes, pos) != 0x02014b50L) {
                return@repeat
            }
            val method = readU16(bytes, pos + 10)
            val compressedSize = readU32(bytes, pos + 20)
            val uncompressedSize = readU32(bytes, pos + 24)
            val nameLength = readU16(bytes, pos + 28)
            val extraLength = readU16(bytes, pos + 30)
            val commentLength = readU16(bytes, pos + 32)
            val localOffset = readU32(bytes, pos + 42)
            val headerSize = 46 + nameLength + extraLength + commentLength
            if (remaining < headerSize || pos + headerSize > bytes.size) {
                return@repeat
            }
            val name = bytes.decodeToString(pos + 46, pos + 46 + nameLength)
            pos += headerSize
            remaining -= headerSize

            // Directory entries carry no data; skip them so callers never
            // receive zero-byte pseudo-cards.
            if (name.endsWith("/")) {
                return@repeat
            }

            val raw = extractEntryData(bytes, localOffset, compressedSize)
            if (raw == null) {
                skip(name)
                return@repeat
            }
            // Reject a claimed size above the cap BEFORE inflating: the
            // platform inflaters allocate the full expected size up front, so
            // a hostile archive claiming a multi-GiB entry would exhaust
            // memory before the post-inflate cap check below could run.
            if (uncompressedSize > MAX_ENTRY_SIZE) {
                skip(name)
                return@repeat
            }
            val data = when (method) {
                0 -> raw
                8 -> inflateDeflate(raw, uncompressedSize.toInt())
                else -> null
            }
            if (data == null) {
                skip(name)
                return@repeat
            }

            if (data.size > MAX_ENTRY_SIZE) {
                skip(name)
                return@repeat
            }
            if (totalSize + data.size > MAX_TOTAL_SIZE) {
                // Nothing later can fit either (total only grows): stop the
                // walk instead of pointlessly inflating the rest of a giant
                // archive.
                skip(name)
                return ArchiveWalk(delivered, truncated = true, skipped = skipped.toList())
            }
            totalSize += data.size
            onEntry(ZipEntry(name, data))
            delivered++
        }
        return ArchiveWalk(delivered, truncated, skipped.toList())
    }

    private fun extractEntryData(
        bytes: ByteArray,
        localOffset: Long,
        compressedSize: Long
    ): ByteArray? {
        if (localOffset < 0 || localOffset + 30 > bytes.size) return null
        if (readU32(bytes, localOffset.toInt()) != 0x04034b50L) return null
        val nameLength = readU16(bytes, localOffset.toInt() + 26)
        val extraLength = readU16(bytes, localOffset.toInt() + 28)
        val dataStart = localOffset + 30 + nameLength + extraLength
        if (compressedSize < 0 || dataStart + compressedSize > bytes.size) return null
        return bytes.copyOfRange(dataStart.toInt(), (dataStart + compressedSize).toInt())
    }

    private fun findEocd(bytes: ByteArray): Int? {
        // The EOCD is the last record; search only the tail (22 bytes record
        // + max 65535 bytes comment) so the comment length can be verified
        // against the end of the buffer.
        val start = maxOf(0, bytes.size - 22 - 65535)
        for (offset in bytes.size - 22 downTo start) {
            if (readU32(bytes, offset) == 0x06054b50L) {
                val commentLength = readU16(bytes, offset + 20)
                if (offset + 22 + commentLength == bytes.size) return offset
            }
        }
        return null
    }

    private fun crc32Of(data: ByteArray): Long {
        val crc = CommonCRC32()
        crc.update(data)
        return crc.value
    }

    private fun writeU16(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun writeU32(out: ByteArray, offset: Int, value: Long) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = ((value shr 8) and 0xFF).toByte()
        out[offset + 2] = ((value shr 16) and 0xFF).toByte()
        out[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 > bytes.size) return 0
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > bytes.size) return 0
        return (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }
}

// Result of one streaming archive walk: how many entries reached the
// consumer, whether the size caps cut the walk short, and the names of
// entries that could not be delivered (corrupt data, over-cap entries).
// Callers report the card-shaped skipped names as import failures so no card
// is ever lost silently.
data class ArchiveWalk(
    val delivered: Int,
    val truncated: Boolean,
    val skipped: List<String>
)

private class LocalEntry(
    val nameBytes: ByteArray,
    val data: ByteArray,
    val crc: Long
)
