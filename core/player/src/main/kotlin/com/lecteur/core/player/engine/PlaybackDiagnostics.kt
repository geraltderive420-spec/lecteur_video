package com.lecteur.core.player.engine

import android.content.Context
import android.media.MediaCodecList
import android.util.Log
import java.io.File

/**
 * Debug-build diagnostics written to a file (filesDir/diag.txt) because some vendor ROMs hide application logs.
 * Read with: adb shell run-as <package> cat files/diag.txt
 */
internal object PlaybackDiagnostics {
    private var file: File? = null

    fun init(context: Context) {
        if (file != null) return
        file = File(context.applicationContext.filesDir, "diag.txt").also { it.writeText("") }
        line("device decoders for video:")
        runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { t -> t.startsWith("video/") } }
                .forEach { info ->
                    info.supportedTypes.filter { it == "video/dolby-vision" || it == "video/hevc" }.forEach { type ->
                        val levels = runCatching {
                            info.getCapabilitiesForType(type).profileLevels.joinToString { "p0x%x/l0x%x".format(it.profile, it.level) }
                        }.getOrDefault("?")
                        line("  ${info.name} [$type] hw=${info.isHardwareAccelerated} sw=${info.isSoftwareOnly} $levels")
                    }
                }
        }.onFailure { line("  codec list failed: $it") }
    }

    fun line(text: String) {
        Log.w("LecteurDiag", text)
        runCatching { file?.appendText(text + "\n") }
    }
}
