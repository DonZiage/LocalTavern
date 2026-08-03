package chat.donzi.localtavern

expect fun saveFile(fileName: String, bytes: ByteArray): String?

expect fun openDirectory(path: String)

expect fun convertToPng(bytes: ByteArray): ByteArray

expect val isDesktop: Boolean

/** Human-readable app version ("0.5.5", "dev build", ...) for the About section. */
expect fun appVersionName(): String

/** Absolute path of the SQLite database file, for the About section. */
expect fun appDatabasePath(): String