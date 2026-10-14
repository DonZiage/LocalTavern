package chat.donzi.localtavern.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import chat.donzi.localtavern.data.database.LocalTavernDB
import chat.donzi.localtavern.data.database.LogicalClock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Red team: a single synced row larger than any forwardable envelope must be
// rejected BEFORE it is applied. An honest device never ships a sequence group
// above MAX_SAFE_GROUP_PLAINTEXT_BYTES (collectDeltaBatched refuses it with
// hasMore and the exchange fails loudly), so a row above that ceiling arriving
// from a legacy or hostile peer could never be forwarded again — and on a
// mobile heap its decode transient (~4-5x the wire size, held as base64 +
// ciphertext + plaintext + parsed rows at once) kills the app mid-sync.
// Applied, it poisons the database: every future sync fails at the same row
// ("a row on this device is too large to sync") and every launch reloads it
// through the character/timeline flows (SQLite itself stays structurally
// valid, so the corruption-recovery path never fires), leaving a crash-at-
// launch loop that only a data wipe clears.
class SyncOversizedRowRedTeamTest {

    private fun newDb(): LocalTavernDB {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalTavernDB.Schema.create(driver)
        return LocalTavernDB(driver)
    }

    private fun newRepository(db: LocalTavernDB): SyncRepository {
        val identity = SyncIdentity(
            deviceId = "device-a",
            deviceName = "device-a",
            privateKeyBase64 = kotlin.io.encoding.Base64.encode(ByteArray(32) { 1 }),
            publicKeyBase64 = kotlin.io.encoding.Base64.encode(ByteArray(32) { 2 })
        )
        return SyncRepository(db, identity, clock = LogicalClock(db))
    }

    @Test
    fun oversizedPersonaText_isRejectedWithoutApplying() = runTest {
        val db = newDb()
        val repo = newRepository(db)
        // 9 MB of inline text: above the 8 MB ceiling no honest device ever
        // ships, but far below the 24 MB decrypted-envelope gate, so only a
        // per-row check can refuse it.
        val huge = "x".repeat(9 * 1024 * 1024)
        val changes = SyncChanges(
            personas = listOf(
                SyncPersona(
                    id = "p-huge", name = "Huge", description = huge,
                    avatarData = null, updatedAt = 1000L, isDeleted = 0L
                )
            )
        )

        val result = runCatching { repo.applyChanges(changes, peerDeviceId = "device-b") }
        assertTrue(result.isFailure, "A single row larger than any forwardable envelope must be rejected, not applied")
        assertTrue(
            db.localTavernDBQueries.selectAllPersonas().executeAsList().isEmpty(),
            "A rejected envelope must leave zero rows behind"
        )
    }

    @Test
    fun oversizedMessageText_isRejectedWithoutApplying() = runTest {
        val db = newDb()
        val repo = newRepository(db)
        val huge = "x".repeat(9 * 1024 * 1024)
        val changes = SyncChanges(
            messages = listOf(
                SyncMessage(
                    id = "m-huge", sessionId = "s1", role = "user", content = huge,
                    timestamp = 1L, parentId = null, isActivePath = 1L,
                    updatedAt = 1000L, isDeleted = 0L,
                    reasoningText = null, costEstimate = null
                )
            )
        )

        val result = runCatching { repo.applyChanges(changes, peerDeviceId = "device-b") }
        assertTrue(result.isFailure, "A single row larger than any forwardable envelope must be rejected, not applied")
        assertNull(
            db.localTavernDBQueries.selectMessageByIdAny("m-huge").executeAsOneOrNull(),
            "A rejected envelope must leave zero rows behind"
        )
    }

    @Test
    fun honestSizedRows_stillApply() = runTest {
        val db = newDb()
        val repo = newRepository(db)
        // 200 KB rows ship as their own atomic group on honest devices (see
        // SyncBatchingTest.oversizedRowStillShipsAsItsOwnAtomicGroup) and must
        // keep applying after the gate lands.
        val big = "x".repeat(200 * 1024)
        repo.applyChanges(
            SyncChanges(
                personas = listOf(
                    SyncPersona(
                        id = "p-big", name = "Big", description = big,
                        avatarData = null, updatedAt = 1000L, isDeleted = 0L
                    )
                )
            ),
            peerDeviceId = "device-b"
        )
        assertEquals(1, db.localTavernDBQueries.selectAllPersonas().executeAsList().size)
    }
}
