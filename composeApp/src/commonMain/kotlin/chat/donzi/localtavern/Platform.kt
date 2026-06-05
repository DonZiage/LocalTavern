package chat.donzi.localtavern

expect fun saveFile(fileName: String, bytes: ByteArray): String?

expect fun openDirectory(path: String)

expect fun convertToPng(bytes: ByteArray): ByteArray

expect val isDesktop: Boolean