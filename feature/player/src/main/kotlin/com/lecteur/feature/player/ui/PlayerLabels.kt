package com.lecteur.feature.player.ui

import com.lecteur.core.player.display.DisplayMode
import com.lecteur.core.player.engine.CodecNames
import com.lecteur.core.player.settings.DecoderMode
import com.lecteur.core.player.settings.EqualizerPreset
import com.lecteur.core.player.settings.PassthroughMode
import com.lecteur.core.player.tracks.AudioOption
import com.lecteur.core.player.tracks.LanguageCodes
import com.lecteur.core.player.tracks.SubtitleMode
import com.lecteur.core.player.tracks.SubtitleOption
import java.util.Locale

/** French labels for the track pickers and settings. */
object PlayerLabels {

    fun channels(count: Int): String = when (count) {
        0 -> ""
        1 -> "mono"
        2 -> "stéréo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "$count canaux"
    }

    fun audio(option: AudioOption, locale: Locale = Locale.FRENCH): String {
        val language = LanguageCodes.displayName(option.language, locale) ?: "Piste ${option.index + 1}"
        return listOfNotNull(
            language.replaceFirstChar { it.titlecase(locale) },
            option.codec,
            channels(option.channels).takeIf { it.isNotEmpty() },
            option.label?.takeIf { it.isNotBlank() && !it.equals(language, ignoreCase = true) }
        ).joinToString(" · ")
    }

    fun subtitle(option: SubtitleOption, locale: Locale = Locale.FRENCH): String {
        val language = LanguageCodes.displayName(option.language, locale)
            ?: option.label?.takeIf { it.isNotBlank() }
            ?: "Piste ${option.index + 1}"
        return listOfNotNull(
            language.replaceFirstChar { it.titlecase(locale) },
            option.codec,
            "forcés".takeIf { option.isForced },
            "externe".takeIf { option.isExternal }
        ).joinToString(" · ")
    }

    /** Why a track cannot be selected, shown under its name; null when it is playable. */
    fun subtitleProblem(option: SubtitleOption): String? = when {
        !option.isSupported && option.codec == "VOBSUB" -> "Format image VOBSUB non pris en charge"
        !option.isSupported -> "Format non pris en charge"
        else -> null
    }

    /** Image formats (PGS, DVB) are shown as they are: size and colour settings do not apply. */
    fun subtitleNote(option: SubtitleOption): String? =
        if (option.isSupported && (option.codec == "PGS" || option.codec == "DVB")) "Sous-titres en image : style non modifiable" else null

    fun displayMode(mode: DisplayMode): String = when (mode) {
        DisplayMode.FIT -> "Ajuster"
        DisplayMode.FILL -> "Remplir (rogner)"
        DisplayMode.STRETCH -> "Étirer"
        DisplayMode.ORIGINAL -> "Original (1:1)"
        DisplayMode.RATIO_16_9 -> "16:9"
        DisplayMode.RATIO_4_3 -> "4:3"
        DisplayMode.RATIO_21_9 -> "21:9"
    }

    fun decoderMode(mode: DecoderMode): String = when (mode) {
        DecoderMode.AUTO -> "Automatique (matériel puis logiciel)"
        DecoderMode.SOFTWARE_PREFERRED -> "Logiciel de préférence"
        DecoderMode.HARDWARE_ONLY -> "Matériel uniquement"
    }

    fun passthrough(mode: PassthroughMode): String = when (mode) {
        PassthroughMode.AUTO -> "Automatique si la sortie le permet"
        PassthroughMode.OFF -> "Toujours décoder (PCM)"
    }

    fun subtitleMode(mode: SubtitleMode): String = when (mode) {
        SubtitleMode.OFF -> "Jamais (sauf sous-titres forcés)"
        SubtitleMode.AUTO -> "Si la langue de la VO n'est pas comprise"
        SubtitleMode.ALWAYS -> "Toujours"
    }

    fun equalizer(preset: EqualizerPreset): String = when (preset) {
        EqualizerPreset.FLAT -> "Neutre"
        EqualizerPreset.VOICE -> "Voix"
        EqualizerPreset.BASS_BOOST -> "Graves"
        EqualizerPreset.TREBLE_BOOST -> "Aigus"
    }

    fun videoCodec(codec: String?): String = codec ?: "inconnu"

    fun bitrate(bitsPerSecond: Int?): String = bitsPerSecond?.let { "%.1f Mb/s".format(Locale.FRANCE, it / 1_000_000.0) } ?: "—"

    fun audioCodecName(mimeCodec: String?): String = CodecNames.audio(mimeCodec) ?: "—"
}
