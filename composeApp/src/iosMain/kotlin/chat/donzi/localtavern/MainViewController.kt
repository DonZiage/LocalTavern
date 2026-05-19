package chat.donzi.localtavern

import androidx.compose.ui.window.ComposeUIViewController
import chat.donzi.localtavern.ui.App
import chat.donzi.localtavern.data.database.DriverFactory

@Suppress("unused")
fun MainViewController() = ComposeUIViewController {

    App(driverFactory = DriverFactory())
}