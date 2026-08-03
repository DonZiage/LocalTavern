package chat.donzi.localtavern

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import chat.donzi.localtavern.data.database.DriverFactory
import chat.donzi.localtavern.data.sync.QrScanResultBridge
import chat.donzi.localtavern.ui.App

class MainActivity : ComponentActivity() {

    private val qrScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        QrScanResultBridge.deliver(QrScanResultBridge.REQUEST_QR_SCAN, result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AndroidAppContext.setContext(applicationContext)
        AndroidAppContext.setActivity(this)
        QrScanResultBridge.attach(qrScanLauncher)

        val driverFactory = DriverFactory(this)

        enableEdgeToEdge()

        setContent {
            App(driverFactory = driverFactory)
        }
    }
}
