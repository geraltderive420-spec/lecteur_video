package com.lecteur.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class LibraryModelsTest {

    // region resolution tiers

    @Test
    fun tierLooksAtWidthAndHeight() {
        assertThat(ResolutionTier.of(3840, 2160)).isEqualTo(ResolutionTier.UHD)
        assertThat(ResolutionTier.of(1920, 1080)).isEqualTo(ResolutionTier.FULL_HD)
        assertThat(ResolutionTier.of(1280, 720)).isEqualTo(ResolutionTier.HD)
        assertThat(ResolutionTier.of(720, 480)).isEqualTo(ResolutionTier.ANY)
        assertThat(ResolutionTier.of(null, null)).isEqualTo(ResolutionTier.ANY)
    }

    @Test
    fun aCroppedWidescreenFilmKeepsItsTier() {
        assertThat(ResolutionTier.of(1920, 800)).isEqualTo(ResolutionTier.FULL_HD)
        assertThat(ResolutionTier.of(3840, 1600)).isEqualTo(ResolutionTier.UHD)
        assertThat(ResolutionTier.of(1280, 536)).isEqualTo(ResolutionTier.HD)
    }

    @Test
    fun badgeNamesTheTier() {
        assertThat(ResolutionTier.badge(3840, 2160)).isEqualTo("4K")
        assertThat(ResolutionTier.badge(1920, 800)).isEqualTo("1080p")
        assertThat(ResolutionTier.badge(1280, 720)).isEqualTo("720p")
        assertThat(ResolutionTier.badge(640, 480)).isEqualTo("SD")
        assertThat(ResolutionTier.badge(null, null)).isNull()
        assertThat(ResolutionTier.badge(0, 0)).isNull()
    }

    // endregion

    // region filters and sorting

    @Test
    fun activeCountOnlyCountsTheSheetFilters() {
        assertThat(LibraryFilters().activeCount).isEqualTo(0)
        val filters = LibraryFilters(genreId = 1, yearFrom = 1990, yearTo = 1999, watch = WatchFilter.UNWATCHED, personId = 5)
        assertThat(filters.activeCount).isEqualTo(3)
        assertThat(filters.hasScope).isTrue()
    }

    @Test
    fun clearingTheSheetKeepsTheBrowseScope() {
        val cleared = LibraryFilters(genreId = 1, watch = WatchFilter.WATCHED, personId = 5, collectionId = 9).withoutSheetFilters()
        assertThat(cleared).isEqualTo(LibraryFilters(personId = 5, collectionId = 9))
        assertThat(LibraryFilters().isEmpty).isTrue()
    }

    @Test
    fun titlesSortAscendingAndEverythingElseDescendingByDefault() {
        assertThat(SortField.TITLE.defaultOrder()).isEqualTo(SortOrder.ASC)
        assertThat(SortField.RATING.defaultOrder()).isEqualTo(SortOrder.DESC)
        assertThat(SortField.ADDED.defaultOrder()).isEqualTo(SortOrder.DESC)
        assertThat(SortOrder.ASC.flipped()).isEqualTo(SortOrder.DESC)
    }

    // endregion

    // region play plan

    @Test
    fun aPlanNeedsEntriesAndAValidStart() {
        assertThrows(IllegalArgumentException::class.java) { PlayPlan(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { PlayPlan(listOf(QueueEntry("u", "t")), startIndex = 1) }
        assertThat(PlayPlan(listOf(QueueEntry("u", "t"))).startIndex).isEqualTo(0)
    }

    // endregion

    // region home layout

    @Test
    fun defaultLayoutShowsEveryRowInDeclarationOrder() {
        assertThat(HomeLayout.DEFAULT.visibleRows).containsExactlyElementsIn(HomeRow.entries).inOrder()
    }

    @Test
    fun layoutSurvivesAnEncodeDecodeRoundTrip() {
        val layout = HomeLayout.DEFAULT.move(HomeRow.FAVORITES, -3).setVisible(HomeRow.UNWATCHED, false)
        assertThat(HomeLayout.decode(HomeLayout.encode(layout))).isEqualTo(layout)
    }

    @Test
    fun decodeDropsUnknownRowsAndDuplicatesAndAppendsNewOnes() {
        val layout = HomeLayout.decode("SERIES:1,GHOST:1,SERIES:0,CONTINUE:0")
        assertThat(layout.rows.take(2)).containsExactly(HomeRowConfig(HomeRow.SERIES, true), HomeRowConfig(HomeRow.CONTINUE, false)).inOrder()
        assertThat(layout.rows.map { it.row }).containsNoDuplicates()
        assertThat(layout.rows).hasSize(HomeRow.entries.size)
        assertThat(layout.rows.drop(2).all { it.visible }).isTrue()
    }

    @Test
    fun decodeFallsBackToTheDefaultLayout() {
        assertThat(HomeLayout.decode(null)).isEqualTo(HomeLayout.DEFAULT)
        assertThat(HomeLayout.decode("  ")).isEqualTo(HomeLayout.DEFAULT)
    }

    @Test
    fun moveStopsAtTheEnds() {
        val first = HomeLayout.DEFAULT.rows.first().row
        val last = HomeLayout.DEFAULT.rows.last().row
        assertThat(HomeLayout.DEFAULT.move(first, -1)).isEqualTo(HomeLayout.DEFAULT)
        assertThat(HomeLayout.DEFAULT.move(last, 1)).isEqualTo(HomeLayout.DEFAULT)
        assertThat(HomeLayout.DEFAULT.move(first, 1).rows[1].row).isEqualTo(first)
    }

    // endregion

    // region labels and links

    @Test
    fun channelLabelsReadLikeAnAmplifierDisplay() {
        assertThat(channelLabel(2)).isEqualTo("2.0")
        assertThat(channelLabel(6)).isEqualTo("5.1")
        assertThat(channelLabel(8)).isEqualTo("7.1")
        assertThat(channelLabel(0)).isNull()
        assertThat(channelLabel(12)).isEqualTo("12 ch")
    }

    @Test
    fun externalLinksNeedTheirIdentifier() {
        assertThat(ExternalLinks.imdb("tt0133093")).isEqualTo("https://www.imdb.com/title/tt0133093/")
        assertThat(ExternalLinks.imdb(" ")).isNull()
        assertThat(ExternalLinks.trailer("abc123")).isEqualTo("https://www.youtube.com/watch?v=abc123")
        assertThat(ExternalLinks.trailer(null)).isNull()
        assertThat(ExternalLinks.tmdb(MediaKind.SERIES, 1399)).isEqualTo("https://www.themoviedb.org/tv/1399")
        assertThat(ExternalLinks.tmdb(MediaKind.MOVIE, null)).isNull()
    }

    @Test
    fun versionLabelSummarisesTheFile() {
        val version = FileVersion(
            1, "a.mkv", "/a.mkv", 10, null, true,
            MediaBadges("4K", HdrType.DOLBY_VISION, VideoCodec.HEVC, "TrueHD 7.1", emptyList(), emptyList()), null
        )
        assertThat(version.shortLabel).isEqualTo("4K · Dolby Vision · HEVC · TrueHD 7.1")
        val bare = version.copy(badges = MediaBadges(null, HdrType.NONE, VideoCodec.UNKNOWN, null, emptyList(), emptyList()))
        assertThat(bare.shortLabel).isEqualTo("a.mkv")
    }

    @Test
    fun seasonTitlePrefersTheTmdbNameAndNamesTheSpecials() {
        assertThat(SeasonDetail(2, "Les Chevaliers", null, emptyList()).title).isEqualTo("Les Chevaliers")
        assertThat(SeasonDetail(3, null, null, emptyList()).title).isEqualTo("Saison 3")
        assertThat(SeasonDetail(0, " ", null, emptyList()).title).isEqualTo("Épisodes spéciaux")
    }

    // endregion
}

class FormattersTest {

    @Test
    fun runtimeReadsInHoursAndMinutes() {
        assertThat(Formatters.runtime(148)).isEqualTo("2 h 28 min")
        assertThat(Formatters.runtime(45)).isEqualTo("45 min")
        assertThat(Formatters.runtime(120)).isEqualTo("2 h")
        assertThat(Formatters.runtime(0)).isNull()
        assertThat(Formatters.runtime(null)).isNull()
    }

    @Test
    fun fileDurationRoundsToTheNearestMinute() {
        assertThat(Formatters.runtimeOfMs(8_880_000)).isEqualTo("2 h 28 min")
        assertThat(Formatters.runtimeOfMs(89_999)).isEqualTo("1 min")
        assertThat(Formatters.runtimeOfMs(null)).isNull()
    }

    @Test
    fun sizeUsesFileManagerUnits() {
        val en = java.util.Locale.US
        assertThat(Formatters.size(512, en)).isEqualTo("512 o")
        assertThat(Formatters.size(2_048, en)).isEqualTo("2 Ko")
        assertThat(Formatters.size(4_500_000_000, en)).isEqualTo("4.2 Go")
        assertThat(Formatters.size(120L * 1024 * 1024 * 1024, en)).isEqualTo("120 Go")
        assertThat(Formatters.size(3L * 1024 * 1024 * 1024 * 1024, en)).isEqualTo("3.0 To")
    }

    @Test
    fun sizeUsesTheLocalDecimalSeparator() {
        assertThat(Formatters.size(4_500_000_000, java.util.Locale.FRANCE)).isEqualTo("4,2 Go")
    }

    @Test
    fun ratingKeepsOneDecimalAndHidesMissingOnes() {
        assertThat(Formatters.rating(7.84f, java.util.Locale.US)).isEqualTo("7.8")
        assertThat(Formatters.rating(0f)).isNull()
        assertThat(Formatters.rating(null)).isNull()
    }

    @Test
    fun yearIsReadFromTheDate() {
        assertThat(Formatters.yearOf("2021-03-05")).isEqualTo(2021)
        assertThat(Formatters.yearOf("2021")).isEqualTo(2021)
        assertThat(Formatters.yearOf("")).isNull()
        assertThat(Formatters.yearOf("abcd")).isNull()
        assertThat(Formatters.yearOf(null)).isNull()
    }

    @Test
    fun countsAgree() {
        assertThat(Formatters.episodes(1)).isEqualTo("1 épisode")
        assertThat(Formatters.episodes(12)).isEqualTo("12 épisodes")
        assertThat(Formatters.seasons(2)).isEqualTo("2 saisons")
        assertThat(Formatters.decade(1990)).isEqualTo("Années 1990")
    }
}
