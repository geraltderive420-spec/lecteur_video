package com.lecteur.core.player

import com.google.common.truth.Truth.assertThat
import com.lecteur.core.player.subtitles.ExternalSubtitleFinder
import com.lecteur.core.player.subtitles.SubtitleFormat
import com.lecteur.core.player.tracks.AudioOption
import com.lecteur.core.player.tracks.LanguageCodes
import com.lecteur.core.player.tracks.LanguagePreferences
import com.lecteur.core.player.tracks.SubtitleMode
import com.lecteur.core.player.tracks.SubtitleOption
import com.lecteur.core.player.tracks.TrackSelectionPolicy
import org.junit.Test

class TracksAndSubtitlesTest {

    private fun audio(i: Int, lang: String?, default: Boolean = false, supported: Boolean = true) =
        AudioOption(i, lang, "AC3", 6, null, default, false, supported)

    private fun sub(i: Int, lang: String?, forced: Boolean = false, supported: Boolean = true) =
        SubtitleOption(i, lang, "SRT", null, forced, false, false, false, supported)

    // region LanguageCodes

    @Test
    fun languageVariantsCompareEqual() {
        assertThat(LanguageCodes.matches("fre", "fr")).isTrue()
        assertThat(LanguageCodes.matches("fra", "FR")).isTrue()
        assertThat(LanguageCodes.matches("fr-CA", "fre")).isTrue()
        assertThat(LanguageCodes.matches("eng", "en_US")).isTrue()
        assertThat(LanguageCodes.matches("eng", "fr")).isFalse()
    }

    @Test
    fun undeterminedLanguageNeverMatches() {
        assertThat(LanguageCodes.normalize("und")).isNull()
        assertThat(LanguageCodes.normalize("")).isNull()
        assertThat(LanguageCodes.normalize(null)).isNull()
        assertThat(LanguageCodes.matches("und", "und")).isFalse()
    }

    @Test
    fun knownCodesRejectReleaseTags() {
        assertThat(LanguageCodes.isKnownCode("fr")).isTrue()
        assertThat(LanguageCodes.isKnownCode("fre")).isTrue()
        assertThat(LanguageCodes.isKnownCode("hd")).isFalse()
        assertThat(LanguageCodes.isKnownCode("sub")).isFalse()
    }

    // endregion

    // region TrackSelectionPolicy

    @Test
    fun audioFollowsLanguagePreferenceOrder() {
        val tracks = listOf(audio(0, "eng", default = true), audio(1, "jpn"), audio(2, "fre"))
        val choice = TrackSelectionPolicy.choose(tracks, emptyList(), LanguagePreferences(audio = listOf("fr", "en")))
        assertThat(choice.audioIndex).isEqualTo(2)
        val fallback = TrackSelectionPolicy.choose(tracks, emptyList(), LanguagePreferences(audio = listOf("de", "ja")))
        assertThat(fallback.audioIndex).isEqualTo(1)
    }

    @Test
    fun audioFallsBackToDefaultThenFirst() {
        val withDefault = listOf(audio(0, "eng"), audio(1, "jpn", default = true))
        assertThat(TrackSelectionPolicy.choose(withDefault, emptyList(), LanguagePreferences(audio = listOf("fr"))).audioIndex).isEqualTo(1)
        val noDefault = listOf(audio(0, "eng"), audio(1, "jpn"))
        assertThat(TrackSelectionPolicy.choose(noDefault, emptyList(), LanguagePreferences(audio = listOf("fr"))).audioIndex).isEqualTo(0)
    }

    @Test
    fun unsupportedAudioIsSkippedWhenAnotherIsPlayable() {
        val tracks = listOf(audio(0, "fre", supported = false), audio(1, "eng"))
        assertThat(TrackSelectionPolicy.choose(tracks, emptyList(), LanguagePreferences(audio = listOf("fr"))).audioIndex).isEqualTo(1)
    }

    @Test
    fun noAudioMeansNoChoice() {
        assertThat(TrackSelectionPolicy.choose(emptyList(), emptyList(), LanguagePreferences()).audioIndex).isNull()
    }

    @Test
    fun autoModeShowsNoFullSubtitlesWhenAudioIsUnderstood() {
        val choice = TrackSelectionPolicy.choose(
            listOf(audio(0, "fre")),
            listOf(sub(0, "fre"), sub(1, "eng")),
            LanguagePreferences(audio = listOf("fr"), subtitles = listOf("fr"), subtitleMode = SubtitleMode.AUTO)
        )
        assertThat(choice.subtitleIndex).isNull()
    }

    @Test
    fun autoModeSelectsForcedTrackOfTheAudioLanguage() {
        val choice = TrackSelectionPolicy.choose(
            listOf(audio(0, "fre")),
            listOf(sub(0, "fre"), sub(1, "fre", forced = true)),
            LanguagePreferences(subtitleMode = SubtitleMode.AUTO)
        )
        assertThat(choice.subtitleIndex).isEqualTo(1)
    }

    @Test
    fun autoModeSubtitlesForeignAudio() {
        val choice = TrackSelectionPolicy.choose(
            listOf(audio(0, "jpn")),
            listOf(sub(0, "eng"), sub(1, "fre", forced = true), sub(2, "fre")),
            LanguagePreferences(audio = listOf("fr"), subtitles = listOf("fr"), subtitleMode = SubtitleMode.AUTO)
        )
        assertThat(choice.subtitleIndex).isEqualTo(2)
    }

    @Test
    fun alwaysModeSubtitlesEvenUnderstoodAudio() {
        val choice = TrackSelectionPolicy.choose(
            listOf(audio(0, "fre")),
            listOf(sub(0, "fre")),
            LanguagePreferences(subtitleMode = SubtitleMode.ALWAYS)
        )
        assertThat(choice.subtitleIndex).isEqualTo(0)
    }

    @Test
    fun offModeOnlyKeepsForcedTracks() {
        val subs = listOf(sub(0, "fre"), sub(1, "fre", forced = true))
        val choice = TrackSelectionPolicy.choose(listOf(audio(0, "fre")), subs, LanguagePreferences(subtitleMode = SubtitleMode.OFF))
        assertThat(choice.subtitleIndex).isEqualTo(1)
        val none = TrackSelectionPolicy.choose(listOf(audio(0, "fre")), listOf(sub(0, "fre")), LanguagePreferences(subtitleMode = SubtitleMode.OFF))
        assertThat(none.subtitleIndex).isNull()
    }

    @Test
    fun unsupportedSubtitlesAreNeverChosen() {
        val choice = TrackSelectionPolicy.choose(
            listOf(audio(0, "jpn")),
            listOf(sub(0, "fre", supported = false), sub(1, "eng")),
            LanguagePreferences(subtitles = listOf("fr", "en"), subtitleMode = SubtitleMode.ALWAYS)
        )
        assertThat(choice.subtitleIndex).isEqualTo(1)
    }

    // endregion

    // region ExternalSubtitleFinder

    @Test
    fun findsSubtitlesNamedAfterTheVideo() {
        val found = ExternalSubtitleFinder.find(
            "Movie.2020.1080p.mkv",
            listOf("Movie.2020.1080p.srt", "Movie.2020.1080p.fr.srt", "Movie.2020.1080p.en.forced.ass", "Other.srt", "Movie.2020.1080p.mkv")
        )
        assertThat(found.map { it.fileName }).containsExactly(
            "Movie.2020.1080p.en.forced.ass", "Movie.2020.1080p.fr.srt", "Movie.2020.1080p.srt"
        )
        val en = found.first { it.fileName.contains(".en.") }
        assertThat(en.language).isEqualTo("en")
        assertThat(en.isForced).isTrue()
        assertThat(en.format).isEqualTo(SubtitleFormat.ASS)
        assertThat(found.first { it.fileName.endsWith(".1080p.srt") }.language).isNull()
    }

    @Test
    fun matchingIsCaseInsensitiveAndRespectsWordBoundary() {
        val found = ExternalSubtitleFinder.find("Alien.mkv", listOf("ALIEN.FRE.SRT", "Aliens.srt", "Alien3.srt"))
        assertThat(found.map { it.fileName }).containsExactly("ALIEN.FRE.SRT")
        assertThat(found.single().language).isEqualTo("fr")
    }

    @Test
    fun releaseTagsAreNotLanguages() {
        val found = ExternalSubtitleFinder.find("Show.S01E01.mkv", listOf("Show.S01E01.HD.srt"))
        assertThat(found.single().language).isNull()
    }

    @Test
    fun vobsubPairIsOneUnsupportedEntry() {
        val found = ExternalSubtitleFinder.find("Film.mkv", listOf("Film.idx", "Film.sub"))
        assertThat(found).hasSize(1)
        assertThat(found.single().format.isRenderable).isFalse()
        assertThat(SubtitleFormat.SRT.isRenderable).isTrue()
        assertThat(SubtitleFormat.SUP.mimeType).isEqualTo("application/pgs")
    }

    @Test
    fun noSiblingsNoSubtitles() {
        assertThat(ExternalSubtitleFinder.find("Film.mkv", emptyList())).isEmpty()
        assertThat(ExternalSubtitleFinder.find("", listOf("a.srt"))).isEmpty()
    }

    // endregion
}
