package chat.donzi.localtavern

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

/**
 * Saves a file to the platform's public storage (Downloads/LocalTavern/ExportedCharacters).
 * @return The absolute path to the saved file, or null if it failed.
 */
expect fun saveFile(fileName: String, bytes: ByteArray): String?

/**
 * Opens the specified directory in the platform's file explorer.
 */
expect fun openDirectory(path: String)