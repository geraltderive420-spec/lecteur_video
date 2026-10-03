package com.lecteur.core.player.settings

import com.lecteur.core.player.tracks.LanguagePreferences
import com.lecteur.core.player.tracks.SubtitleMode
import kotlinx.coroutines.flow.Flow

enum class DecoderMode {
    /** Hardware decoders first, FFmpeg software decoders as fallback. */
    AUTO,

    /** FFmpeg software decoders preferred (useful when a device decoder misbehaves). */
    SOFTWARE_PREFERRED,

    /** Hardware decoders only for video (FFmpeg stays available for audio codecs the device lacks). */
    HARDWARE_ONLY
}

enum class PassthroughMode {
    /** Bitstream passthrough whenever the connected output supports the codec. */
    AUTO,

    /** Always decode to PCM. */
    OFF
}

enum class SubtitleEdge { NONE, OUTLINE, DROP_SHADOW }

enum class EqualizerPreset { FLAT, VOICE, BASS_BOOST, TREBLE_BOOST }

data class SubtitleStyle(
    /** Text height as a fraction of the video height. */
    val sizeFraction: Float = 0.0533f,
    val textColorArgb: Int = 0xFFFFFFFF.toInt(),
    val edge: SubtitleEdge = SubtitleEdge.OUTLINE,
    val edgeColorArgb: Int = 0xFF000000.toInt(),
    val backgroundArgb: Int = 0x00000000,
    /** Distance of the text block from the bottom edge, as a fraction of the height. */
    val bottomPaddingFraction: Float = 0.08f
)

data class PlayerSettings(
    val seekStepSeconds: Int = 10,
    val longPressSpeed: Float = 2f,
    val gesturesEnabled: Boolean = true,
    val volumeBoostEnabled: Boolean = false,
    val preferredAudioLanguages: List<String> = listOf("fr"),
    val preferredSubtitleLanguages: List<String> = listOf("fr"),
    val subtitleMode: SubtitleMode = SubtitleMode.AUTO,
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    val decoderMode: DecoderMode = DecoderMode.AUTO,
    val tunneling: Boolean = false,
    val passthrough: PassthroughMode = PassthroughMode.AUTO,
    val backgroundAudio: Boolean = false,
    val lockedRotation: Boolean = false,
    val nightMode: Boolean = false,
    val equalizer: EqualizerPreset = EqualizerPreset.FLAT,
    /** Play the next episode / file of the queue by itself when one ends. */
    val autoPlayNext: Boolean = true,
    /** Seconds before the end at which the "next episode" card starts counting down; 0 shows no countdown. */
    val nextCountdownSeconds: Int = 10
) {
    val languagePreferences: LanguagePreferences
        get() = LanguagePreferences(preferredAudioLanguages, preferredSubtitleLanguages, subtitleMode)
}

interface PlayerSettingsRepository {
    val settings: Flow<PlayerSettings>
    suspend fun update(transform: (PlayerSettings) -> PlayerSettings)
}
