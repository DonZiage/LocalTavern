package chat.donzi.localtavern.qr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

// Full-screen camera scanner that reads the pairing QR code shown by the host
// device. Returns the scanned text in the activity result under RESULT_TEXT.
// Runs on its own activity so the Compose UI never has to handle the camera.
class QrScannerActivity : ComponentActivity() {

    private var analyzerInFlight = false
    private lateinit var previewView: PreviewView

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission is needed to scan the pairing QR code.", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        setContentView(previewView)

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
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
