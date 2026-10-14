package chat.donzi.localtavern.data.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

// The native driver maintains its own single reader connection
// (maxReaderConnections = 1) and a single transaction connection; keeping the
// app's dispatchers serialized preserves today's semantics exactly.
actual fun createDatabaseDispatchers(): DatabaseDispatchers = DatabaseDispatchers.serialized()
