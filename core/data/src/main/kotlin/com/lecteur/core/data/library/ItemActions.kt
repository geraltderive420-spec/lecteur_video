package com.lecteur.core.data.library

import com.lecteur.core.data.playback.PlayPlanner
import com.lecteur.core.model.LibraryItem
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.PlayPlan
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** What a long-press on a poster can do, shared by the home screen, the library and the search. */
class ItemActions @Inject constructor(
    private val planner: PlayPlanner,
    private val watched: WatchedRepository,
    private val favorites: FavoritesRepository
) {
    /** A film plays its best copy; a series plays what its "Lire" button says (resume, next, first). Null: nothing playable. */
    suspend fun play(item: LibraryItem): PlayPlan? =
        if (item.kind == MediaKind.MOVIE) planner.forMovie(item.id) else planner.forSeries(item.id)

    suspend fun setWatched(item: LibraryItem, isWatched: Boolean) =
        if (item.kind == MediaKind.MOVIE) watched.setMovieWatched(item.id, isWatched) else watched.setSeriesWatched(item.id, isWatched)

    suspend fun isFavorite(item: LibraryItem): Boolean =
        (if (item.kind == MediaKind.MOVIE) favorites.observeMovie(item.id) else favorites.observeSeries(item.id)).first()

    /** Returns whether the title is a favourite afterwards. */
    suspend fun toggleFavorite(item: LibraryItem): Boolean =
        if (item.kind == MediaKind.MOVIE) favorites.toggleMovie(item.id) else favorites.toggleSeries(item.id)
}
