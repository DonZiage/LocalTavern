package chat.donzi.localtavern.data.database

import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

abstract class BaseRepository(
    val database: LocalTavernDB,
    // One clock must be shared by every repository in the app (and by
    // SyncRepository): the sync layer advances it on receive, and the write
    // paths must stamp from the same counter or the skew protection breaks.
    protected val clock: LogicalClock = LogicalClock(database)
) {
    protected val queries = database.localTavernDBQueries

    @OptIn(ExperimentalUuidApi::class)
    protected fun generateUuid(): String = Uuid.random().toString()

    /** Wall-clock millis for display ordering (message timestamps, "last used"). */
    protected fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

    /** Logical sync timestamp for the updatedAt of synced rows (HLC). */
    protected fun nextTimestamp(): Long = clock.nextTimestamp()

    /** Device-local monotone sync sequence for the syncSeq of synced rows. */
    protected fun nextSyncSeq(): Long = clock.nextSyncSeq()
}
