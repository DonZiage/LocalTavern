package chat.donzi.localtavern

import java.io.File
import java.awt.Desktop
import javax.swing.JFileChooser
import javax.swing.UIManager
import org.jetbrains.skia.Image
import org.jetbrains.skia.EncodedImageFormat

actual fun saveFile(fileName: String, bytes: ByteArray): String? {
    return try {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())

        val downloads = File(System.getProperty("user.home"), "Downloads")
        val chooser = JFileChooser().apply {
            selectedFile = File(downloads, fileName)
            dialogTitle = "Export Character"
        }

        if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) {
            return null
        }

        val file = chooser.selectedFile
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

actual fun convertToPng(bytes: ByteArray): ByteArray {
    return try {
        val skiaImage = Image.makeFromEncoded(bytes)
        val pngData = skiaImage.encodeToData(EncodedImageFormat.PNG)
        pngData?.bytes ?: bytes
    } catch (e: Exception) {
        e.printStackTrace()
        bytes
    }
}

actual val isDesktop: Boolean = true