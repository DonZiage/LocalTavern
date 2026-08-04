package chat.donzi.localtavern

expect fun saveFile(fileName: String, bytes: ByteArray): String?

expect fun openDirectory(path: String)

expect fun convertToPng(bytes: ByteArray): ByteArray

expect val isDesktop: Boolean

/** True on the Android build (used for Android-only UI affordances). */
expect val isAndroid: Boolean

/**
 * Opens the device's security settings (Android: the lock screen settings
 * screen). No-op on desktop and iOS, where the system settings app cannot be
 * opened programmatically.
 */
expect fun openDeviceSecuritySettings()

/** Human-readable app version ("0.5.5", "dev build", ...) for the About section. */
expect fun appVersionName(): String

/** Absolute path of the SQLite database file, for the About section. */
expect fun appDatabasePath(): String