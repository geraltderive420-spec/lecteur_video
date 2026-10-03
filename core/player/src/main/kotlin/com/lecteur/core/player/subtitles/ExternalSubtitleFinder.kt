package com.lecteur.core.player.subtitles

import com.lecteur.core.player.tracks.LanguageCodes
import java.util.Locale

/**
 * External subtitle formats. [mimeType] is null (and [isRenderable] false) for formats Media3 cannot decode:
 * VOBSUB (.sub/.idx) is listed so the user knows the file was seen, but it cannot be displayed.
 */
enum class SubtitleFormat(val extension: String, val mimeType: String?) {
    SRT("srt", "application/x-subrip"),
    ASS("ass", "text/x-ssa"),
    SSA("ssa", "text/x-ssa"),
    VTT("vtt", "text/vtt"),
    SUP("sup", "application/pgs"),
    VOBSUB_IDX("idx", null),
    VOBSUB_SUB("sub", null);

    val isRenderable: Boolean get() = mimeType != null

    companion object {
        fun fromFileName(name: String): SubtitleFormat? {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return entries.firstOrNull { it.extension == ext }
        }
    }
}

data class ExternalSubtitle(
    val fileName: String,
    val format: SubtitleFormat,
    val language: String?,
    val isForced: Boolean
)

object ExternalSubtitleFinder {

    private val separators = Regex("[._\\-\\s\\[\\]()]+")
    private val baseSeparators = setOf('.', '_', '-', ' ', '[', '(')

    /** Subtitle files among [siblingNames] that belong to [videoFileName] (same base name, optional `.fr.forced` style suffix). */
    fun find(videoFileName: String, siblingNames: List<String>): List<ExternalSubtitle> {
        val videoBase = baseName(videoFileName).lowercase(Locale.ROOT)
        if (videoBase.isEmpty()) return emptyList()

        val found = siblingNames.mapNotNull { name ->
            val format = SubtitleFormat.fromFileName(name) ?: return@mapNotNull null
            val base = baseName(name)
            val lower = base.lowercase(Locale.ROOT)
            val suffix = when {
                lower == videoBase -> ""
                lower.startsWith(videoBase) && lower[videoBase.length] in baseSeparators -> base.substring(videoBase.length)
                else -> return@mapNotNull null
            }
            val tokens = suffix.split(separators).filter { it.isNotEmpty() }.map { it.lowercase(Locale.ROOT) }
            ExternalSubtitle(
                fileName = name,
                format = format,
                language = tokens.firstOrNull { LanguageCodes.isKnownCode(it) }?.let { LanguageCodes.normalize(it) },
                isForced = "forced" in tokens || "forcé" in tokens || "forces" in tokens
            )
        }

        // A VobSub pair is one subtitle: keep the .idx entry only.
        val idxBases = found.filter { it.format == SubtitleFormat.VOBSUB_IDX }.map { baseName(it.fileName).lowercase(Locale.ROOT) }.toSet()
        return found
            .filterNot { it.format == SubtitleFormat.VOBSUB_SUB && baseName(it.fileName).lowercase(Locale.ROOT) in idxBases }
            .sortedWith(compareBy({ it.language == null }, { it.language }, { it.isForced }, { it.fileName }))
    }

    private fun baseName(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) fileName.substring(0, dot) else fileName
    }
}
