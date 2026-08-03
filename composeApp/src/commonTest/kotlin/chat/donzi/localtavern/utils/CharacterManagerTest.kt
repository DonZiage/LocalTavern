package chat.donzi.localtavern.utils

import chat.donzi.localtavern.domain.Character
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CharacterManagerTest {

    private val character = Character(
        id = "char1",
        name = "Alice",
        description = "A curious explorer",
        personality = "Brave and kind",
        scenario = "Deep forest",
        firstMes = "Hello there!",
        mesExample = listOf("Example: *waves*", "Example2: *grins*"),
        creatorNotes = null,
        altGreetings = listOf("Greetings!"),
        avatarData = null
    )

    @Test
    fun exportedJson_usesSillyTavernV2Wrapper() {
        val bytes = CharacterManager.exportToJson(character)
        val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject

        assertEquals("chara_card_v2", root["spec"]?.jsonPrimitive?.content)
        assertEquals("2.0", root["spec_version"]?.jsonPrimitive?.content)
        assertTrue(root.containsKey("data"), "Exported card must embed the card data under the 'data' key")
        val data = root["data"]!!.jsonObject
        assertEquals("Alice", data["name"]?.jsonPrimitive?.content)
        assertEquals("A curious explorer", data["description"]?.jsonPrimitive?.content)
    }

    @Test
    fun exportedJson_roundTripsThroughProcessImport() {
        val bytes = CharacterManager.exportToJson(character)
        val imported = CharacterManager.processImport(bytes, "Alice.json")

        assertNotNull(imported, "Exported card must be re-importable")
        assertEquals("Alice", imported.card.name)
        assertEquals("A curious explorer", imported.card.description)
        assertEquals("Brave and kind", imported.card.personality)
        assertEquals("Deep forest", imported.card.scenario)
        assertEquals("Hello there!", imported.card.first_mes)
        assertEquals(listOf("Greetings!"), imported.card.alternate_greetings)
    }

    @Test
    fun bareCardJson_withoutWrapper_stillImports() {
        val bare = """
            {"name":"Bob","description":"No wrapper","personality":"","scenario":"",
             "first_mes":"Hi","mes_example":"","creator_notes":"","system_prompt":"",
             "alternate_greetings":[]}
        """.trimIndent()
        val imported = CharacterManager.processImport(bare.encodeToByteArray(), "Bob.json")
        assertNotNull(imported)
        assertEquals("Bob", imported.card.name)
        assertEquals("No wrapper", imported.card.description)
    }

    @Test
    fun tavernAIV1Card_importsAsV2() {
        val v1 = """
            {"char_name":"Old Kate","char_persona":"A grizzled innkeeper","char_greeting":"Welcome, traveler!",
             "world_scenario":"A rainy tavern","example_dialogue":"Kate: Hmm.<START>Kate: *grins*",
             "alternate_greetings":"Alt one|||Alt two"}
        """.trimIndent()
        val imported = CharacterManager.processImport(v1.encodeToByteArray(), "Old Kate.json")

        assertNotNull(imported, "Legacy TavernAI v1 cards must import instead of becoming blank characters")
        assertEquals("Old Kate", imported.card.name)
        assertEquals("A grizzled innkeeper", imported.card.description)
        assertEquals("A rainy tavern", imported.card.scenario)
        assertEquals("Welcome, traveler!", imported.card.first_mes)
        assertEquals(listOf("Alt one", "Alt two"), imported.card.alternate_greetings)
        assertEquals(2, imported.card.mes_example.split("<START>").size,
            "v1 example_dialogue separators must be preserved")
    }

    @Test
    fun corruptAvatar_neverWritesBrokenPng() {
        // A non-PNG avatar that the platform converter cannot convert must
        // fall back to a JSON card instead of writing a corrupt .png.
        val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 1)
        val (fileName, bytes) = CharacterManager.prepareExportBytes(
            character.copy(avatarData = fakeJpeg)
        )
        assertTrue(fileName.endsWith(".json"), "Unconvertible avatar must fall back to JSON export, got $fileName")
        val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        assertEquals("chara_card_v2", root["spec"]?.jsonPrimitive?.content)
    }

    @Test
    fun truncatedPng_doesNotCrashExport() {
        // A PNG whose IHDR length field is garbage must not splice outside
        // the buffer (the old code threw ArrayIndexOutOfBoundsException).
        val corruptPng = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), // IHDR length: huge
            0x49, 0x48, 0x44, 0x52
        )
        val (fileName, bytes) = CharacterManager.prepareExportBytes(
            character.copy(avatarData = corruptPng)
        )
        assertTrue(fileName.endsWith(".png"), "Signature-valid PNG must still export as .png, got $fileName")
        assertTrue(bytes.size == corruptPng.size, "Corrupt PNG must pass through unchanged, not crash")
    }

    @Test
    fun processImportBatch_mixedFiles_parsesCardsAndCountsFailures() {
        val alicePng = pngCardBytes(cardJson("Alice"))
        val bobJson = cardJson("Bob").encodeToByteArray()
        val broken = "not a character card".encodeToByteArray()

        val result = CharacterManager.processImportBatch(
            listOf(
                PickedFile("alice.png", alicePng),
                PickedFile("Bob.json", bobJson),
                PickedFile("broken.png", broken)
            )
        )

        assertEquals(listOf("Alice", "Bob"), result.imports.map { it.card.name })
        assertEquals(listOf("broken.png"), result.failed)
        // PNG cards keep their original bytes as the avatar; JSON cards get none.
        assertContentEquals(alicePng, result.imports[0].avatarData)
        assertEquals(null, result.imports[1].avatarData)
    }

    @Test
    fun processImportBatch_zipOfCards_expandsAndImports() {
        val zipBytes = Zip.createArchive(
            listOf(
                ZipEntry("cards/Alice.png", pngCardBytes(cardJson("Alice"))),
                ZipEntry("cards/Bob.json", cardJson("Bob").encodeToByteArray()),
                ZipEntry("README.txt", "ignore me".encodeToByteArray())
            )
        )

        val result = CharacterManager.processImportBatch(
            listOf(PickedFile("character-pack.zip", zipBytes))
        )

        assertEquals(listOf("Alice", "Bob"), result.imports.map { it.card.name })
        assertEquals(emptyList(), result.failed)
    }

    @Test
    fun processImportBatch_corruptZip_countsAsFailure() {
        val result = CharacterManager.processImportBatch(
            listOf(PickedFile("bad.zip", byteArrayOf(1, 2, 3)))
        )
        assertEquals(emptyList(), result.imports)
        assertEquals(listOf("bad.zip"), result.failed)
    }

    @Test
    fun prepareBatchExportBytes_deduplicatesArchiveNames() {
        val alice1 = character.copy(name = "Alice", avatarData = null)
        val alice2 = character.copy(name = "Alice", avatarData = null)
        val alice3 = character.copy(name = "Alice", avatarData = null)

        val archive = CharacterManager.prepareBatchExportBytes(listOf(alice1, alice2, alice3))
        val entries = Zip.readArchive(archive)

        assertEquals(
            listOf("Alice.json", "Alice (2).json", "Alice (3).json"),
            entries.map { it.name },
            "Colliding card names must get an internal suffix inside the archive"
        )
    }

    @Test
    fun prepareBatchExportBytes_roundTripsThroughBatchImport() {
        val alice = character.copy(name = "Alice", avatarData = null)
        val bob = character.copy(name = "Bob", avatarData = null, description = "A different explorer")

        val archive = CharacterManager.prepareBatchExportBytes(listOf(alice, bob))
        val result = CharacterManager.processImportBatch(
            listOf(PickedFile("export.zip", archive))
        )

        assertEquals(listOf("Alice", "Bob"), result.imports.map { it.card.name })
        assertEquals("A different explorer", result.imports[1].card.description)
        assertEquals(emptyList(), result.failed)
    }

    private fun cardJson(name: String) =
        """{"name":"$name","description":"desc","personality":"","scenario":"","first_mes":"Hi","mes_example":"","creator_notes":""}"""

    // Builds a minimal PNG that embeds a chara_card_v2 payload in a tEXt
    // chunk, mirroring what real SillyTavern exports look like.
    @OptIn(ExperimentalEncodingApi::class)
    private fun pngCardBytes(jsonString: String): ByteArray {
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        val chunkData = "chara\u0000${Base64.encode(jsonString.encodeToByteArray())}".encodeToByteArray()
        val ihdr = buildChunk("IHDR", ByteArray(13))
        val text = buildChunk("tEXt", chunkData)
        val iend = buildChunk("IEND", ByteArray(0))
        return signature + ihdr + text + iend
    }

    private fun buildChunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.encodeToByteArray()
        val out = ByteArray(12 + data.size)
        out[0] = ((data.size shr 24) and 0xFF).toByte()
        out[1] = ((data.size shr 16) and 0xFF).toByte()
        out[2] = ((data.size shr 8) and 0xFF).toByte()
        out[3] = (data.size and 0xFF).toByte()
        typeBytes.copyInto(out, 4)
        data.copyInto(out, 8)
        val crc = CommonCRC32()
        crc.update(typeBytes)
        crc.update(data)
        val crcValue = crc.value
        out[out.size - 4] = ((crcValue shr 24) and 0xFF).toByte()
        out[out.size - 3] = ((crcValue shr 16) and 0xFF).toByte()
        out[out.size - 2] = ((crcValue shr 8) and 0xFF).toByte()
        out[out.size - 1] = (crcValue and 0xFF).toByte()
        return out
    }
}
