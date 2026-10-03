package com.lecteur.core.data.library

import com.lecteur.core.common.playback.EpisodeFileState
import com.lecteur.core.common.playback.NextUpResolver
import com.lecteur.core.data.playback.PlaybackRequestFactory
import com.lecteur.core.database.dao.LibraryDao
import com.lecteur.core.database.relation.ContinueRow
import com.lecteur.core.database.relation.EpisodeStateRow
import com.lecteur.core.model.LibraryFilters
import com.lecteur.core.model.LibraryItem
import com.lecteur.core.model.LibraryQuery
import com.lecteur.core.model.LibrarySection
import com.lecteur.core.model.LibrarySort
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.SortField
import com.lecteur.core.model.SortOrder
import com.lecteur.core.model.WatchFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** A file the user stopped in the middle of: a film, an episode, or a file that is not identified. */
data class ContinueItem(
    val mediaFileId: Long,
    /** Null for a file the library could not identify: it then opens straight in the player. */
    val kind: MediaKind?,
    val titleId: Long?,
    /** Set for an episode: playing it queues the rest of the series. */
    val episodeId: Long?,
    val title: String,
    val subtitle: String?,
    /** Landscape picture first (still, backdrop), poster as the last resort. */
    val imagePath: String?,
    val progress: Float,
    val remainingMinutes: Int,
    val isAvailable: Boolean
)

data class NextUpItem(
    val episodeId: Long,
    val seriesId: Long,
    val mediaFileId: Long,
    val seriesTitle: String,
    /** "S02E05 · Episode title". */
    val label: String,
    val imagePath: String?
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeRepository @Inject constructor(
    private val dao: LibraryDao,
    private val library: LibraryRepository,
    private val favorites: FavoritesRepository
) {

    val continueWatching: Flow<List<ContinueItem>> = dao.observeContinueWatching(CONTINUE_CANDIDATES).map(::continueItems)

    val nextUp: Flow<List<NextUpItem>> = dao.observeFollowedEpisodeStates().map { rows ->
        val next = NextUpResolver.resolve(rows.map(EpisodeStateRow::toState)).take(ROW_SIZE)
        if (next.isEmpty()) emptyList()
        else {
            val cards = dao.episodeCards(next.map { it.episodeId }).associateBy { it.episodeId }
            next.mapNotNull { target ->
                val card = cards[target.episodeId] ?: return@mapNotNull null
                NextUpItem(
                    episodeId = card.episodeId,
                    seriesId = card.seriesId,
                    mediaFileId = target.mediaFileId,
                    seriesTitle = card.seriesTitle,
                    label = episodeLabel(card.seasonNumber, card.episodeNumber, card.episodeTitle),
                    imagePath = card.stillPath ?: card.backdropPath ?: card.posterPath
                )
            }
        }
    }.flowOn(Dispatchers.Default)

    val recentlyAdded: Flow<List<LibraryItem>> = both(SortField.ADDED, SortOrder.DESC, LibraryFilters())

    val unwatched: Flow<List<LibraryItem>> = both(SortField.ADDED, SortOrder.DESC, LibraryFilters(watch = WatchFilter.UNWATCHED))

    fun shelf(section: LibrarySection): Flow<List<LibraryItem>> =
        library.observe(LibraryQuery(section, LibrarySort(SortField.TITLE, SortOrder.ASC), LibraryFilters(), ROW_SIZE))

    val favoriteTitles: Flow<List<LibraryItem>> = flow { emit(favorites.listId()) }.flatMapLatest { listId ->
        both(SortField.ADDED, SortOrder.DESC, LibraryFilters(userListId = listId))
    }

    /** Films and series merged under one sort, newest first (the rows that mix both shelves). */
    private fun both(field: SortField, order: SortOrder, filters: LibraryFilters): Flow<List<LibraryItem>> {
        val sort = LibrarySort(field, order)
        return combine(
            library.observe(LibraryQuery(LibrarySection.MOVIES, sort, filters, ROW_SIZE)),
            library.observe(LibraryQuery(LibrarySection.SERIES, sort, filters, ROW_SIZE))
        ) { movies, series -> (movies + series).sortedByDescending { it.addedAt }.take(ROW_SIZE) }
    }

    private fun continueItems(rows: List<ContinueRow>): List<ContinueItem> {
        val seenTitles = HashSet<String>()
        return rows.mapNotNull { row ->
            // One card per series (its latest episode) and per film (whatever the version), the most recent first
            val key = when {
                row.seriesId != null -> "S${row.seriesId}"
                row.movieId != null -> "M${row.movieId}"
                else -> "F${row.mediaFileId}"
            }
            if (!seenTitles.add(key)) return@mapNotNull null
            row.toItem()
        }.take(ROW_SIZE)
    }

    companion object {
        const val ROW_SIZE = 20
        private const val CONTINUE_CANDIDATES = 60

        fun episodeLabel(season: Int, episode: Int, title: String?): String =
            "S%02dE%02d".format(season, episode) + title?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    }
}

internal fun EpisodeStateRow.toState() = EpisodeFileState(
    seriesId, episodeId, seasonNumber, episodeNumber, mediaFileId, isAvailable, completed, positionMs, durationMs, lastWatchedAt, width, height, size
)

internal fun ContinueRow.toItem(): ContinueItem {
    val kind = when {
        seriesId != null -> MediaKind.SERIES
        movieId != null -> MediaKind.MOVIE
        else -> null
    }
    val isEpisode = episodeId != null && seasonNumber != null && episodeNumber != null
    return ContinueItem(
        mediaFileId = mediaFileId,
        kind = kind,
        titleId = seriesId ?: movieId,
        episodeId = episodeId,
        title = title ?: PlaybackRequestFactory.displayTitle(fileName),
        subtitle = if (isEpisode) HomeRepository.episodeLabel(seasonNumber!!, episodeNumber!!, episodeTitle) else null,
        imagePath = stillPath ?: backdropPath ?: posterPath,
        progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
        remainingMinutes = ((durationMs - positionMs).coerceAtLeast(0) / 60_000L).toInt(),
        isAvailable = isAvailable
    )
}
