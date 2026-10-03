package com.lecteur.core.data.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lecteur.core.player.settings.DecoderMode
import com.lecteur.core.player.settings.EqualizerPreset
import com.lecteur.core.player.settings.PassthroughMode
import com.lecteur.core.player.settings.PlayerSettings
import com.lecteur.core.player.settings.SubtitleEdge
import com.lecteur.core.player.settings.SubtitleStyle
import com.lecteur.core.player.tracks.SubtitleMode

/** Mapping between [PlayerSettings] and DataStore preferences. Unknown or missing values fall back to the defaults. */
internal object PlayerSettingsPreferences {

    private val seekStep = intPreferencesKey("seek_step_seconds")
    private val longPressSpeed = floatPreferencesKey("long_press_speed")
    private val gestures = booleanPreferencesKey("gestures_enabled")
    private val volumeBoost = booleanPreferencesKey("volume_boost")
    private val audioLanguages = stringPreferencesKey("audio_languages")
    private val subtitleLanguages = stringPreferencesKey("subtitle_languages")
    private val subtitleMode = stringPreferencesKey("subtitle_mode")
    private val styleSize = floatPreferencesKey("subtitle_size")
    private val styleTextColor = intPreferencesKey("subtitle_text_color")
    private val styleEdge = stringPreferencesKey("subtitle_edge")
    private val styleEdgeColor = intPreferencesKey("subtitle_edge_color")
    private val styleBackground = intPreferencesKey("subtitle_background")
    private val stylePadding = floatPreferencesKey("subtitle_bottom_padding")
    private val decoderMode = stringPreferencesKey("decoder_mode")
    private val tunneling = booleanPreferencesKey("tunneling")
    private val passthrough = stringPreferencesKey("passthrough")
    private val backgroundAudio = booleanPreferencesKey("background_audio")
    private val lockedRotation = booleanPreferencesKey("locked_rotation")
    private val nightMode = booleanPreferencesKey("night_mode")
    private val equalizer = stringPreferencesKey("equalizer")
    private val autoPlayNext = booleanPreferencesKey("auto_play_next")
    private val nextCountdown = intPreferencesKey("next_countdown_seconds")

    fun read(prefs: Preferences): PlayerSettings {
        val d = PlayerSettings()
        val ds = d.subtitleStyle
        return PlayerSettings(
            seekStepSeconds = (prefs[seekStep] ?: d.seekStepSeconds).coerceIn(1, 120),
            longPressSpeed = (prefs[longPressSpeed] ?: d.longPressSpeed).coerceIn(1f, 4f),
            gesturesEnabled = prefs[gestures] ?: d.gesturesEnabled,
            volumeBoostEnabled = prefs[volumeBoost] ?: d.volumeBoostEnabled,
            preferredAudioLanguages = prefs[audioLanguages]?.let(::splitList) ?: d.preferredAudioLanguages,
            preferredSubtitleLanguages = prefs[subtitleLanguages]?.let(::splitList) ?: d.preferredSubtitleLanguages,
            subtitleMode = enumOf(prefs[subtitleMode], d.subtitleMode),
            subtitleStyle = SubtitleStyle(
                sizeFraction = (prefs[styleSize] ?: ds.sizeFraction).coerceIn(0.02f, 0.15f),
                textColorArgb = prefs[styleTextColor] ?: ds.textColorArgb,
                edge = enumOf(prefs[styleEdge], ds.edge),
                edgeColorArgb = prefs[styleEdgeColor] ?: ds.edgeColorArgb,
                backgroundArgb = prefs[styleBackground] ?: ds.backgroundArgb,
                bottomPaddingFraction = (prefs[stylePadding] ?: ds.bottomPaddingFraction).coerceIn(0f, 0.4f)
            ),
            decoderMode = enumOf(prefs[decoderMode], d.decoderMode),
            tunneling = prefs[tunneling] ?: d.tunneling,
            passthrough = enumOf(prefs[passthrough], d.passthrough),
            backgroundAudio = prefs[backgroundAudio] ?: d.backgroundAudio,
            lockedRotation = prefs[lockedRotation] ?: d.lockedRotation,
            nightMode = prefs[nightMode] ?: d.nightMode,
            equalizer = enumOf(prefs[equalizer], d.equalizer),
            autoPlayNext = prefs[autoPlayNext] ?: d.autoPlayNext,
            nextCountdownSeconds = (prefs[nextCountdown] ?: d.nextCountdownSeconds).coerceIn(0, 60)
        )
    }

    fun write(prefs: MutablePreferences, s: PlayerSettings) {
        prefs[seekStep] = s.seekStepSeconds
        prefs[longPressSpeed] = s.longPressSpeed
        prefs[gestures] = s.gesturesEnabled
        prefs[volumeBoost] = s.volumeBoostEnabled
        prefs[audioLanguages] = s.preferredAudioLanguages.joinToString(",")
        prefs[subtitleLanguages] = s.preferredSubtitleLanguages.joinToString(",")
        prefs[subtitleMode] = s.subtitleMode.name
        prefs[styleSize] = s.subtitleStyle.sizeFraction
        prefs[styleTextColor] = s.subtitleStyle.textColorArgb
        prefs[styleEdge] = s.subtitleStyle.edge.name
        prefs[styleEdgeColor] = s.subtitleStyle.edgeColorArgb
        prefs[styleBackground] = s.subtitleStyle.backgroundArgb
        prefs[stylePadding] = s.subtitleStyle.bottomPaddingFraction
        prefs[decoderMode] = s.decoderMode.name
        prefs[tunneling] = s.tunneling
        prefs[passthrough] = s.passthrough.name
        prefs[backgroundAudio] = s.backgroundAudio
        prefs[lockedRotation] = s.lockedRotation
        prefs[nightMode] = s.nightMode
        prefs[equalizer] = s.equalizer.name
        prefs[autoPlayNext] = s.autoPlayNext
        prefs[nextCountdown] = s.nextCountdownSeconds
    }

    private fun splitList(raw: String): List<String> = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private inline fun <reified E : Enum<E>> enumOf(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
