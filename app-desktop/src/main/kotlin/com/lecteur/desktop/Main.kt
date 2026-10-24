package com.lecteur.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.window.application
import com.lecteur.desktop.background.Autostart
import com.lecteur.desktop.background.SingleInstance
import com.lecteur.desktop.ui.AppController
import com.lecteur.desktop.ui.LecteurApp
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.UIManager

fun main(args: Array<String>) {
    // Started by Windows at logon (see Autostart): the server runs in the notification area, no window
    val background = Autostart.BACKGROUND_ARG in args

    // No background erase of the video canvas by Windows (flicker over libVLC's picture)
    System.setProperty("sun.awt.noerasebackground", "true")
    // Native look for the file and folder pickers
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    System.setProperty("skiko.renderApi", System.getProperty("skiko.renderApi") ?: "DIRECT3D")

    if (!SingleInstance.acquire()) {
        // Already running (often in the notification area): bring its window up. A second logon start stays silent.
        if (!background && !SingleInstance.requestShow()) {
            JOptionPane.showMessageDialog(null, "Lecteur Média est déjà ouvert.", "Lecteur Média", JOptionPane.INFORMATION_MESSAGE)
        }
        return
    }

    val services = AppServices()
    if (services.settings.value.receiverEnabled) services.receiver.start()
    if (services.settings.value.shareEnabled) services.share.start()
    if (services.settings.value.scanOnStart) services.scanner.start()
    Autostart.refresh()
    // Periodic rescan while the app runs, window open or not (new episodes downloaded overnight...)
    Thread({
        var last = System.currentTimeMillis()
        while (true) {
            Thread.sleep(60_000)
            val hours = services.settings.value.periodicScanHours
            if (hours > 0 && System.currentTimeMillis() - last >= hours * 3_600_000L) {
                last = System.currentTimeMillis()
                services.scanner.start()
            }
        }
    }, "periodic-scan").apply { isDaemon = true }.start()
    Runtime.getRuntime().addShutdownHook(Thread { services.shutdown() })

    val app = AppController(services)
    app.mainWindowOpen = !background
    SingleInstance.listen { SwingUtilities.invokeLater { app.showMainWindow() } }

    application(exitProcessOnExit = true) {
        val icon = remember { runCatching { BitmapPainter(useResource("icon.png", ::loadImageBitmap)) }.getOrNull() }
        LecteurApp(app, icon)
    }
}
