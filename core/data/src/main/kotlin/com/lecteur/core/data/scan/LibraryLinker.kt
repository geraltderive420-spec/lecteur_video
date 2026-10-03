package com.lecteur.core.data.scan

import com.lecteur.core.common.match.TitleSimilarity
import com.lecteur.core.common.scan.EpisodeNumbering
import com.lecteur.core.common.scan.FoundFile
import com.lecteur.core.common.scan.SeriesKey
import com.lecteur.core.database.dao.EpisodeDao
import com.lecteur.core.database.dao.MediaFileDao
import com.lecteur.core.database.dao.MovieDao
import com.lecteur.core.database.dao.SeasonDao
import com.lecteur.core.database.dao.SeriesAliasDao
import com.lecteur.core.database.dao.SeriesDao
import com.lecteur.core.database.entity.EpisodeEntity
import com.lecteur.core.database.entity.MovieEntity
import com.lecteur.core.database.entity.SeasonEntity
import com.lecteur.core.database.entity.SeriesAliasEntity
import com.lecteur.core.database.entity.SeriesEntity
import com.lecteur.core.model.MatchState
import com.lecteur.core.model.MediaCategory
import com.lecteur.core.model.ParsedMediaInfo
import javax.inject.Inject

enum class LinkTarget { MOVIE, SERIES, PERSONAL }

/** Decides what a file is from the folder's category and from what its name looks like. */
object LinkPolicy {
    fun decide(category: MediaCategory, parsed: ParsedMediaInfo): LinkTarget = when (category) {
        MediaCategory.PERSONAL -> LinkTarget.PERSONAL
        MediaCategory.MOVIES, MediaCategory.DOCUMENTARIES -> LinkTarget.MOVIE
        // A series folder holding a file with no episode marker (a bonus, a film): kept as a movie rather than invented as episode 1
        MediaCategory.SERIES, MediaCategory.ANIME, MediaCategory.GENERIC -> if (parsed.isSeries) LinkTarget.SERIES else LinkTarget.MOVIE
    }
}

/**
 * Files a scanned video under a movie or an episode, creating the movie / series / season / episode rows it needs.
 * Everything created here is provisional (UNIDENTIFIED, titles from the file name or an nfo): the online
 * identification pass refines it later, so scanning never needs a network.
 */
class LibraryLinker @Inject constructor(
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val seasonDao: SeasonDao,
    private val episodeDao: EpisodeDao,
    private val aliasDao: SeriesAliasDao,
    private val mediaFileDao: MediaFileDao
) {

    /** Movies and series left without any file after files changed hands between categories. */
    suspend fun removeOrphans() {
        movieDao.deleteOrphanMovies()
        seriesDao.deleteOrphanSeries()
    }

    suspend fun link(mediaFileId: Long, file: FoundFile, parsed: ParsedMediaInfo, category: MediaCategory, local: LocalMetadata) {
        when (LinkPolicy.decide(category, parsed)) {
            LinkTarget.SERIES -> {
                val episodeId = episodeFor(parsed, local)
                mediaFileDao.setLinks(mediaFileId, movieId = null, episodeId = episodeId)
            }
            LinkTarget.MOVIE -> mediaFileDao.setLinks(mediaFileId, movieFor(file, parsed, local, personal = false), null)
            LinkTarget.PERSONAL -> mediaFileDao.setLinks(mediaFileId, movieFor(file, parsed, local, personal = true), null)
        }
    }

    // region movies

    private suspend fun movieFor(file: FoundFile, parsed: ParsedMediaInfo, local: LocalMetadata, personal: Boolean): Long {
        val nfo = local.nfo?.takeUnless { personal }

        // A sidecar that names the TMDB id points at a movie that may already be in the library (another version of it)
        nfo?.tmdbId?.let { tmdbId -> movieDao.getMovieByTmdbId(tmdbId)?.let { return it.id } }

        val title = (nfo?.title ?: parsed.cleanTitle).ifBlank { file.name.substringBeforeLast('.') }
        return movieDao.insertMovie(
            MovieEntity(
                title = title,
                originalTitle = nfo?.originalTitle,
                sortTitle = TitleSimilarity.comparisonKey(title).ifEmpty { title.lowercase() },
                year = nfo?.year ?: parsed.year,
                overview = nfo?.overview,
                runtime = nfo?.runtimeMinutes,
                rating = nfo?.rating,
                certification = nfo?.certification,
                tmdbId = nfo?.tmdbId,
                imdbId = nfo?.imdbId,
                posterPath = local.posterUri,
                backdropPath = local.backdropUri,
                matchState = MatchState.UNIDENTIFIED,
                // Personal videos are never looked up online
                matchLocked = personal
            )
        )
    }

    // endregion

    // region series

    private suspend fun episodeFor(parsed: ParsedMediaInfo, local: LocalMetadata): Long {
        val seriesId = seriesFor(parsed, local)

        val season = parsed.seasonNumber ?: 1
        val number = parsed.episodeNumbers.firstOrNull() ?: parsed.absoluteEpisodeNumber ?: 1
        val seasonId = seasonDao.getSeasonByNumber(seriesId, season)?.id
            ?: seasonDao.insertSeason(SeasonEntity(seriesId = seriesId, seasonNumber = season))

        episodeDao.getEpisode(seriesId, season, number)?.let { return it.id }
        return episodeDao.insertEpisode(
            EpisodeEntity(
                seriesId = seriesId,
                seasonId = seasonId,
                seasonNumber = season,
                episodeNumber = number,
                absoluteNumber = parsed.absoluteEpisodeNumber,
                // Date-numbered shows ("Show 2024.03.15"): the date is the only key TMDB will match on
                airDate = if (EpisodeNumbering.isDateBased(season, number)) EpisodeNumbering.dateOf(season, number) else null
            )
        )
    }

    private suspend fun seriesFor(parsed: ParsedMediaInfo, local: LocalMetadata): Long {
        val nfo = local.nfo

        val keys = SeriesKey.lookupKeys(parsed.cleanTitle, parsed.year)
        for ((index, key) in keys.withIndex()) {
            val owner = aliasDao.findSeriesId(key) ?: continue
            // The bare title is shared by homonyms ("Doctor Who" 1963 and 2005): a file naming a year must not join a show of another era
            val isBareTitle = keys.size > 1 && index == keys.lastIndex
            if (isBareTitle && parsed.year != null && !sameEra(owner, parsed.year!!)) continue
            return owner
        }
        nfo?.tmdbId?.let { tmdbId -> seriesDao.getSeriesByTmdbId(tmdbId)?.let { existing -> register(existing.id, parsed); return existing.id } }

        val title = (nfo?.title ?: parsed.cleanTitle).ifBlank { "Série sans titre" }
        val year = nfo?.year ?: parsed.year
        val seriesId = seriesDao.insertSeries(
            SeriesEntity(
                title = title,
                originalTitle = nfo?.originalTitle,
                sortTitle = TitleSimilarity.comparisonKey(title).ifEmpty { title.lowercase() },
                // Only the year is known at this point; the identification pass replaces it with the real date
                firstAirDate = year?.toString(),
                overview = nfo?.overview,
                rating = nfo?.rating,
                certification = nfo?.certification,
                tmdbId = nfo?.tmdbId,
                imdbId = nfo?.imdbId,
                posterPath = local.posterUri,
                backdropPath = local.backdropUri
            )
        )
        register(seriesId, parsed)
        nfo?.title?.let { aliasDao.putIfAbsent(SeriesAliasEntity(TitleSimilarity.normalize(it), seriesId)) }
        return seriesId
    }

    private suspend fun sameEra(seriesId: Long, year: Int): Boolean {
        val known = seriesDao.getSeriesById(seriesId)?.firstAirDate?.take(4)?.toIntOrNull() ?: return true
        return kotlin.math.abs(known - year) <= 1
    }

    /** The most specific key leads to this series for sure; the bare title only if nobody owns it yet. */
    private suspend fun register(seriesId: Long, parsed: ParsedMediaInfo) {
        val keys = SeriesKey.lookupKeys(parsed.cleanTitle, parsed.year)
        keys.forEachIndexed { index, key ->
            val entity = SeriesAliasEntity(key, seriesId)
            if (index == 0 && keys.size > 1) aliasDao.put(entity) else aliasDao.putIfAbsent(entity)
        }
    }

    // endregion
}
