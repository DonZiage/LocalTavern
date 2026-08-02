package chat.donzi.localtavern.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerConfigurationSelectionOrdered
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.Foundation.NSData
import platform.Foundation.getBytes
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_get_global_queue
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import kotlinx.cinterop.BetaInteropApi
import platform.UniformTypeIdentifiers.UTTypeImage
import chat.donzi.localtavern.utils.ImageSanitizer

@OptIn(ExperimentalForeignApi::class)
fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val byteArray = ByteArray(size)
    if (size > 0) {
        byteArray.usePinned { pinned ->
            getBytes(pinned.addressOf(0), length)
        }
    }
    return byteArray
}

@OptIn(BetaInteropApi::class)
@Composable
actual fun rememberImagePickerLauncher(
    onImagesPicked: (List<ByteArray>) -> Unit,
    preserveOriginal: Boolean
): () -> Unit {
    // The delegate is created once but must always invoke the latest callback.
    val currentOnImagesPicked by rememberUpdatedState(onImagesPicked)

    fun deliverSanitized(imageList: List<ByteArray>) {
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0)) {
            val sanitized = ImageSanitizer.sanitize(imageList, preserveOriginal)
            dispatch_async(dispatch_get_main_queue()) {
                currentOnImagesPicked(sanitized)
            }
        }
    }

    val delegate = remember {
        object : NSObject(), PHPickerViewControllerDelegateProtocol {
            override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
                picker.dismissViewControllerAnimated(true, null)
                if (didFinishPicking.isEmpty()) return

                val results = didFinishPicking.filterIsInstance<PHPickerResult>()
                if (results.isEmpty()) return

                val imageList = mutableListOf<ByteArray>()
                var remaining = results.size

                results.forEach { result ->
                    val itemProvider = result.itemProvider
                    if (itemProvider.hasItemConformingToTypeIdentifier(UTTypeImage.identifier)) {
                        itemProvider.loadDataRepresentationForTypeIdentifier(UTTypeImage.identifier) { data, _ ->
                            dispatch_async(dispatch_get_main_queue()) {
                                if (data != null) {
                                    imageList.add(data.toByteArray())
                                }
                                remaining--
                                if (remaining == 0) {
                                    deliverSanitized(imageList)
                                }
                            }
                        }
                    } else {
                        dispatch_async(dispatch_get_main_queue()) {
                            remaining--
                            if (remaining == 0) {
                                deliverSanitized(imageList)
                            }
                        }
                    }
                }
            }
        }
    }

    return remember {
        {
            val configuration = PHPickerConfiguration()
            configuration.filter = PHPickerFilter.imagesFilter
            configuration.selectionLimit = 0
            configuration.selection = PHPickerConfigurationSelectionOrdered

            val picker = PHPickerViewController(configuration)
            picker.delegate = delegate

            val rootViewController = UIApplication.sharedApplication.keyWindow?.rootViewController
            rootViewController?.presentViewController(
                picker,
                animated = true,
                completion = null
            )
        }
    }
}