package chat.donzi.localtavern

import java.io.File
import java.awt.Desktop

class JVMPlatform: Platform {
    override val name: String = "Java ${System.getProperty("java.version")}"
}

actual fun getPlatform(): Platform = JVMPlatform()

actual fun saveFile(fileName: String, bytes: ByteArray): String? {
    return try {
        val userHome = System.getProperty("user.home")
        val downloads = File(userHome, "Downloads")
        val exportDir = File(downloads, "LocalTavern/ExportedCharacters")
        if (!exportDir.exists()) exportDir.mkdirs()
        
        val file = File(exportDir, fileName)
        file.writeBytes(bytes)
        file.absolutePath
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

actual fun openDirectory(path: String) {
    try {
        val file = File(path)
        val directory = if (file.isDirectory) file else file.parentFile
        if (directory != null && directory.exists()) {
            Desktop.getDesktop().open(directory)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}