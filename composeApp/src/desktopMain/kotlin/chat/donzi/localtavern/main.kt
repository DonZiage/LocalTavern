package chat.donzi.localtavern

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import chat.donzi.localtavern.database.DriverFactory

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "LocalTavern"
    ) {
        // Creates the Desktop database driver
        val driverFactory = DriverFactory()

        App(driverFactory)
    }
}