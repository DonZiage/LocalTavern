package chat.donzi.localtavern

import android.os.Build
import android.os.Environment
import java.io.File
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()

actual fun saveFile(fileName: String, bytes: ByteArray): String? {
    return try {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
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
    // Opening a specific directory on Android is complex due to Scoped Storage.
    // We'll attempt to open the Downloads folder as a generic fallback.
    // Note: This requires context, which we don't have here easily.
    // A better approach would be to use a FileProvider or let the user know where it is.
}