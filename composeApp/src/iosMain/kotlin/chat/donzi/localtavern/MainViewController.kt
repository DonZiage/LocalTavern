package chat.donzi.localtavern

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import chat.donzi.localtavern.ui.App
import chat.donzi.localtavern.data.database.DriverFactory

@Suppress("unused")
fun MainViewController() = ComposeUIViewController {
    // Recreating the driver on every recomposition would open (and leak) a new
    // SQLite connection; build it once.
    val driverFactory = remember { DriverFactory() }

    App(driverFactory = driverFactory)
}