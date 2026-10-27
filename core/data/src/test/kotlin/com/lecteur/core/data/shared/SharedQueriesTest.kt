package com.lecteur.core.data.shared

import com.google.common.truth.Truth.assertThat
import com.lecteur.core.common.library.Catalog
import com.lecteur.core.common.library.CreditDto
import com.lecteur.core.common.library.EpisodeDto
import com.lecteur.core.common.library.FileDto
import com.lecteur.core.common.library.MovieDto
import com.lecteur.core.common.library.SeriesDto
import com.lecteur.core.common.library.WatchDto
import com.lecteur.core.model.LibraryFilters
import com.lecteur.core.model.LibraryQuery
import com.lecteur.core.model.LibrarySection
import com.lecteur.core.model.LibrarySort
import com.lecteur.core.model.ResolutionTier
import com.lecteur.core.model.SortField
import com.lecteur.core.model.SortOrder
import com.lecteur.core.model.WatchFilter
import org.junit.Test

class SharedQueriesTest {
    private fun file(id: Long, height: Int = 1080, watch: WatchDto? = null) =
        FileDto(id, "f$id.mkv", 1_000, durationMs = 100_000, width = height * 16 / 9, height = height, watch = watch)

    private val catalog = Catalog(
        serverId = "pc", serverName = "PC", generatedAtMs = 0,
        movies = listOf(
            MovieDto(1, "Alien", 1979, genres = listOf("Horreur"), addedAt = 10, files = listOf(file(11, 720)), cast = listOf(CreditDto(7, "Sigourney Weaver"))),
            MovieDto(2, "Élysium", 2013, genres = listOf("Action"), addedAt = 30, files = listOf(file(12, 2160, WatchDto(100_000, 100_000, completed = true)))),
            MovieDto(3, "Brazil", 1985, genres = listOf("Action", "Horreur"), addedAt = 20, files = listOf(file(13)))
        ),
        series = listOf(SeriesDto(4, "Dark", 2017, episodes = listOf(EpisodeDto(41, 1, 1, files = listOf(file(14))))))
    )
    private val mapping = SharedMapping { it }

    private fun titles(query: LibraryQuery) = SharedQueries.items(catalog, mapping, query).map { it.title }

    @Test
    fun titlesSortTheWayTheLocalLibraryDoes() {
        assertThat(titles(LibraryQuery(LibrarySection.MOVIES))).containsExactly("Alien", "Brazil", "Élysium").inOrder()
        assertThat(titles(LibraryQuery(LibrarySection.MOVIES, LibrarySort(SortField.ADDED, SortOrder.DESC)))).containsExactly("Élysium", "Brazil", "Alien").inOrder()
    }

    @Test
    fun filtersNarrowByGenreNamePersonWatchAndResolution() {
        val horror = "Horreur".hashCode().toLong()
        assertThat(titles(LibraryQuery(LibrarySection.MOVIES, filters = LibraryFilters(genreId = horror)))).containsExactly("Alien", "Brazil")
        assertThat(titles(LibraryQuery(LibrarySection.MOVIES, filters = LibraryFilters(personId = 7)))).containsExactly("Alien")
        assertThat(titles(LibraryQuery(LibrarySection.MOVIES, filters = LibraryFilters(watch = WatchFilter.WATCHED)))).containsExactly("Élysium")
        assertThat(titles(LibraryQuery(LibrarySection.MOVIES, filters = LibraryFilters(resolution = ResolutionTier.FULL_HD)))).containsExactly("Brazil", "Élysium")
        // Lists and sagas belong to the phone's library: nothing of a PC is in them
        assertThat(titles(LibraryQuery(LibrarySection.MOVIES, filters = LibraryFilters(userListId = 1)))).isEmpty()
    }

    @Test
    fun searchIgnoresAccentsAndFindsPeople() {
        val results = SharedQueries.search(catalog, { it }, "elysium")
        assertThat(results.movies.map { it.title }).containsExactly("Élysium")
        assertThat(SharedQueries.search(catalog, { it }, "weaver").people.map { it.name }).containsExactly("Sigourney Weaver")
        assertThat(SharedQueries.search(catalog, { it }, "dark").series.map { it.id }).containsExactly(4L)
    }

    @Test
    fun copiedFilesGetNamesEveryFileSystemTakes() {
        assertThat(CopyPaths.safeName("Star Wars: Episode IV")).isEqualTo("Star Wars Episode IV")
        assertThat(CopyPaths.safeName("What?.")).isEqualTo("What")
        assertThat(CopyPaths.safeName("a/b\\c")).isEqualTo("a b c")
        assertThat(CopyPaths.safeName("???")).isEqualTo("Sans titre")
    }
}
