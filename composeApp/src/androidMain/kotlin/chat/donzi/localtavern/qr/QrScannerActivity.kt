package chat.donzi.localtavern.qr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage

// Full-screen camera scanner that reads the pairing QR code shown by the host
// device. Returns the scanned text in the activity result under RESULT_TEXT.
// Runs on its own activity so the Compose UI never has to handle the camera.
//
// The camera preview carries a viewfinder overlay: a dimmed scrim with a
// clear square in the middle, corner brackets marking it, an instruction
// line and a Cancel button — the user always knows what to aim at and how
// to get out.
class QrScannerActivity : ComponentActivity() {

    private var analyzerInFlight = false
    private var previewView: PreviewView? = null
    private var cameraStarted = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                maybeStartCamera()
            } else {
                Toast.makeText(this, "Camera permission is needed to scan the pairing QR code.", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            QrScannerScreen(
                onPreviewViewCreated = { view ->
                    previewView = view
                    maybeStartCamera()
                },
                onCancel = { finish() }
            )
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            maybeStartCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Starts the camera once BOTH the permission and the PreviewView exist:
    // whichever arrives second triggers it, so neither order can strand the
    // preview on a black screen.
    private fun maybeStartCamera() {
        if (cameraStarted || previewView == null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        cameraStarted = true
        startCamera()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView?.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val scanner = BarcodeScanning.getClient()
            analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                if (analyzerInFlight) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                val mediaImage = imageProxy.image
                if (mediaImage == null) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                analyzerInFlight = true
                val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                scanner.process(inputImage)
                    .addOnSuccessListener { barcodes ->
                        for (barcode in barcodes) {
                            val raw = barcode.rawValue ?: continue
                            if (raw.startsWith("localtavern://pair")) {
                                finishWithResult(raw)
                                break
                            }
                        }
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                        analyzerInFlight = false
                    }
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Could not open the camera: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun finishWithResult(text: String) {
        setResult(RESULT_OK, Intent().putExtra(RESULT_TEXT, text))
        finish()
    }

    companion object {
        const val RESULT_TEXT = "scan_result_text"
    }
}

@Composable
private fun QrScannerScreen(
    onPreviewViewCreated: (PreviewView) -> Unit,
    onCancel: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                PreviewView(context).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    onPreviewViewCreated(this)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        QrScanOverlay()
        Text(
            text = "Align the QR code inside the frame",
            color = Color.White,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp)
        )
        Button(
            onClick = onCancel,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)
        ) {
            Text("Cancel")
        }
    }
}

// Dimmed scrim with a clear square viewfinder and white corner brackets.
// The square is 72% of the screen's smaller dimension, centered a little
// above the middle so the Cancel button and the instruction line stay clear
// of it.
@Composable
private fun QrScanOverlay() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val side = size.minDimension * 0.72f
        val left = (size.width - side) / 2f
        val top = size.height * 0.42f - side / 2f
        val rect = Rect(left, top, left + side, top + side)

        val scrim = Color.Black.copy(alpha = 0.55f)
        drawRect(scrim, topLeft = Offset.Zero, size = Size(size.width, rect.top))
        drawRect(scrim, topLeft = Offset(0f, rect.bottom), size = Size(size.width, size.height - rect.bottom))
        drawRect(scrim, topLeft = Offset(0f, rect.top), size = Size(rect.left, side))
        drawRect(scrim, topLeft = Offset(rect.right, rect.top), size = Size(size.width - rect.right, side))

        val arm = 28.dp.toPx()
        val stroke = 4.dp.toPx()
        val bracket = Color.White
        // Top-left
        drawLine(bracket, Offset(rect.left, rect.top + arm), Offset(rect.left, rect.top), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(bracket, Offset(rect.left, rect.top), Offset(rect.left + arm, rect.top), strokeWidth = stroke, cap = StrokeCap.Round)
        // Top-right
        drawLine(bracket, Offset(rect.right - arm, rect.top), Offset(rect.right, rect.top), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(bracket, Offset(rect.right, rect.top), Offset(rect.right, rect.top + arm), strokeWidth = stroke, cap = StrokeCap.Round)
        // Bottom-left
        drawLine(bracket, Offset(rect.left, rect.bottom - arm), Offset(rect.left, rect.bottom), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(bracket, Offset(rect.left, rect.bottom), Offset(rect.left + arm, rect.bottom), strokeWidth = stroke, cap = StrokeCap.Round)
        // Bottom-right
        drawLine(bracket, Offset(rect.right, rect.bottom - arm), Offset(rect.right, rect.bottom), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(bracket, Offset(rect.right, rect.bottom), Offset(rect.right - arm, rect.bottom), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}
