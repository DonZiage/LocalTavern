package chat.donzi.localtavern.data.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The hybrid logical clock that stamps sync LWW timestamps. The whole point
// of it: an edit that causally follows a received row must always out-stamp
// that row, even when the editing device's wall clock is far behind.
class LogicalClockTest {

    private fun newClock(wallClock: () -> Long): Pair<LogicalClock, LocalTavernDB> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalTavernDB.Schema.create(driver)
        val db = LocalTavernDB(driver)
        return LogicalClock(db, wallClock) to db
    }

    @Test
    fun stampsAreStrictlyIncreasing_evenWithAFrozenWallClock() {
        val (clock, _) = newClock { 1000L }
        val t1 = clock.nextTimestamp()
        val t2 = clock.nextTimestamp()
        val t3 = clock.nextTimestamp()
        assertEquals(1000L, t1, "First stamp is the wall clock itself")
        assertTrue(t2 > t1, "Second stamp must exceed the first")
        assertTrue(t3 > t2, "Stamps must be strictly monotone")
    }

    @Test
    fun absorbedTimestampAdvancesLaterStamps() {
        val (clock, _) = newClock { 1000L }
        clock.absorb(5_000_000L)
        assertTrue(clock.nextTimestamp() > 5_000_000L, "A local write after observing a peer version must out-stamp it")
    }

    @Test
    fun slowWallClockNeverRegressesBelowObservedTimestamps() {
        val (clock, _) = newClock { 10L }
        clock.absorb(1_000_000L)
        repeat(10) { clock.nextTimestamp() }
        assertTrue(clock.nextTimestamp() > 1_000_000L, "The counter must stay above every absorbed timestamp")
    }

    @Test
    fun counterPersistsAcrossInstances() {
        val (clock1, db) = newClock { 100L }
        clock1.absorb(9_000_000L)
        val clock2 = LogicalClock(db) { 100L }
        assertTrue(clock2.nextTimestamp() > 9_000_000L, "A fresh instance must continue from the persisted high-water mark")
    }

    @Test
    fun worksWithoutAPreExistingAppSettingsRow() {
        // No code path ran insertDefaultSettings; the clock self-heals.
        val (clock, _) = newClock { 42L }
        assertEquals(42L, clock.nextTimestamp())
    }
}
