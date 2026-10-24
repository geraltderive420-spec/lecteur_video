package com.lecteur.desktop.background

import com.lecteur.desktop.data.AppDirs
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption

/**
 * One copy at a time: two would each rewrite the settings from memory (a paired device erased by the other) and serve
 * the library on two ports. The copy running in the notification area is the server; launching the app again (Start
 * menu, desktop shortcut) only asks it to show its window, through a socket on the loopback address.
 */
object SingleInstance {
    private var lock: FileLock? = null
    private val portFile: File get() = File(AppDirs.root, "app.port")
    private const val SHOW = "show"

    /** A lock on a file of the data folder, held until the process ends (released by the system even after a crash). */
    fun acquire(): Boolean {
        val channel = runCatching { FileChannel.open(File(AppDirs.root, "app.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE) }
            .getOrElse { return true }
        lock = runCatching { channel.tryLock() }.getOrNull()
        if (lock == null) channel.close()
        return lock != null
    }

    /** In the copy holding the lock: every "show" from a later launch calls [onShow] (on this listener's thread). */
    fun listen(onShow: () -> Unit) {
        val server = runCatching { ServerSocket(0, 8, InetAddress.getLoopbackAddress()) }.getOrNull() ?: return
        runCatching { portFile.writeText(server.localPort.toString()) }
        Thread({
            while (true) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    socket.use {
                        it.soTimeout = 2_000
                        if (it.getInputStream().readNBytes(SHOW.length).decodeToString() == SHOW) onShow()
                    }
                }
            }
        }, "single-instance").apply { isDaemon = true }.start()
    }

    /** In a later launch: false when the running copy could not be reached. */
    fun requestShow(): Boolean = runCatching {
        val port = portFile.readText().trim().toInt()
        Socket().use {
            it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2_000)
            it.getOutputStream().write(SHOW.toByteArray())
        }
        true
    }.getOrDefault(false)
}
