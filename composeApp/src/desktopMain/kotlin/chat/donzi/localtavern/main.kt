package chat.donzi.localtavern

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import chat.donzi.localtavern.data.database.DriverFactory
import chat.donzi.localtavern.ui.App

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "LocalTavern"
    ) {
        val driverFactory = DriverFactory()

        App(driverFactory)
    }
}