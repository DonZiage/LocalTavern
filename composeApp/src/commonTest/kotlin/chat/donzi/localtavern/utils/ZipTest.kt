package chat.donzi.localtavern.utils

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZipTest {

    @Test
    fun roundTrip_preservesNamesAndData() {
        val entries = listOf(
            ZipEntry("Alice.png", byteArrayOf(1, 2, 3, 4)),
            ZipEntry("Bob.json", "{\"name\":\"Bob\"}".encodeToByteArray())
        )
        val archive = Zip.createArchive(entries)
        val read = Zip.readArchive(archive)

        assertEquals(2, read.size)
        assertEquals("Alice.png", read[0].name)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), read[0].data)
        assertEquals("Bob.json", read[1].name)
        assertContentEquals("{\"name\":\"Bob\"}".encodeToByteArray(), read[1].data)
    }

    @Test
    fun emptyArchive_roundTrips() {
        val archive = Zip.createArchive(emptyList())
        assertTrue(Zip.readArchive(archive).isEmpty())
    }

    @Test
    fun garbageBytes_readNothing() {
        assertTrue(Zip.readArchive(byteArrayOf(1, 2, 3)).isEmpty())
        assertTrue(Zip.readArchive(ByteArray(200) { 7 }).isEmpty())
        // A PNG signature without a zip structure must not parse either.
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        ) + ByteArray(64) { 0 }
        assertTrue(Zip.readArchive(png).isEmpty())
    }

    @Test
    fun truncatedArchive_readsNothing() {
        val archive = Zip.createArchive(listOf(ZipEntry("A.png", byteArrayOf(9))))
        val cut = archive.copyOf(archive.size - 22) // EOCD removed
        assertTrue(Zip.readArchive(cut).isEmpty())
    }

    @Test
    fun archiveWithoutEocd_readsNothing() {
        val archive = Zip.createArchive(listOf(ZipEntry("A.png", byteArrayOf(9))))
        val cut = archive.copyOf(archive.size - 22 - 20) // EOCD + part of central dir
        assertTrue(Zip.readArchive(cut).isEmpty())
    }

    @Test
    fun directoryEntries_areSkipped() {
        val archive = Zip.createArchive(
            listOf(
                ZipEntry("cards/", byteArrayOf()),
                ZipEntry("cards/Alice.png", byteArrayOf(5))
            )
        )
        val read = Zip.readArchive(archive)
        assertEquals(1, read.size)
        assertEquals("cards/Alice.png", read[0].name)
    }

    @Test
    fun duplicateNames_areAllReturned() {
        val archive = Zip.createArchive(
            listOf(
                ZipEntry("Alice.png", byteArrayOf(1)),
                ZipEntry("Alice.png", byteArrayOf(2))
            )
        )
        val read = Zip.readArchive(archive)
        assertEquals(2, read.size)
        assertContentEquals(byteArrayOf(1), read[0].data)
        assertContentEquals(byteArrayOf(2), read[1].data)
    }

    @Test
    fun utf8Names_surviveRoundTrip() {
        val name = "Märzhase 雪.png"
        val archive = Zip.createArchive(listOf(ZipEntry(name, byteArrayOf(42))))
        val read = Zip.readArchive(archive)
        assertEquals(name, read.single().name)
    }
}
