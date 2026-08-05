package chat.donzi.localtavern.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIWindow
import platform.UniformTypeIdentifiers.UTTypeArchive
import platform.UniformTypeIdentifiers.UTTypeData
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.UniformTypeIdentifiers.UTTypeJSON
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@Composable
actual fun rememberCharacterCardPickerLauncher(
    onPicked: (List<PickedFile>) -> Unit
): () -> Unit {
    // The delegate is created once but must always invoke the latest callback
    // (the callers may close over changing state).
    val currentOnPicked by rememberUpdatedState(onPicked)

    val delegate = remember {
        object : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(
                controller: UIDocumentPickerViewController,
                didPickDocumentsAtURLs: List<*>
            ) {
                val urls = didPickDocumentsAtURLs.filterIsInstance<NSURL>()
                if (urls.isEmpty()) return

                dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0uL)) {
                    val picked = urls.mapNotNull { url ->
                        val data = NSData.dataWithContentsOfURL(url)
                        if (data == null || data.length == 0uL) {
                            null
                        } else {
                            PickedFile(url.lastPathComponent ?: "card", data.toByteArray())
                        }
                    }
                    dispatch_async(dispatch_get_main_queue()) {
                        if (picked.isNotEmpty()) {
                            currentOnPicked(picked)
                        }
                    }
                }
            }
        }
    }

    // UIKit does not retain the picker while it is presented; keep a strong
    // reference so it cannot be deallocated mid-presentation.
    var pickerReference by remember { mutableStateOf<UIDocumentPickerViewController?>(null) }

    return remember {
        {
            val picker = UIDocumentPickerViewController(
                forOpeningContentTypes = listOf(
                    UTTypeImage,
                    UTTypeJSON,
                    UTTypeArchive,
                    UTTypeData
                ),
                asCopy = true
            )
            picker.allowsMultipleSelection = true
            picker.delegate = delegate
            pickerReference = picker

            // keyWindow is deprecated (iOS 13+) and can be nil on multi-scene
            // setups; fall back to the last window's root view controller.
            val windows = UIApplication.sharedApplication.windows.filterIsInstance<UIWindow>()
            val rootViewController = windows.firstOrNull { it.isKeyWindow() }
                ?.rootViewController
                ?: windows.lastOrNull()?.rootViewController
            rootViewController?.presentViewController(
                picker,
                animated = true,
                completion = null
            )
        }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@Composable
actual fun rememberLorebookPickerLauncher(
    onPicked: (List<PickedFile>) -> Unit
): () -> Unit {
    val currentOnPicked by rememberUpdatedState(onPicked)

    val delegate = remember {
        object : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(
                controller: UIDocumentPickerViewController,
                didPickDocumentsAtURLs: List<*>
            ) {
                val url = didPickDocumentsAtURLs.filterIsInstance<NSURL>().firstOrNull()
                    ?: return

                dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0uL)) {
                    val data = NSData.dataWithContentsOfURL(url)
                    val picked = if (data == null || data.length == 0uL) {
                        null
                    } else {
                        PickedFile(url.lastPathComponent ?: "lorebook.json", data.toByteArray())
                    }
                    dispatch_async(dispatch_get_main_queue()) {
                        if (picked != null) {
                            currentOnPicked(listOf(picked))
                        }
                    }
                }
            }
        }
    }

    var pickerReference by remember { mutableStateOf<UIDocumentPickerViewController?>(null) }

    return remember {
        {
            val picker = UIDocumentPickerViewController(
                forOpeningContentTypes = listOf(UTTypeJSON, UTTypeData),
                asCopy = true
            )
            picker.allowsMultipleSelection = false
            picker.delegate = delegate
            pickerReference = picker

            val windows = UIApplication.sharedApplication.windows.filterIsInstance<UIWindow>()
            val rootViewController = windows.firstOrNull { it.isKeyWindow() }
                ?.rootViewController
                ?: windows.lastOrNull()?.rootViewController
            rootViewController?.presentViewController(
                picker,
                animated = true,
                completion = null
            )
        }
    }
}
