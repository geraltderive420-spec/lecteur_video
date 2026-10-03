package com.lecteur.core.player.tracks

/** An audio stream of the playing media, as shown in the track picker. */
data class AudioOption(
    /** Index among the audio tracks of the media, stable for a given file; this is what gets persisted. */
    val index: Int,
    val language: String?,
    val codec: String?,
    val channels: Int,
    val label: String?,
    val isDefault: Boolean,
    val isSelected: Boolean,
    /** False when no decoder (hardware or FFmpeg) can play it on this device. */
    val isSupported: Boolean
)

data class SubtitleOption(
    val index: Int,
    val language: String?,
    val codec: String?,
    val label: String?,
    val isForced: Boolean,
    val isDefault: Boolean,
    val isSelected: Boolean,
    val isExternal: Boolean,
    /** Formats the renderer cannot decode (e.g. VOBSUB) are listed but flagged. */
    val isSupported: Boolean
)

enum class SubtitleMode {
    /** Never show full subtitles; forced ones (foreign dialogue) still appear. */
    OFF,

    /** Subtitles only when the audio is not in a language the user understands, plus forced ones. */
    AUTO,

    /** Subtitles in the preferred language whenever available. */
    ALWAYS
}

data class LanguagePreferences(
    val audio: List<String> = listOf("fr"),
    val subtitles: List<String> = listOf("fr"),
    val subtitleMode: SubtitleMode = SubtitleMode.AUTO
)

data class TrackChoice(val audioIndex: Int?, val subtitleIndex: Int?)

/** Persisted/engine value for "subtitles explicitly switched off" (null means "not chosen yet, use the policy"). */
const val SUBTITLE_OFF = -1
