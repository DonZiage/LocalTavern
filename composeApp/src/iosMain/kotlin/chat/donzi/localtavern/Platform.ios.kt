package chat.donzi.localtavern

import platform.Foundation.*
import kotlinx.cinterop.*
import platform.UIKit.UIApplication


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