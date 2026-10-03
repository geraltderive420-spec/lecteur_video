package com.lecteur.feature.library

import com.google.common.truth.Truth.assertThat
import com.lecteur.core.model.FilterOptions
import com.lecteur.core.model.Genre
import com.lecteur.core.model.HdrFilter
import com.lecteur.core.model.LanguageOption
import com.lecteur.core.model.LibraryFilters
import com.lecteur.core.model.LibraryFolder
import com.lecteur.core.model.MediaCategory
import com.lecteur.core.model.ResolutionTier
import com.lecteur.core.model.SortField
import com.lecteur.core.model.SortOrder
import com.lecteur.core.model.WatchFilter
import org.junit.Test

class LibraryLabelsTest {

    private val options = FilterOptions(
        genres = listOf(Genre(1, null, "Action")),
        folders = listOf(LibraryFolder(5, "tree://a", "/storage/emulated/0/Films", MediaCategory.MOVIES)),
        audioLanguages = listOf(LanguageOption("fr", "français"))
    )

    @Test
    fun noFilterNoChip() {
        assertThat(LibraryLabels.activeChips(LibraryFilters(), options)).isEmpty()
    }

    @Test
    fun everySheetFilterGetsAChipNamedAfterItsValue() {
        val filters = LibraryFilters(
            genreId = 1, yearFrom = 1990, yearTo = 1999, watch = WatchFilter.UNWATCHED, resolution = ResolutionTier.UHD,
            hdr = HdrFilter.DOLBY_VISION, certification = "12", folderId = 5, audioLanguage = "fr"
        )
        assertThat(LibraryLabels.activeChips(filters, options).map { it.label }).containsExactly(
            "Action", "Années 1990", "Non vus", "4K", "Dolby Vision", "Âge 12", "Films", "Audio : français"
        ).inOrder()
        assertThat(LibraryLabels.activeChips(filters, options)).hasSize(filters.activeCount)
    }

    @Test
    fun removingAChipClearsOnlyItsFilter() {
        val filters = LibraryFilters(genreId = 1, watch = WatchFilter.WATCHED, personId = 9)
        val chips = LibraryLabels.activeChips(filters, options)
        val withoutGenre = chips.first { it.label == "Action" }.remove(filters)
        assertThat(withoutGenre).isEqualTo(LibraryFilters(watch = WatchFilter.WATCHED, personId = 9))
        val withoutWatch = chips.first { it.label == "Vus" }.remove(filters)
        assertThat(withoutWatch.watch).isEqualTo(WatchFilter.ALL)
        assertThat(withoutWatch.genreId).isEqualTo(1)
    }

    @Test
    fun theBrowseScopeIsNeverAChip() {
        val chips = LibraryLabels.activeChips(LibraryFilters(personId = 9, collectionId = 3, userListId = 1), options)
        assertThat(chips).isEmpty()
    }

    @Test
    fun aYearRangeThatIsNotADecadeIsJustAYear() {
        val chips = LibraryLabels.activeChips(LibraryFilters(yearFrom = 2001, yearTo = 2003), options)
        assertThat(chips.single().label).isEqualTo("Année")
        assertThat(chips.single().remove(LibraryFilters(yearFrom = 2001, yearTo = 2003)).yearFrom).isNull()
    }

    @Test
    fun chipsStillReadBeforeTheOptionsAreLoaded() {
        val chips = LibraryLabels.activeChips(LibraryFilters(genreId = 1, folderId = 5, audioLanguage = "fr"), null)
        assertThat(chips.map { it.label }).containsExactly("Genre", "Dossier", "Audio : fr").inOrder()
    }

    @Test
    fun everySortFieldHasALabelAndAnOrderPhrase() {
        SortField.entries.forEach { field ->
            assertThat(LibraryLabels.sort(field)).isNotEmpty()
            assertThat(LibraryLabels.sortOrder(field, SortOrder.ASC)).isNotEqualTo(LibraryLabels.sortOrder(field, SortOrder.DESC))
        }
    }
}
