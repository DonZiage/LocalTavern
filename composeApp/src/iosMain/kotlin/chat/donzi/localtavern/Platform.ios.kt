package chat.donzi.localtavern

import platform.UIKit.UIDevice
import platform.Foundation.*
import kotlinx.cinterop.*
import platform.UIKit.UIApplication

class IOSPlatform: Platform {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}

actual fun getPlatform(): Platform = IOSPlatform()

@OptIn(ExperimentalForeignApi::class)
actual fun saveFile(fileName: String, bytes: ByteArray): String? {
    return try {
        val fileManager = NSFileManager.defaultManager
        // On iOS, we use the Documents directory which is accessible via the "Files" app 
        // if UIFileSharingEnabled and LSSupportsOpeningDocumentsInPlace are set in Info.plist
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
    // On iOS, we can open the "Files" app to the specified folder if it's within our sandbox
    // and correctly configured. However, a simpler approach is opening the app's documents folder.
    val url = NSURL.URLWithString("shareddocuments://")!!
    if (UIApplication.sharedApplication.canOpenURL(url)) {
        UIApplication.sharedApplication.openURL(url)
    }
}