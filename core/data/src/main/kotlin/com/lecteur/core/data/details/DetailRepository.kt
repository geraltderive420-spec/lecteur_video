package com.lecteur.core.data.details

import com.lecteur.core.common.playback.EpisodeFileState
import com.lecteur.core.common.playback.NextUpResolver
import com.lecteur.core.data.library.FavoritesRepository
import com.lecteur.core.data.library.toItem
import com.lecteur.core.database.dao.LibraryDao
import com.lecteur.core.database.dao.MetadataDao
import com.lecteur.core.database.dao.MovieDao
import com.lecteur.core.database.dao.SeriesDao
import com.lecteur.core.database.entity.CastMemberEntity
import com.lecteur.core.database.entity.GenreEntity
import com.lecteur.core.database.entity.MovieEntity
import com.lecteur.core.database.entity.SeriesEntity
import com.lecteur.core.database.entity.SeriesPreferenceEntity
import com.lecteur.core.database.entity.WatchStateEntity
import com.lecteur.core.database.query.LibraryQueryBuilder
import com.lecteur.core.database.relation.EpisodeDetailRow
import com.lecteur.core.model.CastCredit
import com.lecteur.core.model.CollectionInfo
import com.lecteur.core.model.EpisodeItem
import com.lecteur.core.model.FileVersion
import com.lecteur.core.model.Genre
import com.lecteur.core.model.LibraryFilters
import com.lecteur.core.model.LibraryQuery
import com.lecteur.core.model.LibrarySection
import com.lecteur.core.model.LibrarySort
import com.lecteur.core.model.MediaCollection
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.Movie
import com.lecteur.core.model.MovieDetail
import com.lecteur.core.model.Person
import com.lecteur.core.model.SeasonDetail
import com.lecteur.core.model.Series
import com.lecteur.core.model.SeriesDetail
import com.lecteur.core.model.SeriesPlayTarget
import com.lecteur.core.model.SeriesPreference
import com.lecteur.core.model.SortField
import com.lecteur.core.model.SortOrder
import com.lecteur.core.model.WatchState
import com.lecteur.core.model.WatchStatus
import com.lecteur.core.model.HdrType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject

/** Everything the detail pages show, kept live: a played minute or a rescan updates the page in place. */
class DetailRepository @Inject constructor(
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val libraryDao: LibraryDao,
    private val metadataDao: MetadataDao,
    private val favorites: FavoritesRepository
) {

    fun observeMovie(movieId: Long): Flow<MovieDetail?> = combine(
        movieDao.observeMovieDetail(movieId),
        libraryDao.observeWatchStatesForMovie(movieId),
        libraryDao.observeAudioTracksForMovie(movieId),
        libraryDao.observeSubtitleTracksForMovie(movieId),
        favorites.observeMovie(movieId)
    ) { relation, states, audio, subtitles, favorite ->
        if (relation == null) return@combine null
        val movie = relation.movie
        val statesByFile = states.associateBy { it.mediaFileId }
        val audioByFile = audio.groupBy { it.mediaFileId }
        val subtitlesByFile = subtitles.groupBy { it.mediaFileId }

        val versions = relation.files.map { file ->
            FileVersion(
                mediaFileId = file.id,
                fileName = file.fileName,
                displayPath = file.displayPath,
                sizeBytes = file.size,
                durationMs = file.durationMs,
                isAvailable = file.isAvailable,
                badges = Badges.forFile(file, audioByFile[file.id].orEmpty(), subtitlesByFile[file.id].orEmpty()),
                watch = statesByFile[file.id]?.toModel()
            )
        }.sortedWith(VERSION_ORDER)

        val (watch, progress) = watchOf(states)
        MovieDetail(
            movie = movie.toModel(),
            genres = relation.genres.map(GenreEntity::toModel),
            directors = relation.cast.filter { it.isDirector }.sortedBy { it.order }.map(CastMemberEntity::toCredit),
            cast = relation.cast.filter { !it.isDirector }.sortedBy { it.order }.map(CastMemberEntity::toCredit),
            versions = versions,
            collection = collectionOf(movie),
            isFavorite = favorite,
            watch = watch,
            progress = progress
        )
    }.distinctUntilChanged()

    fun observeSeries(seriesId: Long): Flow<SeriesDetail?> = combine(
        seriesDao.observeSeriesDetail(seriesId),
        libraryDao.observeEpisodeRows(seriesId),
        favorites.observeSeries(seriesId),
        metadataDao.observeSeriesPreference(seriesId)
    ) { relation, rows, favorite, preference ->
        if (relation == null) return@combine null
        val episodes = episodeItems(rows)
        val episodesBySeason = episodes.groupBy { it.seasonNumber }
        val seasons = relation.seasons.sortedWith(compareBy({ it.seasonNumber == 0 }, { it.seasonNumber })).map { season ->
            SeasonDetail(season.seasonNumber, season.name, season.posterPath, episodesBySeason[season.seasonNumber].orEmpty())
        }

        val summaries = NextUpResolver.summarize(
            rows.filter { it.mediaFileId != null }.map { it.toFileState(seriesId) }
        )
        val episodeById = episodes.associateBy { it.episodeId }
        val target = NextUpResolver.playTarget(summaries)?.let { (summary, kind) ->
            episodeById[summary.episodeId]?.let { SeriesPlayTarget(it, kind) }
        }

        SeriesDetail(
            series = relation.series.toModel(),
            genres = relation.genres.map(GenreEntity::toModel),
            cast = relation.cast.sortedBy { it.order }.map(CastMemberEntity::toCredit),
            seasons = seasons,
            isFavorite = favorite,
            preference = preference?.let { SeriesPreference(it.seriesId, it.preferredAudioLanguage, it.preferredSubtitleLanguage) },
            playTarget = target,
            watch = seriesWatch(episodes)
        )
    }.distinctUntilChanged()

    /** The audio and subtitle languages this series is played in; null keeps the global choice for that kind of track. */
    suspend fun setSeriesPreference(seriesId: Long, audioLanguage: String?, subtitleLanguage: String?) {
        metadataDao.setSeriesPreference(SeriesPreferenceEntity(seriesId, audioLanguage, subtitleLanguage))
    }

    suspend fun person(personId: Long): Person? =
        libraryDao.person(personId)?.let { Person(it.id, it.tmdbId, it.name, it.profilePath) }

    suspend fun genreName(genreId: Long): String? = libraryDao.genreName(genreId)

    suspend fun collection(collectionId: Long): MediaCollection? =
        metadataDao.getCollectionById(collectionId)?.let {
            MediaCollection(it.id, it.tmdbId, it.name, it.overview, it.posterPath, it.backdropPath)
        }

    private suspend fun collectionOf(movie: MovieEntity): CollectionInfo? {
        val collectionId = movie.collectionId ?: return null
        val collection = metadataDao.getCollectionById(collectionId) ?: return null
        val query = LibraryQuery(
            LibrarySection.MOVIES, LibrarySort(SortField.RELEASE, SortOrder.ASC), LibraryFilters(collectionId = collectionId)
        )
        val others = libraryDao.rows(LibraryQueryBuilder.build(query)).filter { it.id != movie.id }.map { it.toItem(MediaKind.MOVIE) }
        return CollectionInfo(collection.id, collection.name, others)
    }

    private fun episodeItems(rows: List<EpisodeDetailRow>): List<EpisodeItem> =
        rows.groupBy { it.episodeId }.values.map { group ->
            val first = group.first()
            val versions = group.filter { it.mediaFileId != null }.map { it.toVersion() }.sortedWith(VERSION_ORDER)
            val states = versions.mapNotNull { it.watch }
            EpisodeItem(
                episodeId = first.episodeId,
                seasonNumber = first.seasonNumber,
                episodeNumber = first.episodeNumber,
                title = first.title,
                overview = first.overview,
                stillPath = first.stillPath,
                airDate = first.airDate,
                runtimeMinutes = first.runtime,
                versions = versions,
                watch = statusOf(states),
                progress = progressOf(states)
            )
        }.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))

    private fun seriesWatch(episodes: List<EpisodeItem>): WatchStatus {
        val present = episodes.filter { it.isInLibrary }
        return when {
            present.isNotEmpty() && present.all { it.watch == WatchStatus.WATCHED } -> WatchStatus.WATCHED
            present.any { it.watch != WatchStatus.UNWATCHED } -> WatchStatus.IN_PROGRESS
            else -> WatchStatus.UNWATCHED
        }
    }

    private fun watchOf(states: List<WatchStateEntity>): Pair<WatchStatus, Float> {
        val models = states.map { it.toModel() }
        return statusOf(models) to progressOf(models)
    }

    private fun statusOf(states: List<WatchState>): WatchStatus = when {
        states.any { it.isCompleted } -> WatchStatus.WATCHED
        states.any { it.progressPercentage > NOT_STARTED } -> WatchStatus.IN_PROGRESS
        else -> WatchStatus.UNWATCHED
    }

    private fun progressOf(states: List<WatchState>): Float =
        states.filter { !it.isCompleted && it.progressPercentage > NOT_STARTED }.maxOfOrNull { it.progressPercentage } ?: 0f

    companion object {
        private const val NOT_STARTED = 0.02f

        /** Copies you can play first, then the sharpest, then the biggest. */
        val VERSION_ORDER: Comparator<FileVersion> =
            compareByDescending<FileVersion> { it.isAvailable }
                .thenByDescending { resolutionRank(it.badges.resolution) }
                .thenByDescending { it.sizeBytes }
                .thenBy { it.mediaFileId }

        private fun resolutionRank(badge: String?) = when (badge) {
            "4K" -> 4
            "1080p" -> 3
            "720p" -> 2
            "SD" -> 1
            else -> 0
        }
    }
}

private fun EpisodeDetailRow.toVersion(): FileVersion {
    val duration = watchDurationMs?.takeIf { it > 0 } ?: fileDurationMs ?: 0
    val fileId = checkNotNull(mediaFileId) { "A version needs a file" }
    val finished = completed
    return FileVersion(
        mediaFileId = fileId,
        fileName = fileName.orEmpty(),
        displayPath = fileName.orEmpty(),
        sizeBytes = size ?: 0,
        durationMs = fileDurationMs,
        isAvailable = isAvailable ?: true,
        badges = Badges.fromColumns(width, height, runCatching { HdrType.valueOf(hdrType.orEmpty()) }.getOrDefault(HdrType.NONE)),
        watch = if (finished == null) null else WatchState(
            mediaFileId = fileId,
            positionMs = positionMs ?: 0,
            durationMs = duration,
            isCompleted = finished,
            lastWatchedAt = lastWatchedAt ?: 0
        )
    )
}

private fun EpisodeDetailRow.toFileState(seriesId: Long) = EpisodeFileState(
    seriesId = seriesId,
    episodeId = episodeId,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    mediaFileId = mediaFileId!!,
    isAvailable = isAvailable ?: true,
    completed = completed ?: false,
    positionMs = positionMs ?: 0,
    durationMs = watchDurationMs ?: 0,
    lastWatchedAt = lastWatchedAt ?: 0,
    width = width,
    height = height,
    size = size ?: 0
)

private fun WatchStateEntity.toModel() = WatchState(
    id = id,
    mediaFileId = mediaFileId,
    positionMs = positionMs,
    durationMs = durationMs,
    isCompleted = isCompleted,
    playCount = playCount,
    lastWatchedAt = lastWatchedAt,
    selectedAudioTrackIndex = selectedAudioTrack,
    selectedSubtitleTrackIndex = selectedSubtitleTrack,
    audioDelayMs = audioDelayMs,
    subtitleDelayMs = subtitleDelayMs,
    displayMode = displayMode
)

private fun GenreEntity.toModel() = Genre(id, tmdbId, name)

private fun CastMemberEntity.toCredit() = CastCredit(personId, personName, character, profilePath, isDirector)

internal fun MovieEntity.toModel() = Movie(
    id = id, tmdbId = tmdbId, imdbId = imdbId, title = title, originalTitle = originalTitle, sortTitle = sortTitle, year = year,
    releaseDate = releaseDate, overview = overview, runtimeMinutes = runtime, rating = rating, certification = certification,
    posterPath = posterPath, backdropPath = backdropPath, logoPath = logoPath, trailerKey = trailerKey, collectionId = collectionId,
    addedAt = addedAt, matchState = matchState, matchLocked = matchLocked
)

internal fun SeriesEntity.toModel() = Series(
    id = id, tmdbId = tmdbId, imdbId = imdbId, title = title, originalTitle = originalTitle, sortTitle = sortTitle,
    firstAirDate = firstAirDate, status = status, overview = overview, rating = rating, certification = certification,
    posterPath = posterPath, backdropPath = backdropPath, logoPath = logoPath, addedAt = addedAt, matchState = matchState,
    matchLocked = matchLocked
)
