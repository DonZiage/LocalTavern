package chat.donzi.localtavern.data.database

import kotlin.time.Clock

/**
 * Hybrid logical clock backing the sync LWW timestamps (the `updatedAt`
 * column of every synced row).
 *
 * A plain wall-clock timestamp breaks LWW convergence when two devices'
 * clocks drift: if device B edits a row AFTER receiving A's version, but
 * B's clock is behind A's, B's edit would carry a smaller timestamp and be
 * rejected as stale by A — a silently lost update. The fix is to stamp rows
 * with `max(wallClock, lastHlc + 1)` where `lastHlc` is the highest
 * timestamp this device has ever generated OR received. A device that has
 * seen a peer's future-dated row cannot produce a smaller stamp afterwards,
 * so any edit that causally follows a received version always wins LWW
 * regardless of wall-clock skew.
 *
 * The counter is persisted in AppSettings, so the invariant survives app
 * restarts (an in-memory counter would reset to wall clock and re-expose
 * the skew window).
 *
 * This is not thread-safe against a torn read-modify-write between two
 * concurrent callers; the SQLite drivers serialize statement execution, and
 * a collision merely produces two rows with the same stamp, which the
 * existing deviceId tie-break resolves deterministically — the same
 * behavior same-millisecond wall-clock writes always had.
 */
class LogicalClock(
    private val database: LocalTavernDB,
    private val wallClock: () -> Long = { Clock.System.now().toEpochMilliseconds() }
) {
    private val queries get() = database.localTavernDBQueries

    /** Next monotone logical timestamp for a local write. */
    fun nextTimestamp(): Long {
        queries.insertDefaultSettings()
        val last = queries.selectLastHlc().executeAsOne()
        val next = maxOf(wallClock(), last + 1)
        queries.updateLastHlc(next)
        return next
    }

    /**
     * Advances the clock past a timestamp observed from a peer. Called for
     * every incoming sync envelope (regardless of whether its individual
     * rows were applied as new, won, or lost LWW), so a later local edit
     * always out-stamps the observed version.
     */
    fun absorb(timestamp: Long) {
        queries.insertDefaultSettings()
        val last = queries.selectLastHlc().executeAsOne()
        if (timestamp > last) {
            queries.updateLastHlc(timestamp)
        }
    }
}
