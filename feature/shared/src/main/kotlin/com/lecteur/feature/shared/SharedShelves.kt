package com.lecteur.feature.shared

import com.lecteur.core.common.library.Catalog
import com.lecteur.core.common.library.EpisodeDto
import com.lecteur.core.common.library.FileDto
import com.lecteur.core.common.library.MovieDto
import com.lecteur.core.common.library.SeriesDto
import com.lecteur.core.common.library.best
import com.lecteur.core.common.library.progress

/** One card of a shelf of the shared library: what to show and what it opens. */
data class ShelfCard(
    val key: String,
    val title: String,
    val subtitle: String?,
    /** Artwork key of the PC (backdrop or still for wide cards, poster otherwise). */
    val image: String?,
    val progress: Float,
    val movieId: Long? = null,
    val seriesId: Long? = null,
    val episodeId: Long? = null
)

/** The home rows of a shared library, computed from the catalog alone (the watch state in it is the PC's). */
object SharedShelves {

    fun continueWatching(catalog: Catalog, limit: Int = 20): List<ShelfCard> {
        data class Started(val at: Long, val card: ShelfCard)
        val movies = catalog.movies.mapNotNull { m ->
            val file = m.files.filter { it.isStarted() }.maxByOrNull { it.watch!!.lastWatchedAt } ?: return@mapNotNull null
            val watch = file.watch!!
            Started(watch.lastWatchedAt, ShelfCard("m${m.id}", m.title, m.year?.toString(), m.backdrop ?: m.poster, watch.progress, movieId = m.id))
        }
        val episodes = catalog.series.mapNotNull { s ->
            val (episode, file) = s.episodes.flatMap { e -> e.files.filter { it.isStarted() }.map { e to it } }
                .maxByOrNull { it.second.watch!!.lastWatchedAt } ?: return@mapNotNull null
            val watch = file.watch!!
            Started(
                watch.lastWatchedAt,
                ShelfCard("e${episode.id}", s.title, episodeLabel(episode), episode.still ?: s.backdrop, watch.progress, seriesId = s.id, episodeId = episode.id)
            )
        }
        return (movies + episodes).sortedByDescending { it.at }.take(limit).map { it.card }
    }

    /** For each series already followed: the episode after the last one finished, if it is not started yet. */
    fun nextUp(catalog: Catalog, limit: Int = 20): List<ShelfCard> =
        catalog.series.mapNotNull { s ->
            val lastDone = s.episodes.filter { it.isCompleted() }.maxByOrNull { e -> e.files.maxOf { it.watch?.lastWatchedAt ?: 0L } } ?: return@mapNotNull null
            val at = lastDone.files.maxOf { it.watch?.lastWatchedAt ?: 0L }
            val next = s.episodes.dropWhile { it.id != lastDone.id }.drop(1).firstOrNull { !it.isCompleted() } ?: return@mapNotNull null
            if (next.files.any { it.isStarted() }) return@mapNotNull null
            at to ShelfCard("n${next.id}", s.title, episodeLabel(next), next.still ?: s.backdrop, 0f, seriesId = s.id, episodeId = next.id)
        }.sortedByDescending { it.first }.take(limit).map { it.second }

    fun recentlyAdded(catalog: Catalog, limit: Int = 20): List<ShelfCard> =
        (catalog.movies.map { it.addedAt to ShelfCard("m${it.id}", it.title, it.year?.toString(), it.poster, 0f, movieId = it.id) } +
            catalog.series.map { it.addedAt to ShelfCard("s${it.id}", it.title, it.year?.toString(), it.poster, 0f, seriesId = it.id) })
            .sortedByDescending { it.first }.take(limit).map { it.second }

    /** What "Lire" plays for a series: the episode in progress, else the one after the last seen, else the first. */
    fun seriesTarget(series: SeriesDto): EpisodeDto? {
        val episodes = series.episodes.filter { e -> e.files.any { it.available } }
        episodes.filter { e -> e.files.any { it.isStarted() } }.maxByOrNull { e -> e.files.maxOf { it.watch?.lastWatchedAt ?: 0L } }?.let { return it }
        val lastDone = episodes.lastOrNull { it.isCompleted() }
        return lastDone?.let { done -> episodes.dropWhile { it.id != done.id }.drop(1).firstOrNull { !it.isCompleted() } } ?: episodes.firstOrNull()
    }

    /** The episodes to queue when [from] is played: it and the following ones that can be played. */
    fun queueFrom(series: SeriesDto, from: EpisodeDto): List<Pair<EpisodeDto, FileDto>> =
        series.episodes.dropWhile { it.id != from.id }.mapNotNull { e -> e.files.filter { it.available }.best()?.let { e to it } }

    fun movieFile(movie: MovieDto): FileDto? = movie.files.filter { it.available }.best() ?: movie.files.best()

    fun episodeLabel(e: EpisodeDto): String = "S%02dE%02d".format(e.season, e.number) + (e.title?.let { " · $it" } ?: "")

    fun EpisodeDto.isCompleted(): Boolean = files.any { it.watch?.completed == true }

    fun FileDto.isStarted(): Boolean = watch?.let { !it.completed && it.progress in 0.02f..0.98f } == true
}
