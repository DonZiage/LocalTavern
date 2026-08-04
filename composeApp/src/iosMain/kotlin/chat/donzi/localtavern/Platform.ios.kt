package chat.donzi.localtavern

import platform.Foundation.*
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentInteractionController
import platform.UIKit.UIWindow
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import platform.CoreGraphics.CGRectMake
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.ExperimentalForeignApi
import org.jetbrains.skia.Image
import org.jetbrains.skia.EncodedImageFormat

// UIDocumentInteractionController is not retained by the system while the
// options menu is on screen; keep a strong reference so it cannot be
// deallocated (which would dismiss the menu or crash).
private var documentInteractionController: UIDocumentInteractionController? = null

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

@OptIn(ExperimentalForeignApi::class)
actual fun openDirectory(path: String) {
    val fileManager = NSFileManager.defaultManager
    if (!fileManager.fileExistsAtPath(path)) return

    // canOpenURL() rejects file:// URLs and "shareddocuments://" is not a
    // registered scheme, so opening the Files app directly is not possible.
    // Present the document's options menu (Copy / Save to Files / share)
    // instead, anchored to the root view controller.
    val fileURL = NSURL.fileURLWithPath(path)
    documentInteractionController = UIDocumentInteractionController.interactionControllerWithURL(fileURL)

    // keyWindow is deprecated (iOS 13+) and can be nil on multi-scene setups.
    val windows = UIApplication.sharedApplication.windows.filterIsInstance<UIWindow>()
    val rootViewController = windows.firstOrNull { it.isKeyWindow() }
        ?.rootViewController
        ?: windows.lastOrNull()?.rootViewController
    rootViewController?.let { vc ->
        documentInteractionController?.presentOptionsMenuFromRect(CGRectMake(0.0, 0.0, 0.0, 0.0), inView = vc.view, animated = true)
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

actual val isAndroid: Boolean = false

actual fun openDeviceSecuritySettings() = Unit

actual fun appVersionName(): String {
    val info = NSBundle.mainBundle.infoDictionary
    return (info?.objectForKey("CFBundleShortVersionString") as? String) ?: "dev build"
}

actual fun appDatabasePath(): String {
    // The NativeSqliteDriver places the file in the app sandbox Documents
    // directory ("Documents/localtavern.db").
    val fileManager = NSFileManager.defaultManager
    val documentsDir = fileManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask).first() as? NSURL
    return documentsDir?.URLByAppendingPathComponent("localtavern.db")?.path ?: ""
}