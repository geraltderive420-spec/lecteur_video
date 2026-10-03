package com.lecteur.audio

import com.google.common.truth.Truth.assertThat
import com.lecteur.audio.navigation.BrowseKind
import com.lecteur.audio.navigation.Screen
import com.lecteur.core.model.LibraryFilters
import com.lecteur.core.model.LibrarySection
import com.lecteur.core.model.MediaKind
import org.junit.Test

class NavigationTest {

    @Test
    fun browseKindsNarrowTheLibraryTheWayTheirNameSays() {
        assertThat(BrowseKind.GENRE.scope(7)).isEqualTo(LibraryFilters(genreId = 7))
        assertThat(BrowseKind.PERSON.scope(42)).isEqualTo(LibraryFilters(personId = 42))
        assertThat(BrowseKind.COLLECTION.scope(9)).isEqualTo(LibraryFilters(collectionId = 9))
    }

    @Test
    fun aDecadeCoversTenYears() {
        val scope = BrowseKind.DECADE.scope(1990)
        assertThat(scope.yearFrom).isEqualTo(1990)
        assertThat(scope.yearTo).isEqualTo(1999)
    }

    @Test
    fun routesCarryOnlyIdsAndEnumNames() {
        assertThat(Screen.Movie.create(12)).isEqualTo("movie/12")
        assertThat(Screen.Series.create(3)).isEqualTo("series/3")
        assertThat(Screen.Library.create(LibrarySection.SERIES)).isEqualTo("library/SERIES")
        assertThat(Screen.Browse.create(LibrarySection.MOVIES, BrowseKind.PERSON, 5)).isEqualTo("browse/MOVIES/PERSON/5")
        assertThat(Screen.Correct.create(MediaKind.MOVIE, 8)).isEqualTo("correct/MOVIE/8")
    }

    @Test
    fun everyRouteHasItsArgumentsInThePattern() {
        assertThat(Screen.Movie.route).contains("{movieId}")
        assertThat(Screen.Series.route).contains("{seriesId}")
        assertThat(Screen.Browse.route).containsMatch("\\{section\\}/\\{kind\\}/\\{id\\}")
        assertThat(Screen.Correct.route).contains("{kind}")
    }

    @Test
    fun onlyTheFourTabsShowTheNavigationBar() {
        assertThat(Screen.topLevel).containsExactly(Screen.Home.route, Screen.Library.route, Screen.Explorer.route, Screen.Settings.route)
        assertThat(Screen.topLevel).doesNotContain(Screen.Movie.route)
        assertThat(Screen.topLevel).doesNotContain(Screen.Search.route)
    }

    @Test
    fun routesAreDistinct() {
        val routes = listOf(
            Screen.Home, Screen.Library, Screen.Explorer, Screen.Settings, Screen.Search, Screen.Movie, Screen.Series,
            Screen.Browse, Screen.Folders, Screen.Review, Screen.Correct, Screen.Welcome
        ).map { it.route }
        assertThat(routes).containsNoDuplicates()
    }
}
