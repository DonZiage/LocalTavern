package chat.donzi.localtavern.utils

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry as JZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Interop checks between our hand-rolled ZIP code and the JDK's
// implementation: our writer must produce archives real tools can open, and
// our reader must consume archives real tools produce (including deflated
// entries written with data descriptors, which only the central directory
// can resolve).
class ZipJvmTest {

    @Test
    fun createArchive_isReadableByJavaZip() {
        val entries = listOf(
            ZipEntry("Alice.png", ByteArray(1024) { (it % 251).toByte() }),
            ZipEntry("Bob.json", "{\"name\":\"Bob\"}".encodeToByteArray())
        )
        val archive = Zip.createArchive(entries)

        val readBack = readWithJavaZip(archive)
        assertEquals(setOf("Alice.png", "Bob.json"), readBack.keys)
        assertContentEquals(entries[0].data, readBack.getValue("Alice.png"))
        assertContentEquals(entries[1].data, readBack.getValue("Bob.json"))
    }

    @Test
    fun readArchive_readsJavaDeflatedZip() {
        val payload = listOf(
            "a.png" to ByteArray(2048) { (it % 7).toByte() },
            "b.json" to "hello, world".encodeToByteArray()
        )
        val archive = buildWithJavaZip(payload, deflate = true)
        val read = Zip.readArchive(archive)

        assertEquals(2, read.size)
        assertEquals("a.png", read[0].name)
        assertContentEquals(payload[0].second, read[0].data)
        assertEquals("b.json", read[1].name)
        assertContentEquals(payload[1].second, read[1].data)
    }

    @Test
    fun readArchive_readsJavaStoredZip() {
        val payload = listOf(
            "a.json" to "{\"name\":\"A\"}".encodeToByteArray()
        )
        val archive = buildWithJavaZip(payload, deflate = false)
        val read = Zip.readArchive(archive)

        assertEquals(1, read.size)
        assertContentEquals(payload[0].second, read[0].data)
    }

    @Test
    fun readArchive_readsEmptyJavaZip() {
        val archive = ByteArrayOutputStream().let { out ->
            ZipOutputStream(out).use { it.finish() }
            out.toByteArray()
        }
        assertTrue(Zip.readArchive(archive).isEmpty())
    }

    @Test
    fun readArchive_rejectsGarbage() {
        assertTrue(Zip.readArchive("definitely not a zip".encodeToByteArray()).isEmpty())
        assertTrue(Zip.readArchive(ByteArray(4096) { 3 }).isEmpty())
    }

    private fun readWithJavaZip(archive: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return result
    }

    private fun buildWithJavaZip(
        entries: List<Pair<String, ByteArray>>,
        deflate: Boolean
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setMethod(if (deflate) ZipOutputStream.DEFLATED else ZipOutputStream.STORED)
            entries.forEach { (name, data) ->
                val entry = JZipEntry(name)
                if (!deflate) {
                    entry.size = data.size.toLong()
                    entry.compressedSize = data.size.toLong()
                    entry.crc = CRC32().apply { update(data) }.value
                }
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
