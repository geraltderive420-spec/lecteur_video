package com.lecteur.audio.navigation

import com.lecteur.core.model.LibraryFilters
import com.lecteur.core.model.LibrarySection
import com.lecteur.core.model.MediaKind

/** What a "browse by" entry narrows the library to. */
enum class BrowseKind {
    GENRE, PERSON, COLLECTION, DECADE, LIST;

    /** The filters that list the titles of this entry; [id] is a genre, person or collection id, or the first year of a decade. */
    fun scope(id: Long): LibraryFilters = when (this) {
        GENRE -> LibraryFilters(genreId = id)
        PERSON -> LibraryFilters(personId = id)
        COLLECTION -> LibraryFilters(collectionId = id)
        DECADE -> LibraryFilters(yearFrom = id.toInt(), yearTo = id.toInt() + 9)
        LIST -> LibraryFilters(userListId = id)
    }
}

/**
 * Routes of the app. The four top-level ones sit on the navigation bar; the others open above it.
 * Arguments are ids and enum names only: anything displayed (an actor's name, a genre) is looked up by the destination.
 */
sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Library : Screen("library/{section}") {
        fun create(section: LibrarySection) = "library/${section.name}"
    }
    data object Explorer : Screen("explorer")
    data object Settings : Screen("settings")

    data object Search : Screen("search")
    data object Movie : Screen("movie/{movieId}") {
        fun create(id: Long) = "movie/$id"
    }
    data object Series : Screen("series/{seriesId}") {
        fun create(id: Long) = "series/$id"
    }
    data object Browse : Screen("browse/{section}/{kind}/{id}") {
        fun create(section: LibrarySection, kind: BrowseKind, id: Long) = "browse/${section.name}/${kind.name}/$id"
    }
    data object Folders : Screen("folders")
    data object Review : Screen("review")
    data object Correct : Screen("correct/{kind}/{id}") {
        fun create(kind: MediaKind, id: Long) = "correct/${kind.name}/$id"
    }
    data object Welcome : Screen("welcome")
    data object Remote : Screen("remote")
    data object Lists : Screen("lists")

    companion object {
        /**
         * Routes that show the navigation bar. A getter, not a stored value: the companion is initialised while the first
         * subclass (Home...) is still being constructed, and reading `Home.route` at that point finds it null.
         */
        val topLevel: Set<String> get() = setOf(Home.route, Library.route, Explorer.route, Settings.route)
    }
}
