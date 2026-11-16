package chat.donzi.localtavern.data.sync

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
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
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.QuartzCore.CAShapeLayer
import platform.QuartzCore.kCALineCapRound
import platform.UIKit.UIAction
import platform.UIKit.UIApplication
import platform.UIKit.UIButton
import platform.UIKit.UIButtonTypeSystem
import platform.UIKit.UIColor
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UIFont
import platform.UIKit.UILabel
import platform.UIKit.UITextAlignmentCenter
import platform.UIKit.UIView
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIBezierPath
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import kotlin.math.min

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

    // Viewfinder overlay pieces (dimmed scrim, corner brackets, instruction
    // and Cancel button), laid out in viewDidLayoutSubviews.
    private var overlayView: UIView? = null
    private var dimViews: List<UIView> = emptyList()
    private var bracketLayers: List<CAShapeLayer> = emptyList()
    private var instructionLabel: UILabel? = null
    private var cancelButton: UIButton? = null

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

        setupOverlay()
        session.startRunning()
    }

    // Dimmed scrim with a clear square viewfinder and white corner brackets,
    // an instruction line and a Cancel button — the user always knows what
    // to aim at and how to get out. (The Cancel button is new: previously
    // the scanner could only be left by scanning a code.)
    private fun setupOverlay() {
        val overlay = UIView()
        overlay.backgroundColor = UIColor.clearColor
        view.addSubview(overlay)
        overlayView = overlay

        val dimColor = UIColor.blackColor.colorWithAlphaComponent(0.55)
        dimViews = (0 until 4).map {
            UIView().also {
                it.backgroundColor = dimColor
                overlay.addSubview(it)
            }
        }
        bracketLayers = (0 until 4).map {
            CAShapeLayer().also {
                it.strokeColor = UIColor.whiteColor.CGColor
                it.fillColor = UIColor.clearColor.CGColor
                it.lineWidth = 4.0
                it.lineCap = kCALineCapRound
                overlay.layer.addSublayer(it)
            }
        }
        instructionLabel = UILabel().also {
            it.text = "Align the QR code inside the frame"
            it.textColor = UIColor.whiteColor
            it.textAlignment = UITextAlignmentCenter
            it.font = UIFont.systemFontOfSize(17.0)
            it.numberOfLines = 0
            overlay.addSubview(it)
        }
        cancelButton = UIButton.buttonWithType(UIButtonTypeSystem).also {
            it.setTitle("Cancel", forState = UIControlStateNormal)
            it.setTitleColor(UIColor.whiteColor, forState = UIControlStateNormal)
            it.titleLabel?.font = UIFont.systemFontOfSize(17.0)
            // UIAction carries a closure, so no ObjC selector is needed.
            // The ObjC initializer is a factory method, not a constructor.
            val cancelAction = UIAction.actionWithTitle(title = "", image = null, identifier = null) { _ ->
                finishWith(null)
            }
            it.addAction(cancelAction, forControlEvents = UIControlEventTouchUpInside)
            overlay.addSubview(it)
        }
    }

    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
        layoutOverlay()
    }

    // The square is 72% of the screen's smaller dimension, centered a
    // little above the middle so the Cancel button and the instruction line
    // stay clear of it.
    private fun layoutOverlay() {
        val overlay = overlayView ?: return
        // C struct fields are read through useContents; direct
        // `bounds.size.width` does not resolve in Kotlin/Native.
        val (width, height) = view.bounds.useContents { size.width to size.height }
        // UIView.frame has no setter in the Kotlin/Native UIKit bindings
        // (assigning it fails with "val cannot be reassigned"); the
        // layer's frame is settable and is the same rectangle for a
        // view with no transform, so layout goes through the layer.
        // UIView.layer is nullable in the bindings, hence the safe call.
        overlay.layer?.frame = CGRectMake(0.0, 0.0, width, height)
        val side = min(width, height) * 0.72
        val left = (width - side) / 2.0
        val top = height * 0.42 - side / 2.0
        val right = left + side
        val bottom = top + side

        dimViews.getOrNull(0)?.layer?.frame = CGRectMake(0.0, 0.0, width, top)
        dimViews.getOrNull(1)?.layer?.frame = CGRectMake(0.0, bottom, width, height - bottom)
        dimViews.getOrNull(2)?.layer?.frame = CGRectMake(0.0, top, left, side)
        dimViews.getOrNull(3)?.layer?.frame = CGRectMake(right, top, width - right, side)

        val arm = 28.0
        val corners = listOf(
            Triple(CGPointMake(left, top + arm), CGPointMake(left, top), CGPointMake(left + arm, top)),
            Triple(CGPointMake(right - arm, top), CGPointMake(right, top), CGPointMake(right, top + arm)),
            Triple(CGPointMake(left, bottom - arm), CGPointMake(left, bottom), CGPointMake(left + arm, bottom)),
            Triple(CGPointMake(right - arm, bottom), CGPointMake(right, bottom), CGPointMake(right, bottom - arm))
        )
        corners.forEachIndexed { index, (start, corner, end) ->
            val path = UIBezierPath()
            path.moveToPoint(start)
            path.addLineToPoint(corner)
            path.addLineToPoint(end)
            bracketLayers.getOrNull(index)?.path = path.CGPath
        }

        instructionLabel?.layer?.frame = CGRectMake(16.0, 56.0, width - 32.0, 24.0)
        cancelButton?.layer?.frame = CGRectMake((width - 120.0) / 2.0, height - 88.0, 120.0, 44.0)
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
