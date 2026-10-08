package chat.donzi.localtavern.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.donzi.localtavern.data.database.LocalTavernDB
import chat.donzi.localtavern.data.database.LogicalClock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Negative/fuzz coverage for the sync wire format. The parser must be total:
// whatever bytes arrive (a hostile or legacy peer, a corrupt file, a buggy
// relay) must either decode or fail with a controlled SerializationException
// — never an unchecked crash, never a hang. Decodable-but-adversarial rows
// must flow through applyChanges without corrupting the database.
class SyncEnvelopeFuzzTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val validEnvelope = SyncEnvelope(
        fromDeviceId = "device-abc123",
        cursor = 42L,
        changes = SyncChanges(
            personas = listOf(SyncPersona(id = "p1", name = "Alice", description = null, avatarData = null, updatedAt = 1000L, isDeleted = 0L)),
            messages = listOf(SyncMessage(
                id = "m1", sessionId = "s1", role = "assistant", content = "hello",
                timestamp = 1L, parentId = null, isActivePath = 1L, updatedAt = 2000L,
                isDeleted = 0L, imageRefs = emptyList(), reasoningText = null, costEstimate = null
            ))
        ),
        fromDeviceName = "Laptop",
        fetchAddress = "192.168.1.5:47324",
        hasMore = false
    )

    private fun encodeValid(): String = json.encodeToString(SyncEnvelope.serializer(), validEnvelope)

    // ---------- Parser fuzz ----------

    @Test
    fun truncatedEnvelopes_neverCrash() {
        val original = encodeValid().encodeToByteArray()
        for (cut in 0 until original.size) {
            val input = String(original.copyOf(cut), Charsets.UTF_8)
            val result = runCatching { decode(input) }
            assertResultIsControlled(result, "truncation at $cut")
        }
    }

    @Test
    fun randomByteCorruptions_neverCrash() {
        val original = encodeValid().encodeToByteArray()
        val random = Random(0xC0FFEE)
        repeat(2000) { iteration ->
            val mutated = original.copyOf()
            val mutations = 1 + random.nextInt(6)
            repeat(mutations) {
                val index = random.nextInt(mutated.size)
                mutated[index] = (random.nextInt(256)).toByte()
            }
            val input = String(mutated, Charsets.UTF_8)
            val result = runCatching { decode(input) }
            assertResultIsControlled(result, "byte corruption $iteration")
        }
    }

    @Test
    fun structuralMutations_neverCrash() {
        val original = encodeValid()
        val random = Random(0xBADCAFE)
        val nastyChars = "{}[]\",:\\0123456789nulltruefalsenul".toCharArray()
        repeat(2000) { iteration ->
            var mutated = original
            val ops = 1 + random.nextInt(8)
            repeat(ops) {
                val op = random.nextInt(4)
                val index = random.nextInt(mutated.length + 1)
                mutated = when (op) {
                    0 -> mutated.substring(0, index) + nastyChars[random.nextInt(nastyChars.size)] + mutated.substring(index)
                    1 -> if (mutated.length > 1) mutated.removeRange(index, (index + 1 + random.nextInt(3)).coerceAtMost(mutated.length)) else mutated
                    2 -> mutated.substring(0, index) + random.nextInt(1 shl 20).toString() + mutated.substring(index)
                    3 -> mutated.substring(0, index) + "\\u0000\\uffff\\n\\t" + mutated.substring(index)
                    else -> mutated
                }
                if (mutated.length > 4096) mutated = mutated.substring(0, 4096)
            }
            val result = runCatching { decode(mutated) }
            assertResultIsControlled(result, "structural mutation $iteration")
        }
    }

    @Test
    fun pureGarbageAndWrongShapes_neverCrash() {
        val random = Random(0xDEADBEEF)
        repeat(500) { iteration ->
            val garbage = buildString(random.nextInt(64)) { repeat(random.nextInt(64)) { append(random.nextInt(32, 127).toChar()) } }
            assertResultIsControlled(runCatching { decode(garbage) }, "garbage $iteration")
        }
        // Valid JSON but not an envelope at all.
        listOf(
            "{}", "[]", "\"hello\"", "42", "null", "true",
            """{"a":1}""", """{"fromDeviceId":123}""", """{"changes":[1,2,3]}""",
            """{"cursor":"abc"}""", """{"hasMore":"yes"}""", """{"fromDeviceId":null}"""
        ).forEach { input ->
            assertResultIsControlled(runCatching { decode(input) }, "shape $input")
        }
    }

    @Test
    fun decodedEnvelopes_keepRequiredFieldsNonNull() {
        // Every decodable envelope must still carry the non-null contract:
        // a null fromDeviceId would be echoed into the AEAD associated data
        // and crash crypto with an unhelpful error later. kotlinx enforces
        // this for non-nullable fields, so decoding must either throw a
        // controlled SerializationException or produce a complete envelope.
        val random = Random(0xABABAB)
        repeat(1000) { iteration ->
            val mutated = mutateNumbers(encodeValid(), random)
            assertResultIsControlled(runCatching { decode(mutated) }, "extreme numbers $iteration")
        }
    }

    // Replaces numeric literals with extreme values: decodable but hostile
    // (Long.MIN/MAX, negatives) — the stamps that flow into LWW and cursors.
    private fun mutateNumbers(jsonText: String, random: Random): String {
        var result = jsonText
        repeat(5) {
            val ranges = Regex("\\d+").findAll(result).map { it.range }.toList()
            if (ranges.isEmpty()) return result
            val range = ranges[random.nextInt(ranges.size)]
            val replacement = when (random.nextInt(5)) {
                0 -> "0"
                1 -> "-1"
                2 -> "9223372036854775807"
                3 -> "-9223372036854775808"
                else -> "1"
            }
            result = result.substring(0, range.first) + replacement + result.substring(range.last + 1)
        }
        return result
    }

    private fun decode(input: String): SyncEnvelope =
        json.decodeFromString(SyncEnvelope.serializer(), input)

    private fun assertResultIsControlled(result: Result<SyncEnvelope>, context: String) {
        if (result.isFailure) {
            val throwable = result.exceptionOrNull()!!
            assertTrue(
                throwable is SerializationException,
                "Uncontrolled exception on $context: ${throwable::class.simpleName}: ${throwable.message?.take(120)}"
            )
        }
    }

    // ---------- Adversarial rows through applyChanges ----------

    @Test
    fun garbageMessageRefs_areFilteredNotStoredOrServed() = runTest {
        val device = newDevice("device-a")
        val validSha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        device.repo.applyChanges(
            SyncChanges(messages = listOf(SyncMessage(
                id = "m1", sessionId = "s1", role = "assistant", content = "x",
                timestamp = 1L, parentId = null, isActivePath = 1L, updatedAt = 100L,
                isDeleted = 0L,
                imageRefs = listOf(
                    SyncImageRef(sha256 = "../../etc/passwd", size = 100L),
                    SyncImageRef(sha256 = "zzzz", size = 100L),
                    SyncImageRef(sha256 = validSha, size = 42L),
                    SyncImageRef(sha256 = "A".repeat(64), size = 100L)
                ),
                reasoningText = null, costEstimate = null
            ))),
            peerDeviceId = "device-b"
        )
        val stored = device.db.localTavernDBQueries.selectMessageByIdAny("m1").executeAsOneOrNull()!!
        val refs = chat.donzi.localtavern.utils.deserializeImageRefs(stored.imageRefs)
        assertEquals(listOf(validSha), refs.map { it.sha256 }, "Only the canonical SHA-256 ref may survive")
    }

    @Test
    fun extremeStampsAndWeirdRows_neverCrashOrPollute() = runTest {
        val device = newDevice("device-a")
        val random = Random(0xFEED)

        // A batch of adversarial-but-decodable rows.
        val personas = (0 until 20).map { i ->
            SyncPersona(
                id = "p$i", name = "p$i",
                description = if (i % 3 == 0) null else "x".repeat(i * 37),
                avatarData = if (i % 5 == 0) ByteArray(i) { random.nextInt().toByte() } else null,
                updatedAt = when (i % 4) {
                    0 -> 0L
                    1 -> Long.MAX_VALUE
                    2 -> -1L
                    else -> random.nextLong()
                },
                isDeleted = if (i % 7 == 0) 1L else 0L,
                syncSeq = if (i % 2 == 0) -random.nextInt(100).toLong() else random.nextLong()
            )
        }
        device.repo.applyChanges(SyncChanges(personas = personas), peerDeviceId = "device-b")

        // Negative timestamps and max-value stamps must not break LWW, the
        // ledger or the delta cut: every row is queryable (tombstones
        // included), the max stamp is absorbed, and a subsequent local write
        // still out-stamps it.
        val tombstoned = personas.count { it.isDeleted == 1L }
        (0 until 20).forEach { i ->
            val row = device.db.localTavernDBQueries.selectPersonaByIdAny("p$i").executeAsOneOrNull()
            assertTrue(row != null, "Every adversarial persona row must be stored (p$i)")
        }
        val stored = device.db.localTavernDBQueries.selectAllPersonas().executeAsList()
        assertEquals(20 - tombstoned, stored.size, "Only tombstoned rows are hidden from app-facing queries")
        val maxStamped = stored.maxOf { it.updatedAt }
        assertEquals(Long.MAX_VALUE, maxStamped)

        // A local write after absorbing Long.MAX_VALUE must not collide.
        device.repo.applyChanges(
            SyncChanges(personas = listOf(SyncPersona(id = "p-extra", name = "x", description = null, avatarData = null, updatedAt = Long.MAX_VALUE, isDeleted = 0L))),
            peerDeviceId = "seed"
        )
        assertTrue(
            device.db.localTavernDBQueries.selectAllPersonas().executeAsList().size >= stored.size + 1,
            "A write stamped after an absorbed Long.MAX_VALUE must still land"
        )
    }

    @Test
    fun duplicateAndOversizedRows_areHandled() = runTest {
        val device = newDevice("device-a")
        // Same id applied repeatedly with equal stamps must stay idempotent
        // (single row, single ledger entry, no events).
        repeat(5) {
            device.repo.applyChanges(
                SyncChanges(personas = listOf(SyncPersona(id = "dup", name = "x", description = null, avatarData = null, updatedAt = 500L, isDeleted = 0L))),
                peerDeviceId = "device-b"
            )
        }
        assertEquals(1, device.db.localTavernDBQueries.selectAllPersonas().executeAsList().size)
        assertEquals(500L, device.db.localTavernDBQueries.selectAppliedRow("dup", "device-b").executeAsOneOrNull()?.appliedUpdatedAt ?: 0L)

        // An oversized content string (near the envelope bound) must apply.
        val huge = "x".repeat(4 * 1024 * 1024)
        val message = SyncMessage(
            id = "huge", sessionId = "s1", role = "assistant", content = huge,
            timestamp = 1L, parentId = null, isActivePath = 1L, updatedAt = 600L,
            isDeleted = 0L, imageRefs = emptyList(), reasoningText = null, costEstimate = null
        )
        device.repo.applyChanges(SyncChanges(messages = listOf(message)), peerDeviceId = "device-b")
        assertEquals(huge, device.db.localTavernDBQueries.selectMessageByIdAny("huge").executeAsOneOrNull()?.content)
    }

    private fun TestScope.newDevice(deviceId: String): TestDevice {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalTavernDB.Schema.create(driver)
        val db = LocalTavernDB(driver)
        val identity = SyncIdentity(
            deviceId = deviceId,
            deviceName = deviceId,
            privateKeyBase64 = kotlin.io.encoding.Base64.encode(ByteArray(32) { 1 }),
            publicKeyBase64 = kotlin.io.encoding.Base64.encode(ByteArray(32) { 2 })
        )
        return TestDevice(SyncRepository(db, identity, clock = LogicalClock(db)), db)
    }

    private class TestDevice(val repo: SyncRepository, val db: LocalTavernDB)
}
