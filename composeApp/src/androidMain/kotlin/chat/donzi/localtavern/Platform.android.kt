package chat.donzi.localtavern

import android.os.Build
import android.os.Environment
import java.io.File
import android.content.Intent
import android.content.Context
import androidx.core.content.FileProvider
import android.media.MediaScannerConnection
import androidx.core.net.toUri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

object AndroidAppContext {
    private var applicationContext: Context? = null

    fun setContext(context: Context) {
        if (applicationContext == null) {
            applicationContext = context.applicationContext
        }
    }

    fun getContext(): Context? = applicationContext
}

actual fun saveFile(fileName: String, bytes: ByteArray): String? {
    val context = AndroidAppContext.getContext() ?: return null
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                val mimeType = if (fileName.endsWith(".json", ignoreCase = true)) "application/json" else "image/png"
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/LocalTavern/ExportedCharacters")
            }
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues) ?: return null
            val outputStream = resolver.openOutputStream(uri)
            if (outputStream == null) {
                resolver.delete(uri, null, null)
                return null
            }
            outputStream.use { output -> output.write(bytes) }
            uri.toString()
        } else {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val exportDir = File(downloads, "LocalTavern/ExportedCharacters")
            if (!exportDir.exists()) exportDir.mkdirs()

            val file = File(exportDir, fileName)
            file.writeBytes(bytes)

            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
            file.absolutePath
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

actual fun openDirectory(path: String) {
    val context = AndroidAppContext.getContext() ?: return

    val mimeType = when {
        path.startsWith("content://") ->
            context.contentResolver.getType(path.toUri()) ?: "image/png"
        path.endsWith(".json", ignoreCase = true) -> "application/json"
        else -> "image/png"
    }

    if (path.startsWith("content://")) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(path.toUri(), mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val file = File(path)
    if (!file.exists()) return

    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()

        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path.toUri(), "*/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e2: Exception) {
            e2.printStackTrace()
        }
    }
}

actual fun convertToPng(bytes: ByteArray): ByteArray {
    return try {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        outputStream.toByteArray()
    } catch (e: Exception) {
        e.printStackTrace()
        bytes
    }
}

actual val isDesktop: Boolean = false