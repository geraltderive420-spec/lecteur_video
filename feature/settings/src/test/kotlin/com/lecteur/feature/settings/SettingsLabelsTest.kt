package com.lecteur.feature.settings

import com.google.common.truth.Truth.assertThat
import com.lecteur.core.model.ThemeMode
import com.lecteur.core.player.settings.DecoderMode
import com.lecteur.core.player.settings.PassthroughMode
import com.lecteur.core.player.tracks.SubtitleMode
import org.junit.Test

class SettingsLabelsTest {

    @Test
    fun scanIntervalSaysWhenItIsOff() {
        assertThat(intervalLabel(0)).isEqualTo("Désactivée")
        assertThat(intervalLabel(6)).isEqualTo("Toutes les 6 heures")
    }

    @Test
    fun speedsDropTheUselessDecimal() {
        assertThat(trim(2f)).isEqualTo("2")
        assertThat(trim(1.5f)).isEqualTo("1,5")
    }

    @Test
    fun languageListNamesEachLanguageOrSaysThereIsNone() {
        assertThat(languageList(emptyList())).contains("défaut")
        assertThat(languageList(listOf("fr", "en"))).contains(",")
        assertThat(languageName("zz")).isEqualTo("Zz")
    }

    @Test
    fun everyEnumValueHasADistinctLabel() {
        assertThat(DecoderMode.entries.map(::decoderLabel)).containsNoDuplicates()
        assertThat(PassthroughMode.entries.map(::passthroughLabel)).containsNoDuplicates()
        assertThat(SubtitleMode.entries.map(::subtitleModeLabel)).containsNoDuplicates()
        assertThat(ThemeMode.entries.map(::themeLabel)).containsNoDuplicates()
    }

    @Test
    fun languageTagReadsInFrench() {
        assertThat(languageTag("fr-FR")).startsWith("Français")
        assertThat(languageTag("en-US")).startsWith("Anglais")
    }
}
