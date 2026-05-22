package chat.donzi.localtavern


expect fun saveFile(fileName: String, bytes: ByteArray): String?

expect fun openDirectory(path: String)