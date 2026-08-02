package chat.donzi.localtavern.data.database

import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

abstract class BaseRepository(val database: LocalTavernDB) {
    protected val queries = database.localTavernDBQueries

    @OptIn(ExperimentalUuidApi::class)
    protected fun generateUuid(): String = Uuid.random().toString()

    protected fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
}
