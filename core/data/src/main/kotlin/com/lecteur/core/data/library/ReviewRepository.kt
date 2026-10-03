package com.lecteur.core.data.library

import com.lecteur.core.database.dao.MediaFileDao
import com.lecteur.core.database.dao.MovieDao
import com.lecteur.core.database.dao.SeriesDao
import com.lecteur.core.model.MatchState
import com.lecteur.core.model.MediaKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** A movie or series whose match is missing or doubtful, as listed on the "to verify" screen. */
data class ReviewItem(
    val kind: MediaKind,
    val id: Long,
    val title: String,
    val year: Int?,
    val state: MatchState,
    val posterPath: String?,
    val tmdbId: Long?,
    /** One of the files behind it, to help the user recognise what it is when the title is wrong. */
    val sampleFileName: String?
)

class ReviewRepository @Inject constructor(
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val mediaFileDao: MediaFileDao
) {

    /** Doubtful matches first (they have a proposal), then the unknown ones, each group alphabetical. */
    val items: Flow<List<ReviewItem>> = combine(movieDao.observeMoviesToReview(), seriesDao.observeSeriesToReview()) { movies, series ->
        val movieItems = movies.map {
            ReviewItem(MediaKind.MOVIE, it.id, it.title, it.year, it.matchState, it.posterPath, it.tmdbId, mediaFileDao.getMediaFilesForMovie(it.id).firstOrNull()?.fileName)
        }
        val seriesItems = series.map {
            ReviewItem(
                MediaKind.SERIES, it.id, it.title, it.firstAirDate?.take(4)?.toIntOrNull(), it.matchState, it.posterPath, it.tmdbId,
                mediaFileDao.getFilesForSeries(it.id).firstOrNull()?.fileName
            )
        }
        (movieItems + seriesItems).sortedWith(compareBy<ReviewItem> { it.state != MatchState.TO_VERIFY }.thenBy { it.title.lowercase() })
    }

    /** One title as the correction dialog needs it, whatever its match state (the "Corriger l'association" action of a detail page). */
    suspend fun find(kind: MediaKind, id: Long): ReviewItem? = when (kind) {
        MediaKind.MOVIE -> movieDao.getMovieById(id)?.let {
            ReviewItem(MediaKind.MOVIE, it.id, it.title, it.year, it.matchState, it.posterPath, it.tmdbId, mediaFileDao.getMediaFilesForMovie(it.id).firstOrNull()?.fileName)
        }
        MediaKind.SERIES -> seriesDao.getSeriesById(id)?.let {
            ReviewItem(
                MediaKind.SERIES, it.id, it.title, it.firstAirDate?.take(4)?.toIntOrNull(), it.matchState, it.posterPath, it.tmdbId,
                mediaFileDao.getFilesForSeries(it.id).firstOrNull()?.fileName
            )
        }
    }

    val count: Flow<Int> = combine(movieDao.observeReviewCount(), seriesDao.observeReviewCount()) { movies, series -> movies + series }
}
