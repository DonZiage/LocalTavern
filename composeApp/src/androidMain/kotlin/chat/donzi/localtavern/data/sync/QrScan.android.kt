package chat.donzi.localtavern.data.sync

import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import chat.donzi.localtavern.AndroidAppContext
import chat.donzi.localtavern.qr.QrScannerActivity

// Result bridge between MainActivity's activity-result launcher and the
// common code: launchQrScanner() stashes the callback, starts the scanner
// activity, and MainActivity delivers the result through
// QrScanResultBridge.deliver().
object QrScanResultBridge {
    internal const val REQUEST_QR_SCAN = 7001

    private var pendingCallback: ((String?) -> Unit)? = null
    private var launcher: ActivityResultLauncher<Intent>? = null

    @Synchronized
    fun attach(launcher: ActivityResultLauncher<Intent>) {
        this.launcher = launcher
    }

    @Synchronized
    fun launch(callback: (String?) -> Unit): Boolean {
        val activity = AndroidAppContext.getActivity() ?: return false
        val launcher = launcher ?: return false
        pendingCallback = callback
        launcher.launch(Intent(activity, QrScannerActivity::class.java))
        return true
    }

    @Synchronized
    fun deliver(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_QR_SCAN) return
        val callback = pendingCallback
        pendingCallback = null
        if (callback == null) return
        val text = if (resultCode == android.app.Activity.RESULT_OK) {
            data?.getStringExtra(QrScannerActivity.RESULT_TEXT)
        } else {
            null
        }
        callback(text)
    }
}

actual val supportsQrScanning: Boolean = true

actual fun launchQrScanner(onResult: (String?) -> Unit) {
    if (!QrScanResultBridge.launch(onResult)) {
        onResult(null)
    }
}
