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

        AndroidAppContext.setContext(applicationContext)

        val driverFactory = DriverFactory(applicationContext)

        enableEdgeToEdge()

        setContent {
            App(driverFactory = DriverFactory(this))
        }
    }
}
