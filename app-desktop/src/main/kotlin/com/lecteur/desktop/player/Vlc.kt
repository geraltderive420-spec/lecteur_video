package com.lecteur.desktop.player

import com.sun.jna.NativeLibrary
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import java.io.File

/**
 * libVLC bootstrap. The app ships its own copy of libVLC (resources/vlc next to the executable), so nothing has to be
 * installed; a VLC 64-bit install is used as a fallback when running from sources without the bundle.
 */
object Vlc {
    @Volatile private var factory: MediaPlayerFactory? = null
    @Volatile var loadError: String? = null
        private set

    val bundledDir: File?
        get() = System.getProperty("compose.application.resources.dir")?.let { File(it, "vlc") }?.takeIf { File(it, "libvlc.dll").exists() }

    /** Options fixed for the whole process (libVLC reads them once). */
    /** Subtitle look, read once by libVLC's text renderer (applies from the next start of the app). */
    var subtitleOptions: List<String> = emptyList()

    private fun options(passthrough: Boolean): Array<String> = buildList {
        add("--quiet")
        add("--no-video-title-show")
        add("--no-snapshot-preview")
        add("--no-osd")
        add("--no-stats")
        add("--no-sub-autodetect-file")
        // Smooth seeking and large caches for network streams from the phone
        add("--network-caching=1500")
        add("--file-caching=600")
        // Dolby/DTS sent untouched to an amplifier (HDMI/S/PDIF)
        if (passthrough) add("--spdif")
        add("--aout=mmdevice")
        // Direct3D 11 output: GPU decoding, HDR passed to an HDR display, tone mapping otherwise
        add("--vout=direct3d11")
        add("--avcodec-hw=d3d11va")
        addAll(subtitleOptions)
    }.toTypedArray()

    @Synchronized
    fun factory(passthrough: Boolean = false): MediaPlayerFactory? {
        factory?.let { return it }
        return try {
            val dir = bundledDir
            if (dir != null) {
                NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcLibraryName(), dir.absolutePath)
                NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcCoreLibraryName(), dir.absolutePath)
                // libVLC finds its plugins in the "plugins" folder next to libvlccore.dll
                System.setProperty("jna.library.path", dir.absolutePath)
                // The plugin cache (plugins.dat) goes stale when the installer copies the files (new dates): libVLC then
                // scans every plugin at each start. Rebuild it once per install, where the folder is writable.
                val stamp = File(dir, "plugins/cache.stamp")
                val expected = "${File(dir, "libvlccore.dll").lastModified()}"
                val reset = runCatching { stamp.readText() }.getOrNull() != expected
                MediaPlayerFactory(*(options(passthrough) + if (reset) arrayOf("--reset-plugins-cache") else emptyArray())).also {
                    if (reset) runCatching { stamp.writeText(expected) }
                }
            } else {
                NativeDiscovery().discover()
                MediaPlayerFactory(NativeDiscovery(), *options(passthrough))
            }.also { factory = it }
        } catch (e: Throwable) {
            loadError = e.message ?: e.javaClass.simpleName
            null
        }
    }

    fun release() {
        factory?.release()
        factory = null
    }
}
