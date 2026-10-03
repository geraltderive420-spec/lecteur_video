package com.lecteur.feature.player

import com.google.common.truth.Truth.assertThat
import com.lecteur.core.player.display.DisplayMode
import com.lecteur.core.player.tracks.AudioOption
import com.lecteur.core.player.tracks.SubtitleOption
import com.lecteur.feature.player.ui.PlayerLabels
import org.junit.Test

class PlayerUiHelpersTest {

    // region TimeFormat

    @Test
    fun clockFormatsMinutesAndHours() {
        assertThat(TimeFormat.clock(0)).isEqualTo("00:00")
        assertThat(TimeFormat.clock(65_000)).isEqualTo("01:05")
        assertThat(TimeFormat.clock(3_723_000)).isEqualTo("1:02:03")
        assertThat(TimeFormat.clock(36_000_000)).isEqualTo("10:00:00")
        assertThat(TimeFormat.clock(-5_000)).isEqualTo("00:00")
    }

    @Test
    fun signedShowsTheDirection() {
        assertThat(TimeFormat.signed(30_000)).isEqualTo("+00:30")
        assertThat(TimeFormat.signed(-70_000)).isEqualTo("-01:10")
        assertThat(TimeFormat.signed(0)).isEqualTo("+00:00")
    }

    @Test
    fun delaysAreReadable() {
        assertThat(TimeFormat.delay(0)).isEqualTo("0 ms")
        assertThat(TimeFormat.delay(150)).isEqualTo("+150 ms")
        assertThat(TimeFormat.delay(-50)).isEqualTo("-50 ms")
        assertThat(TimeFormat.delay(-1_250)).isEqualTo("-1,25 s")
    }

    @Test
    fun skipAndSpeed() {
        assertThat(TimeFormat.skip(20_000)).isEqualTo("+20 s")
        assertThat(TimeFormat.skip(-10_000)).isEqualTo("-10 s")
        assertThat(TimeFormat.speed(1f)).isEqualTo("1x")
        assertThat(TimeFormat.speed(0.25f)).isEqualTo("0,25x")
        assertThat(TimeFormat.speed(1.5f)).isEqualTo("1,5x")
    }

    // endregion

    // region PlayerLabels

    private fun audio(language: String?, codec: String? = "E-AC3", channels: Int = 6, label: String? = null) =
        AudioOption(0, language, codec, channels, label, isDefault = false, isSelected = false, isSupported = true)

    private fun subtitle(language: String?, codec: String? = "SRT", forced: Boolean = false, external: Boolean = false, supported: Boolean = true) =
        SubtitleOption(1, language, codec, null, forced, false, false, external, supported)

    @Test
    fun audioLabelListsLanguageCodecAndLayout() {
        assertThat(PlayerLabels.audio(audio("fre"))).isEqualTo("Français · E-AC3 · 5.1")
        assertThat(PlayerLabels.audio(audio("eng", "DTS-HD MA", 8))).isEqualTo("Anglais · DTS-HD MA · 7.1")
        assertThat(PlayerLabels.audio(audio(null, "AAC", 2))).isEqualTo("Piste 1 · AAC · stéréo")
    }

    @Test
    fun audioLabelKeepsAUsefulTrackTitle() {
        assertThat(PlayerLabels.audio(audio("fre", label = "Commentaire du réalisateur"))).endsWith("Commentaire du réalisateur")
        assertThat(PlayerLabels.audio(audio("fre", label = "Français"))).doesNotContain("Français · Français")
    }

    @Test
    fun subtitleLabelFlagsForcedAndExternal() {
        assertThat(PlayerLabels.subtitle(subtitle("fre", forced = true))).isEqualTo("Français · SRT · forcés")
        assertThat(PlayerLabels.subtitle(subtitle("eng", external = true))).isEqualTo("Anglais · SRT · externe")
    }

    @Test
    fun unsupportedAndImageSubtitlesExplainThemselves() {
        assertThat(PlayerLabels.subtitleProblem(subtitle("fre", "VOBSUB", supported = false))).contains("VOBSUB")
        assertThat(PlayerLabels.subtitleProblem(subtitle("fre", "SRT"))).isNull()
        assertThat(PlayerLabels.subtitleNote(subtitle("fre", "PGS"))).contains("image")
        assertThat(PlayerLabels.subtitleNote(subtitle("fre", "SRT"))).isNull()
    }

    @Test
    fun everyDisplayModeHasALabel() {
        DisplayMode.entries.forEach { assertThat(PlayerLabels.displayMode(it)).isNotEmpty() }
        assertThat(PlayerLabels.channels(6)).isEqualTo("5.1")
        assertThat(PlayerLabels.channels(3)).isEqualTo("3 canaux")
    }

    // endregion
}
