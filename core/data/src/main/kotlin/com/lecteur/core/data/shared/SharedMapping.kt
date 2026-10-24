package com.lecteur.core.data.shared

import com.lecteur.core.common.library.CreditDto
import com.lecteur.core.common.library.EpisodeDto
import com.lecteur.core.common.library.FileDto
import com.lecteur.core.common.library.MovieDto
import com.lecteur.core.common.library.SeriesDto
import com.lecteur.core.common.library.WatchDto
import com.lecteur.core.common.library.best
import com.lecteur.core.model.CastCredit
import com.lecteur.core.model.EpisodeItem
import com.lecteur.core.model.FileVersion
import com.lecteur.core.model.Genre
import com.lecteur.core.model.HdrType
import com.lecteur.core.model.LibraryItem
import com.lecteur.core.model.MatchState
import com.lecteur.core.model.MediaBadges
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.Movie
import com.lecteur.core.model.MovieDetail
import com.lecteur.core.model.SeasonDetail
import com.lecteur.core.model.Series
import com.lecteur.core.model.SeriesDetail
import com.lecteur.core.model.SeriesPlayKind
import com.lecteur.core.model.SeriesPlayTarget
import com.lecteur.core.model.VideoCodec
import com.lecteur.core.model.WatchState
import com.lecteur.core.model.WatchStatus

/**
 * A PC's catalog in the app's own models, so the screens of the local library (detail pages, posters, cast) show shared
 * titles as they are. Ids are the PC's: callers keep the server id next to them. [art] turns an artwork key into the
 * full URL on the PC, which [com.lecteur.core.model.ImageUrls] passes through untouched.
 */
class SharedMapping(private val art: (String?) -> String?) {

    fun movieItem(m: MovieDto): LibraryItem {
        val files = m.files
        val watched = files.any { it.watch?.completed == true }
        val progress = files.maxOfOrNull { it.progressOrZero() } ?: 0f
        return LibraryItem(
            kind = MediaKind.MOVIE, id = m.id, title = m.title, year = m.year, posterPath = art(m.poster), rating = m.rating,
            watch = status(watched, progress), progress = if (watched) 0f else progress, unitCount = 1,
            watchedCount = if (watched) 1 else 0, isAvailable = files.any { it.available }, addedAt = m.addedAt,
            matchState = MatchState.IDENTIFIED
        )
    }

    fun seriesItem(s: SeriesDto): LibraryItem {
        val done = s.episodes.count { e -> e.files.any { it.watch?.completed == true } }
        val started = s.episodes.any { e -> e.files.any { it.progressOrZero() > 0f } }
        return LibraryItem(
            kind = MediaKind.SERIES, id = s.id, title = s.title, year = s.year, posterPath = art(s.poster), rating = s.rating,
            watch = when {
                s.episodes.isNotEmpty() && done == s.episodes.size -> WatchStatus.WATCHED
                done > 0 || started -> WatchStatus.IN_PROGRESS
                else -> WatchStatus.UNWATCHED
            },
            progress = 0f, unitCount = s.episodes.size, watchedCount = done, isAvailable = s.episodes.any { e -> e.files.any { it.available } },
            addedAt = s.addedAt, matchState = MatchState.IDENTIFIED
        )
    }

    fun movieDetail(m: MovieDto): MovieDetail {
        val versions = m.files.sortedWith(VERSION_ORDER).map(::version)
        val watched = m.files.any { it.watch?.completed == true }
        val progress = m.files.filter { it.watch?.completed != true }.maxOfOrNull { it.progressOrZero() } ?: 0f
        return MovieDetail(
            movie = Movie(
                id = m.id, tmdbId = m.tmdbId, imdbId = m.imdbId, title = m.title, originalTitle = m.originalTitle, year = m.year,
                releaseDate = m.releaseDate, overview = m.overview, runtimeMinutes = m.runtimeMinutes, rating = m.rating,
                certification = m.certification, posterPath = art(m.poster), backdropPath = art(m.backdrop), trailerKey = m.trailerKey,
                addedAt = m.addedAt, matchState = MatchState.IDENTIFIED
            ),
            genres = genres(m.genres),
            directors = m.cast.filter { it.director }.map(::credit),
            cast = m.cast.filterNot { it.director }.map(::credit),
            versions = versions,
            collection = null,
            isFavorite = false,
            watch = status(watched, progress),
            progress = progress
        )
    }

    fun seriesDetail(s: SeriesDto): SeriesDetail {
        val episodes = s.episodes.map(::episode)
        val seasons = episodes.groupBy { it.seasonNumber }.toSortedMap().map { (number, list) ->
            val info = s.seasons.firstOrNull { it.number == number }
            SeasonDetail(number, info?.name, art(info?.poster), list)
        }
        return SeriesDetail(
            series = Series(
                id = s.id, tmdbId = s.tmdbId, imdbId = s.imdbId, title = s.title, originalTitle = s.originalTitle,
                firstAirDate = s.firstAirDate, status = s.status, overview = s.overview, rating = s.rating, certification = s.certification,
                posterPath = art(s.poster), backdropPath = art(s.backdrop), addedAt = s.addedAt, matchState = MatchState.IDENTIFIED
            ),
            genres = genres(s.genres),
            cast = s.cast.filterNot { it.director }.map(::credit),
            seasons = seasons,
            isFavorite = false,
            preference = null,
            playTarget = playTarget(episodes),
            watch = when {
                episodes.isNotEmpty() && episodes.all { it.watch == WatchStatus.WATCHED } -> WatchStatus.WATCHED
                episodes.any { it.watch != WatchStatus.UNWATCHED } -> WatchStatus.IN_PROGRESS
                else -> WatchStatus.UNWATCHED
            }
        )
    }

    private fun episode(e: EpisodeDto): EpisodeItem {
        val watched = e.files.any { it.watch?.completed == true }
        val progress = if (watched) 0f else e.files.maxOfOrNull { it.progressOrZero() } ?: 0f
        return EpisodeItem(
            episodeId = e.id, seasonNumber = e.season, episodeNumber = e.number, title = e.title, overview = e.overview,
            stillPath = art(e.still), airDate = e.airDate, runtimeMinutes = e.runtimeMinutes,
            versions = e.files.sortedWith(VERSION_ORDER).map(::version), watch = status(watched, progress), progress = progress
        )
    }

    /** The episode in progress, else the one after the last seen, else the first; all seen: watch again from the start. */
    private fun playTarget(episodes: List<EpisodeItem>): SeriesPlayTarget? {
        val playable = episodes.filter { it.isAvailable }
        playable.firstOrNull { it.watch == WatchStatus.IN_PROGRESS }?.let { return SeriesPlayTarget(it, SeriesPlayKind.RESUME) }
        val lastDone = playable.indexOfLast { it.watch == WatchStatus.WATCHED }
        if (lastDone < 0) return playable.firstOrNull()?.let { SeriesPlayTarget(it, SeriesPlayKind.START) }
        playable.drop(lastDone + 1).firstOrNull { it.watch != WatchStatus.WATCHED }?.let { return SeriesPlayTarget(it, SeriesPlayKind.NEXT) }
        return playable.firstOrNull()?.let { SeriesPlayTarget(it, SeriesPlayKind.REWATCH) }
    }

    private fun version(f: FileDto) = FileVersion(
        mediaFileId = f.id,
        fileName = f.fileName,
        displayPath = f.fileName,
        sizeBytes = f.sizeBytes,
        durationMs = f.durationMs,
        isAvailable = f.available,
        badges = MediaBadges(
            resolution = resolution(f.width, f.height),
            hdr = f.hdr?.let { runCatching { HdrType.valueOf(it) }.getOrNull() } ?: HdrType.NONE,
            videoCodec = f.videoCodec?.let { runCatching { VideoCodec.valueOf(it) }.getOrNull() } ?: VideoCodec.UNKNOWN,
            audio = f.audio,
            audioLanguages = f.audioLanguages,
            subtitleLanguages = f.subtitleLanguages
        ),
        watch = f.watch?.let { watchState(f.id, it) }
    )

    private fun credit(c: CreditDto) = CastCredit(c.personId, c.name, c.role, art(c.profile), c.director)

    /** Genres have no id across devices: their name stands for it. */
    private fun genres(names: List<String>) = names.map { Genre(id = it.hashCode().toLong(), tmdbId = null, name = it) }

    companion object {
        fun watchState(fileId: Long, w: WatchDto) = WatchState(
            mediaFileId = fileId, positionMs = w.positionMs, durationMs = w.durationMs, isCompleted = w.completed,
            lastWatchedAt = w.lastWatchedAt, selectedAudioTrackIndex = w.audioIndex, selectedSubtitleTrackIndex = w.subtitleIndex,
            audioDelayMs = w.audioDelayMs, subtitleDelayMs = w.subtitleDelayMs
        )

        /** Same tiers as the local library: a cropped widescreen 1080p is still 1080p. */
        fun resolution(width: Int?, height: Int?): String? {
            val w = width ?: 0
            val h = height ?: 0
            return when {
                w <= 0 && h <= 0 -> null
                w >= 3000 || h >= 2000 -> "4K"
                w >= 1800 || h >= 1000 -> "1080p"
                w >= 1200 || h >= 700 -> "720p"
                else -> "SD"
            }
        }

        private fun status(watched: Boolean, progress: Float) = when {
            watched -> WatchStatus.WATCHED
            progress > 0f -> WatchStatus.IN_PROGRESS
            else -> WatchStatus.UNWATCHED
        }

        private fun FileDto.progressOrZero(): Float =
            watch?.takeIf { !it.completed && it.durationMs > 0 }?.let { (it.positionMs.toFloat() / it.durationMs).coerceIn(0f, 1f) } ?: 0f

        /** The version "Lire" picks first: available, then sharpest (see [best]). */
        private val VERSION_ORDER = compareByDescending<FileDto> { it.available }.thenByDescending { it.height ?: 0 }.thenByDescending { it.sizeBytes }
    }
}
