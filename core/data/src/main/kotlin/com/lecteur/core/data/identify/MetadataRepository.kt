package com.lecteur.core.data.identify

import com.lecteur.core.common.match.MatchCandidate
import com.lecteur.core.common.match.MatchQuery
import com.lecteur.core.common.match.MatchScorer
import com.lecteur.core.common.match.TitleSimilarity
import com.lecteur.core.common.scan.EpisodeNumbering
import com.lecteur.core.data.library.LibraryWriteLock
import com.lecteur.core.database.dao.EpisodeDao
import com.lecteur.core.database.dao.MediaFileDao
import com.lecteur.core.database.dao.MetadataDao
import com.lecteur.core.database.dao.MovieDao
import com.lecteur.core.database.dao.SeasonDao
import com.lecteur.core.database.dao.SeriesAliasDao
import com.lecteur.core.database.dao.SeriesDao
import com.lecteur.core.database.entity.CastMemberEntity
import com.lecteur.core.database.entity.CollectionEntity
import com.lecteur.core.database.entity.EpisodeEntity
import com.lecteur.core.database.entity.GenreEntity
import com.lecteur.core.database.entity.MovieEntity
import com.lecteur.core.database.entity.MovieGenreCrossRef
import com.lecteur.core.database.entity.PersonEntity
import com.lecteur.core.database.entity.SeasonEntity
import com.lecteur.core.database.entity.SeriesAliasEntity
import com.lecteur.core.database.entity.SeriesEntity
import com.lecteur.core.database.entity.SeriesGenreCrossRef
import com.lecteur.core.model.CastRef
import com.lecteur.core.model.GenreRef
import com.lecteur.core.model.ImageUrls
import com.lecteur.core.model.MatchState
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.MetadataException
import com.lecteur.core.model.MetadataProvider
import com.lecteur.core.model.MetadataSearchHit
import com.lecteur.core.model.MovieMetadata
import com.lecteur.core.model.SeriesMetadata
import javax.inject.Inject

/**
 * How an online result lands on a row.
 * [FILL_LOCAL_FIRST]: automatic identification: what a local sidecar already provided (synopsis, rating, artwork) wins.
 * [OVERWRITE]: manual correction or refresh: the online result replaces the previous one, except local artwork.
 */
enum class ApplyMode { FILL_LOCAL_FIRST, OVERWRITE }

data class IdentifyResult(
    val identified: Int = 0,
    val toVerify: Int = 0,
    val unidentified: Int = 0,
    /** The run stopped because the network is down: the remaining items are retried later. */
    val offline: Boolean = false,
    /** No TMDB key in this build: nothing was attempted. */
    val notConfigured: Boolean = false
) {
    val processed: Int get() = identified + toVerify + unidentified
}

class MetadataRepository @Inject constructor(
    private val provider: MetadataProvider,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val seasonDao: SeasonDao,
    private val episodeDao: EpisodeDao,
    private val metadataDao: MetadataDao,
    private val mediaFileDao: MediaFileDao,
    private val aliasDao: SeriesAliasDao,
    private val prefetcher: ImagePrefetcher,
    private val writeLock: LibraryWriteLock
) {

    // region automatic identification

    /**
     * Looks up every movie and series that has no match yet (never tried, or last tried more than [retryAfterMs] ago).
     * Stops early, without marking anything as tried, when the network is down or there is no API key.
     */
    suspend fun identifyPending(
        now: () -> Long = System::currentTimeMillis,
        retryAfterMs: Long = RETRY_AFTER_MS,
        batchSize: Int = 100,
        onProgress: (done: Int) -> Unit = {}
    ): IdentifyResult {
        if (!provider.isConfigured) return IdentifyResult(notConfigured = true)

        var result = IdentifyResult()
        try {
            while (true) {
                val retryBefore = now() - retryAfterMs
                val movies = movieDao.getMoviesToIdentify(retryBefore, batchSize)
                for (movie in movies) {
                    result = result.count(identifyMovie(movie, now))
                    onProgress(result.processed)
                }
                val series = seriesDao.getSeriesToIdentify(retryBefore, batchSize)
                for (show in series) {
                    result = result.count(identifySeries(show, now))
                    onProgress(result.processed)
                }
                if (movies.isEmpty() && series.isEmpty()) break
            }
        } catch (e: MetadataException.Offline) {
            return result.copy(offline = true)
        } catch (e: MetadataException.NotConfigured) {
            return result.copy(notConfigured = true)
        }
        return result
    }

    private fun IdentifyResult.count(state: MatchState) = when (state) {
        MatchState.IDENTIFIED -> copy(identified = identified + 1)
        MatchState.TO_VERIFY -> copy(toVerify = toVerify + 1)
        MatchState.UNIDENTIFIED -> copy(unidentified = unidentified + 1)
    }

    private suspend fun identifyMovie(movie: MovieEntity, now: () -> Long): MatchState {
        try {
            // A sidecar that gave the TMDB id: no search needed
            movie.tmdbId?.let { id ->
                applyMovie(movie.id, provider.movieDetails(id), ApplyMode.FILL_LOCAL_FIRST, MatchState.IDENTIFIED, lock = false, now = now)
                return MatchState.IDENTIFIED
            }

            val runtimeMinutes = mediaFileDao.getMediaFilesForMovie(movie.id).mapNotNull { it.durationMs }.maxOrNull()?.let { (it / 60_000).toInt() }
            val query = MatchQuery(movie.title, movie.year, runtimeMinutes)

            val hits = searchWithFallbacks(MediaKind.MOVIE, movie.title, movie.originalTitle, movie.year)
            if (hits.isEmpty()) return markTried(movie.id, now)

            val candidates = hits.map { MatchCandidate(it.tmdbId, it.title, it.originalTitle, it.year, popularity = it.popularity) }
            // Search results carry no duration: fetch the leading candidate, then judge again with its runtime
            val leader = provider.movieDetails(MatchScorer.rank(query.copy(runtimeMinutes = null), candidates).first().candidate.id)
            val refined = candidates.map { if (it.id == leader.tmdbId) it.copy(runtimeMinutes = leader.runtimeMinutes) else it }
            val decision = MatchScorer.decide(query, refined)

            val best = decision.best ?: return markTried(movie.id, now)
            val metadata = if (best.candidate.id == leader.tmdbId) leader else provider.movieDetails(best.candidate.id)
            applyMovie(movie.id, metadata, ApplyMode.FILL_LOCAL_FIRST, decision.state, lock = false, now = now)
            return decision.state
        } catch (e: MetadataException.NotFound) {
            // The id from a sidecar does not exist on TMDB: fall back to being unidentified rather than failing the run
            return markTried(movie.id, now)
        }
    }

    private suspend fun identifySeries(series: SeriesEntity, now: () -> Long): MatchState {
        try {
            series.tmdbId?.let { id ->
                applySeries(series.id, provider.seriesDetails(id), ApplyMode.FILL_LOCAL_FIRST, MatchState.IDENTIFIED, lock = false, now = now)
                return MatchState.IDENTIFIED
            }

            val year = series.firstAirDate?.take(4)?.toIntOrNull()
            val hits = searchWithFallbacks(MediaKind.SERIES, series.title, series.originalTitle, year)
            if (hits.isEmpty()) return markTried(series.id, now, series = true)

            val candidates = hits.map { MatchCandidate(it.tmdbId, it.title, it.originalTitle, it.year, popularity = it.popularity) }
            val decision = MatchScorer.decide(MatchQuery(series.title, year), candidates)
            val best = decision.best ?: return markTried(series.id, now, series = true)

            applySeries(series.id, provider.seriesDetails(best.candidate.id), ApplyMode.FILL_LOCAL_FIRST, decision.state, lock = false, now = now)
            return decision.state
        } catch (e: MetadataException.NotFound) {
            return markTried(series.id, now, series = true)
        }
    }

    /** Title with its year, then without (the year in a file name is often the wrong one), then the original title. */
    private suspend fun searchWithFallbacks(kind: MediaKind, title: String, originalTitle: String?, year: Int?): List<MetadataSearchHit> {
        provider.search(kind, title, year).takeIf { it.isNotEmpty() }?.let { return it }
        if (year != null) provider.search(kind, title, null).takeIf { it.isNotEmpty() }?.let { return it }
        if (!originalTitle.isNullOrBlank() && !originalTitle.equals(title, ignoreCase = true)) {
            return provider.search(kind, originalTitle, year)
        }
        return emptyList()
    }

    private suspend fun markTried(id: Long, now: () -> Long, series: Boolean = false): MatchState {
        writeLock.withLock { if (series) seriesDao.setMatchAttempted(id, now()) else movieDao.setMatchAttempted(id, now()) }
        return MatchState.UNIDENTIFIED
    }

    // endregion

    // region manual correction

    /** Search for the correction dialog: whatever the user typed, no scoring. */
    suspend fun search(kind: MediaKind, query: String, year: Int? = null): List<MetadataSearchHit> {
        if (!provider.isConfigured) throw MetadataException.NotConfigured()
        return provider.search(kind, query.trim(), year)
    }

    /**
     * Associates a movie or series with a chosen TMDB id and locks it so the next scan cannot undo the choice.
     * @return the id of the surviving row: another row already holding that TMDB id absorbs this one.
     */
    suspend fun applyManual(kind: MediaKind, localId: Long, tmdbId: Long, now: () -> Long = System::currentTimeMillis): Long = when (kind) {
        MediaKind.MOVIE -> applyMovie(localId, provider.movieDetails(tmdbId), ApplyMode.OVERWRITE, MatchState.IDENTIFIED, lock = true, now = now)
        MediaKind.SERIES -> applySeries(localId, provider.seriesDetails(tmdbId), ApplyMode.OVERWRITE, MatchState.IDENTIFIED, lock = true, now = now)
    }

    /** Fetches the current TMDB data again for an item that already has a match (the "refresh metadata" action). */
    suspend fun refresh(kind: MediaKind, localId: Long, now: () -> Long = System::currentTimeMillis): Boolean {
        when (kind) {
            MediaKind.MOVIE -> {
                val movie = movieDao.getMovieById(localId) ?: return false
                val tmdbId = movie.tmdbId ?: return false
                applyMovie(localId, provider.movieDetails(tmdbId), ApplyMode.OVERWRITE, movie.matchState, movie.matchLocked, now)
            }
            MediaKind.SERIES -> {
                val series = seriesDao.getSeriesById(localId) ?: return false
                val tmdbId = series.tmdbId ?: return false
                applySeries(localId, provider.seriesDetails(tmdbId), ApplyMode.OVERWRITE, series.matchState, series.matchLocked, now)
            }
        }
        return true
    }

    /** The user accepts the proposed match of a "to verify" item. */
    suspend fun confirm(kind: MediaKind, localId: Long) {
        when (kind) {
            MediaKind.MOVIE -> movieDao.getMovieById(localId)?.let { movieDao.updateMovie(it.copy(matchState = MatchState.IDENTIFIED, matchLocked = true)) }
            MediaKind.SERIES -> seriesDao.getSeriesById(localId)?.let { seriesDao.updateSeries(it.copy(matchState = MatchState.IDENTIFIED, matchLocked = true)) }
        }
    }

    /** Lets automatic identification handle the item again. */
    suspend fun unlock(kind: MediaKind, localId: Long) {
        when (kind) {
            MediaKind.MOVIE -> movieDao.getMovieById(localId)?.let { movieDao.updateMovie(it.copy(matchLocked = false, matchAttemptedAt = null)) }
            MediaKind.SERIES -> seriesDao.getSeriesById(localId)?.let { seriesDao.updateSeries(it.copy(matchLocked = false, matchAttemptedAt = null)) }
        }
    }

    // endregion

    // region applying metadata: movies

    private suspend fun applyMovie(movieId: Long, meta: MovieMetadata, mode: ApplyMode, state: MatchState, lock: Boolean, now: () -> Long): Long =
        writeLock.withLock { applyMovieLocked(movieId, meta, mode, state, lock, now) }

    private suspend fun applyMovieLocked(
        movieId: Long,
        meta: MovieMetadata,
        mode: ApplyMode,
        state: MatchState,
        lock: Boolean,
        now: () -> Long
    ): Long {
        var target = movieDao.getMovieById(movieId) ?: return movieId

        // Another version of the same film already holds this TMDB id: the files join it, this row goes away
        val twin = movieDao.getMovieByTmdbId(meta.tmdbId)
        if (twin != null && twin.id != target.id) {
            mediaFileDao.reassignMovie(target.id, twin.id)
            movieDao.deleteMovie(target.id)
            if (mode == ApplyMode.FILL_LOCAL_FIRST && twin.matchState == MatchState.IDENTIFIED) return twin.id
            target = twin
        }

        val fill = mode == ApplyMode.FILL_LOCAL_FIRST
        val collectionId = meta.collection?.let { ref ->
            val existing = metadataDao.getCollectionByTmdbId(ref.tmdbId)
            metadataDao.insertCollection(
                CollectionEntity(
                    id = existing?.id ?: 0, tmdbId = ref.tmdbId, name = ref.name, overview = ref.overview ?: existing?.overview,
                    posterPath = ref.posterPath, backdropPath = ref.backdropPath
                )
            ).let { if (it > 0) it else existing?.id }
        }

        val updated = target.copy(
            tmdbId = meta.tmdbId,
            imdbId = meta.imdbId ?: target.imdbId,
            title = meta.title.ifBlank { target.title },
            originalTitle = meta.originalTitle ?: target.originalTitle,
            sortTitle = TitleSimilarity.comparisonKey(meta.title.ifBlank { target.title }),
            year = meta.year ?: target.year,
            releaseDate = meta.releaseDate ?: target.releaseDate,
            overview = pick(fill, target.overview, meta.overview),
            runtime = pick(fill, target.runtime, meta.runtimeMinutes),
            rating = pick(fill, target.rating, meta.rating),
            certification = pick(fill, target.certification, meta.certification),
            posterPath = artwork(target.posterPath, meta.posterPath),
            backdropPath = artwork(target.backdropPath, meta.backdropPath),
            logoPath = meta.logoPath ?: target.logoPath.takeIf { fill },
            trailerKey = meta.trailerKey ?: target.trailerKey.takeIf { fill },
            collectionId = collectionId,
            matchState = state,
            matchLocked = lock || target.matchLocked,
            matchAttemptedAt = now()
        )
        movieDao.updateMovie(updated)

        metadataDao.clearMovieGenres(updated.id)
        genreIds(meta.genres).forEach { metadataDao.insertMovieGenreCrossRef(MovieGenreCrossRef(updated.id, it)) }
        metadataDao.clearCastForMovie(updated.id)
        metadataDao.insertCastMembers(castMembers(meta.cast, movieId = updated.id, seriesId = null))

        warmImageCache(
            listOfNotNull(
                ImageUrls.poster(updated.posterPath), ImageUrls.backdrop(updated.backdropPath), ImageUrls.logo(updated.logoPath)
            ) + meta.cast.take(PREFETCHED_PROFILES).mapNotNull { ImageUrls.profile(it.profilePath) }
        )
        return updated.id
    }

    // endregion

    // region applying metadata: series

    private suspend fun applySeries(seriesId: Long, meta: SeriesMetadata, mode: ApplyMode, state: MatchState, lock: Boolean, now: () -> Long): Long =
        writeLock.withLock { applySeriesLocked(seriesId, meta, mode, state, lock, now) }

    private suspend fun applySeriesLocked(
        seriesId: Long,
        meta: SeriesMetadata,
        mode: ApplyMode,
        state: MatchState,
        lock: Boolean,
        now: () -> Long
    ): Long {
        var target = seriesDao.getSeriesById(seriesId) ?: return seriesId

        val twin = seriesDao.getSeriesByTmdbId(meta.tmdbId)
        if (twin != null && twin.id != target.id) {
            mergeSeries(from = target.id, into = twin.id)
            if (mode == ApplyMode.FILL_LOCAL_FIRST && twin.matchState == MatchState.IDENTIFIED) return twin.id
            target = twin
        }

        val fill = mode == ApplyMode.FILL_LOCAL_FIRST
        val updated = target.copy(
            tmdbId = meta.tmdbId,
            imdbId = meta.imdbId ?: target.imdbId,
            title = meta.title.ifBlank { target.title },
            originalTitle = meta.originalTitle ?: target.originalTitle,
            sortTitle = TitleSimilarity.comparisonKey(meta.title.ifBlank { target.title }),
            firstAirDate = meta.firstAirDate ?: target.firstAirDate,
            status = meta.status ?: target.status.takeIf { fill },
            overview = pick(fill, target.overview, meta.overview),
            rating = pick(fill, target.rating, meta.rating),
            certification = pick(fill, target.certification, meta.certification),
            posterPath = artwork(target.posterPath, meta.posterPath),
            backdropPath = artwork(target.backdropPath, meta.backdropPath),
            logoPath = meta.logoPath ?: target.logoPath.takeIf { fill },
            matchState = state,
            matchLocked = lock || target.matchLocked,
            matchAttemptedAt = now()
        )
        seriesDao.updateSeries(updated)

        // The new official titles also lead to this series, so the next episode of "Le Prisonnier" finds it however it is named
        listOfNotNull(meta.title, meta.originalTitle).map(TitleSimilarity::normalize).filter(String::isNotEmpty).forEach {
            aliasDao.putIfAbsent(SeriesAliasEntity(it, updated.id))
        }

        metadataDao.clearSeriesGenres(updated.id)
        genreIds(meta.genres).forEach { metadataDao.insertSeriesGenreCrossRef(SeriesGenreCrossRef(updated.id, it)) }
        metadataDao.clearCastForSeries(updated.id)
        metadataDao.insertCastMembers(castMembers(meta.cast, movieId = null, seriesId = updated.id))

        val stills = syncSeasonsAndEpisodes(updated.id, meta)

        warmImageCache(
            listOfNotNull(ImageUrls.poster(updated.posterPath), ImageUrls.backdrop(updated.backdropPath), ImageUrls.logo(updated.logoPath)) +
                meta.cast.take(PREFETCHED_PROFILES).mapNotNull { ImageUrls.profile(it.profilePath) } + stills
        )
        return updated.id
    }

    /** Moves the episodes that have files from [from] into [into] (same season and number), then drops [from]. */
    private suspend fun mergeSeries(from: Long, into: Long) {
        for (episode in episodeDao.getEpisodesForSeries(from)) {
            val files = mediaFileDao.getMediaFilesForEpisode(episode.id)
            if (files.isEmpty()) continue

            val season = seasonDao.getSeasonByNumber(into, episode.seasonNumber)?.id
                ?: seasonDao.insertSeason(SeasonEntity(seriesId = into, seasonNumber = episode.seasonNumber))
            val destination = episodeDao.getEpisode(into, episode.seasonNumber, episode.episodeNumber)?.id
                ?: episodeDao.insertEpisode(episode.copy(id = 0, seriesId = into, seasonId = season))
            files.forEach { mediaFileDao.setEpisode(it.id, destination) }
        }
        aliasDao.repoint(from, into)
        // Seasons, episodes, cast and genre links of the absorbed row go with it (foreign key cascade)
        seriesDao.deleteSeries(from)
    }

    /**
     * Rebuilds the season / episode tree from TMDB while keeping every episode that has a file (and so its watch
     * history). Local episodes numbered provisionally (absolute anime numbers, dates) are moved onto the real episode.
     * @return still image URLs of the episodes we own a file for, to prefetch.
     */
    private suspend fun syncSeasonsAndEpisodes(seriesId: Long, meta: SeriesMetadata): List<String> {
        // Episodes known only from an earlier TMDB match would otherwise linger after a re-match
        episodeDao.getEpisodesWithoutFiles(seriesId).forEach { episodeDao.deleteEpisode(it.id) }

        val seasonIds = HashMap<Int, Long>()
        for (season in meta.seasons) {
            val existing = seasonDao.getSeasonByNumber(seriesId, season.seasonNumber)
            val id = seasonDao.insertSeason(
                SeasonEntity(
                    id = existing?.id ?: 0, seriesId = seriesId, seasonNumber = season.seasonNumber, name = season.name,
                    overview = season.overview, posterPath = season.posterPath, airDate = season.airDate
                )
            )
            seasonIds[season.seasonNumber] = existing?.id ?: id
        }

        // Absolute number of the first episode of each season (specials do not count)
        val episodesPerSeason = meta.seasons.filter { it.seasonNumber > 0 }.sortedBy { it.seasonNumber }
            .associate { it.seasonNumber to maxOf(it.episodeCount, it.episodes.size) }
        val absoluteBase = HashMap<Int, Int>()
        var running = 0
        for ((number, count) in episodesPerSeason) { absoluteBase[number] = running; running += count }

        val local = episodeDao.getEpisodesForSeries(seriesId).associateBy { it.seasonNumber to it.episodeNumber }
        val tmdbEpisodes = ArrayList<EpisodeEntity>()
        for (season in meta.seasons) {
            val seasonId = seasonIds[season.seasonNumber] ?: continue
            for (episode in season.episodes) {
                val existing = local[season.seasonNumber to episode.episodeNumber]
                tmdbEpisodes += EpisodeEntity(
                    id = existing?.id ?: 0, seriesId = seriesId, seasonId = seasonId,
                    seasonNumber = season.seasonNumber, episodeNumber = episode.episodeNumber,
                    absoluteNumber = absoluteBase[season.seasonNumber]?.plus(episode.episodeNumber) ?: existing?.absoluteNumber,
                    title = episode.title, overview = episode.overview, stillPath = episode.stillPath,
                    airDate = episode.airDate, runtime = episode.runtimeMinutes ?: meta.typicalRuntimeMinutes
                )
            }
        }
        if (tmdbEpisodes.isNotEmpty()) episodeDao.insertEpisodes(tmdbEpisodes)

        relinkProvisionalEpisodes(seriesId, meta, episodesPerSeason)

        // Provisional seasons (a year used as a season number, say) left empty by the move
        for (season in seasonDao.getSeasonsForSeries(seriesId)) {
            if (season.seasonNumber !in seasonIds && seasonDao.getSeasonWithEpisodes(season.id)?.episodes.isNullOrEmpty()) {
                seasonDao.deleteSeason(season.id)
            }
        }

        val owned = episodeDao.getEpisodesForSeries(seriesId).filter { it.stillPath != null }
        val ownedWithFiles = owned.filter { mediaFileDao.getMediaFilesForEpisode(it.id).isNotEmpty() }
        return ownedWithFiles.mapNotNull { ImageUrls.still(it.stillPath) }
    }

    private suspend fun relinkProvisionalEpisodes(seriesId: Long, meta: SeriesMetadata, episodesPerSeason: Map<Int, Int>) {
        val airDates = meta.seasons.flatMap { season -> season.episodes.filter { it.airDate != null }.map { it.airDate!! to (season.seasonNumber to it.episodeNumber) } }.toMap()

        for (episode in episodeDao.getEpisodesForSeries(seriesId)) {
            val destination: Pair<Int, Int>? = when {
                EpisodeNumbering.isDateBased(episode.seasonNumber, episode.episodeNumber) ->
                    airDates[EpisodeNumbering.dateOf(episode.seasonNumber, episode.episodeNumber)]
                // Parsed as absolute and filed in season 1 provisionally; a real season 1 number maps to itself
                episode.absoluteNumber != null && episode.seasonNumber == 1 ->
                    EpisodeNumbering.fromAbsolute(episode.absoluteNumber!!, episodesPerSeason)
                else -> null
            }
            if (destination == null || destination == (episode.seasonNumber to episode.episodeNumber)) continue

            val files = mediaFileDao.getMediaFilesForEpisode(episode.id)
            if (files.isEmpty()) continue
            val real = episodeDao.getEpisode(seriesId, destination.first, destination.second) ?: continue
            if (real.id == episode.id) continue

            files.forEach { mediaFileDao.setEpisode(it.id, real.id) }
            episodeDao.deleteEpisode(episode.id)
        }
    }

    // endregion

    // region shared helpers

    /** Local artwork is already on the device: only what comes from TMDB is worth downloading ahead of time. */
    private fun warmImageCache(urls: List<String>) = prefetcher.prefetch(urls.filter { it.startsWith("http") })

    /** In fill mode a value from a local sidecar (non-null on the row) beats the online one. */
    private fun <T> pick(fillLocalFirst: Boolean, existing: T?, online: T?): T? =
        if (fillLocalFirst) existing ?: online else online

    /** Local artwork (poster.jpg beside the file) always wins over TMDB; otherwise TMDB's, falling back to what we had. */
    private fun artwork(existing: String?, online: String?): String? =
        if (ImageUrls.isLocal(existing)) existing else online ?: existing

    private suspend fun genreIds(genres: List<GenreRef>): List<Long> = genres.map { genre ->
        val known = metadataDao.getGenreByTmdbId(genre.tmdbId) ?: metadataDao.getGenreByName(genre.name)
        known?.id ?: metadataDao.insertGenre(GenreEntity(tmdbId = genre.tmdbId, name = genre.name))
    }

    private suspend fun castMembers(cast: List<CastRef>, movieId: Long?, seriesId: Long?): List<CastMemberEntity> = cast.map { ref ->
        val existing = metadataDao.getPersonByTmdbId(ref.personTmdbId)
        val personId = existing?.also {
            if (it.name != ref.name || it.profilePath != ref.profilePath) {
                metadataDao.insertPerson(it.copy(name = ref.name, profilePath = ref.profilePath ?: it.profilePath))
            }
        }?.id ?: metadataDao.insertNewPerson(PersonEntity(tmdbId = ref.personTmdbId, name = ref.name, profilePath = ref.profilePath))

        CastMemberEntity(
            personId = personId, personName = ref.name, profilePath = ref.profilePath, movieId = movieId, seriesId = seriesId,
            character = ref.character, order = ref.order, isDirector = ref.isDirector
        )
    }

    // endregion

    companion object {
        /** A title TMDB did not know is looked up again after a week (new releases, corrected names). */
        const val RETRY_AFTER_MS = 7L * 24 * 60 * 60 * 1000
        private const val PREFETCHED_PROFILES = 10
    }
}
