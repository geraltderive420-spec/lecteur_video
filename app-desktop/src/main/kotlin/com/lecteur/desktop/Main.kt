package com.lecteur.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.window.application
import com.lecteur.desktop.ui.AppController
import com.lecteur.desktop.ui.LecteurApp
import com.lecteur.desktop.data.AppDirs
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption
import javax.swing.JOptionPane
import javax.swing.UIManager

fun main() {
    // No background erase of the video canvas by Windows (flicker over libVLC's picture)
    System.setProperty("sun.awt.noerasebackground", "true")
    // Native look for the file and folder pickers
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    System.setProperty("skiko.renderApi", System.getProperty("skiko.renderApi") ?: "DIRECT3D")

    // One copy at a time: two would each rewrite the settings from memory (a paired device erased by the other) and
    // serve the library on two ports
    if (!SingleInstance.acquire()) {
        JOptionPane.showMessageDialog(null, "Lecteur Média est déjà ouvert.", "Lecteur Média", JOptionPane.INFORMATION_MESSAGE)
        return
    }

    val services = AppServices()
    if (services.settings.value.receiverEnabled) services.receiver.start()
    if (services.settings.value.shareEnabled) services.share.start()
    if (services.settings.value.scanOnStart) services.scanner.start()
    // Periodic rescan while the app is open (new episodes downloaded overnight...)
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

    application(exitProcessOnExit = true) {
        val app = remember { AppController(services) }
        val icon = remember { runCatching { BitmapPainter(useResource("icon.png", ::loadImageBitmap)) }.getOrNull() }
        LecteurApp(app, icon)
    }
}

/** A lock on a file of the data folder, held until the process ends (released by the system even after a crash). */
private object SingleInstance {
    private var lock: FileLock? = null

    fun acquire(): Boolean {
        val channel = runCatching { FileChannel.open(File(AppDirs.root, "app.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE) }
            .getOrElse { return true }
        lock = runCatching { channel.tryLock() }.getOrNull()
        if (lock == null) channel.close()
        return lock != null
    }
}
