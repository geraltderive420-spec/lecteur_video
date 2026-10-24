package com.lecteur.desktop.background

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.File

/**
 * "Démarrer avec Windows", the way Plex Media Server and Jellyfin's tray do it: a value under the user's Run key that
 * starts the app at logon in the notification area, without its window, so the library is served as soon as the PC is
 * on. Per user, no administrator rights, nothing to install as a Windows service.
 */
object Autostart {
    const val BACKGROUND_ARG = "--background"

    private val ROOT = WinReg.HKEY_CURRENT_USER
    private const val KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val VALUE = "LecteurMedia"

    /** The installed launcher (set by jpackage's launcher); null when run from Gradle: nothing worth registering then. */
    val launcher: File? get() = System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.isFile }

    val available: Boolean get() = launcher != null

    private val command: String? get() = launcher?.let { "\"${it.absolutePath}\" $BACKGROUND_ARG" }

    fun isEnabled(): Boolean = registered() != null

    /** Returns the new state (unchanged when the registry refused). */
    fun set(enabled: Boolean): Boolean {
        runCatching {
            if (enabled) command?.let { Advapi32Util.registrySetStringValue(ROOT, KEY, VALUE, it) }
            else if (Advapi32Util.registryValueExists(ROOT, KEY, VALUE)) Advapi32Util.registryDeleteValue(ROOT, KEY, VALUE)
        }
        return isEnabled()
    }

    /** At each start: an install moved to another folder (or a new version's launcher) keeps starting with Windows. */
    fun refresh() {
        val current = registered() ?: return
        val wanted = command ?: return
        if (current != wanted) runCatching { Advapi32Util.registrySetStringValue(ROOT, KEY, VALUE, wanted) }
    }

    private fun registered(): String? = runCatching {
        if (Advapi32Util.registryValueExists(ROOT, KEY, VALUE)) Advapi32Util.registryGetStringValue(ROOT, KEY, VALUE) else null
    }.getOrNull()
}
