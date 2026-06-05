package chat.donzi.localtavern

import platform.Foundation.*
import platform.UIKit.UIApplication
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.ExperimentalForeignApi
import org.jetbrains.skia.Image
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalForeignApi::class)
actual fun saveFile(fileName: String, bytes: ByteArray): String? {
    return try {
        val fileManager = NSFileManager.defaultManager
        val documentsDir = fileManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask).first() as NSURL
        val exportDir = documentsDir.URLByAppendingPathComponent("LocalTavern/ExportedCharacters")!!

        if (!fileManager.fileExistsAtPath(exportDir.path!!)) {
            fileManager.createDirectoryAtURL(exportDir, withIntermediateDirectories = true, attributes = null, error = null)
        }

        val fileURL = exportDir.URLByAppendingPathComponent(fileName)!!
        val data = bytes.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
        }

        if (data.writeToURL(fileURL, true)) {
            fileURL.path
        } else {
            null
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

actual fun openDirectory(path: String) {
    val url = NSURL.URLWithString("shareddocuments://")!!
    if (UIApplication.sharedApplication.canOpenURL(url)) {
        UIApplication.sharedApplication.openURL(url)
    }
}

@OptIn(ExperimentalForeignApi::class)
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

actual val isDesktop: Boolean = false