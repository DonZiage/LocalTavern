package chat.donzi.localtavern

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

expect fun saveFile(fileName: String, bytes: ByteArray): String?

expect fun openDirectory(path: String)