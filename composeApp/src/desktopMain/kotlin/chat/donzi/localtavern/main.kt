package chat.donzi.localtavern

import androidx.compose.runtime.*
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import chat.donzi.localtavern.data.database.DriverFactory
import chat.donzi.localtavern.ui.App
import localtavern.composeapp.generated.resources.Res
import localtavern.composeapp.generated.resources.app_icon
import org.jetbrains.compose.resources.painterResource

fun main() = application {
    val windowState = rememberWindowState(placement = WindowPlacement.Maximized)
    Window(
        onCloseRequest = ::exitApplication,
        title = "LocalTavern",
        icon = painterResource(Res.drawable.app_icon),
        state = windowState
    ) {
        var isDarkTheme by remember { mutableStateOf(true) }

        DisposableEffect(window, isDarkTheme) {
            window.minimumSize = java.awt.Dimension(1200, 675)

            val awtColor = if (isDarkTheme) {
                java.awt.Color(24, 24, 28)
            } else {
                java.awt.Color(255, 255, 255)
            }
            window.background = awtColor
            window.contentPane.background = awtColor

            onDispose {}
        }

        val driverFactory = DriverFactory()

        App(driverFactory, onThemeChanged = { isDark -> isDarkTheme = isDark })
    }
}