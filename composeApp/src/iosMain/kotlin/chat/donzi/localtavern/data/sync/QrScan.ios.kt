package chat.donzi.localtavern.data.sync

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIColor
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create

actual val supportsQrScanning: Boolean = true

private class QrScanDelegate(
    private val onFound: (String) -> Unit
) : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {
    override fun captureOutput(output: AVCaptureOutput, didOutputMetadataObjects: List<*>, fromConnection: AVCaptureConnection) {
        for (obj in didOutputMetadataObjects) {
            val code = obj as? AVMetadataMachineReadableCodeObject ?: continue
            val value = code.stringValue ?: continue
            if (value.startsWith("localtavern://pair")) {
                onFound(value)
                break
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class QrScanViewController(
    private val onResult: (String?) -> Unit
) : UIViewController(nibName = null, bundle = null) {

    private var session: AVCaptureSession? = null
    private var delegate: QrScanDelegate? = null
    private var previewLayer: AVCaptureVideoPreviewLayer? = null

    override fun viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor.blackColor

        val session = AVCaptureSession()
        this.session = session

        val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
        if (device == null) {
            finishWith(null)
            return
        }
        val input = AVCaptureDeviceInput.deviceInputWithDevice(device, null)
        if (input == null || !session.canAddInput(input)) {
            finishWith(null)
            return
        }
        session.addInput(input)

        val output = AVCaptureMetadataOutput()
        if (!session.canAddOutput(output)) {
            finishWith(null)
            return
        }
        session.addOutput(output)
        output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)

        val scanDelegate = QrScanDelegate { text ->
            finishWith(text)
        }
        delegate = scanDelegate
        val queue = dispatch_queue_create("chat.donzi.localtavern.qrscan", null)
        output.setMetadataObjectsDelegate(scanDelegate, queue)

        val layer = AVCaptureVideoPreviewLayer(session = session)
        layer.videoGravity = AVLayerVideoGravityResizeAspectFill
        layer.frame = view.bounds
        previewLayer = layer
        view.layer.addSublayer(layer)

        session.startRunning()
    }

    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    private fun finishWith(text: String?) {
        session?.stopRunning()
        dispatch_async(dispatch_get_main_queue()) {
            onResult(text)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun launchQrScanner(onResult: (String?) -> Unit) {
    val window = UIApplication.sharedApplication.windows
        .firstOrNull { (it as? UIWindow)?.isKeyWindow() == true } as? UIWindow
    val root = window?.rootViewController
    if (root == null) {
        onResult(null)
        return
    }
    var dismissed = false
    val scanner = QrScanViewController { text ->
        if (dismissed) return@QrScanViewController
        dismissed = true
        root.dismissViewControllerAnimated(true) {
            onResult(text)
        }
    }
    root.presentViewController(scanner, animated = true, completion = null)
}
