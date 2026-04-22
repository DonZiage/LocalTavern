package chat.donzi.localtavern

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import chat.donzi.localtavern.data.database.DriverFactory
import chat.donzi.localtavern.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // This gives the Tavern its Android "Keys" to access the local disk
        val driverFactory = DriverFactory(applicationContext)

        // Optional: Makes the status bar transparent like iOS 16
        enableEdgeToEdge()

        setContent {
            App(driverFactory)
        }
    }
}