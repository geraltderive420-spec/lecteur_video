package com.lecteur.desktop.data

import com.lecteur.core.common.match.MatchCandidate
import com.lecteur.core.common.match.MatchQuery
import com.lecteur.core.common.match.MatchScorer
import com.lecteur.core.common.match.TitleSimilarity
import com.lecteur.core.common.scan.EpisodeNumbering
import com.lecteur.core.model.CastRef
import com.lecteur.core.model.GenreRef
import com.lecteur.core.model.ImageUrls
import com.lecteur.core.model.MatchState
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.MetadataException
import com.lecteur.core.model.MetadataLanguageSource
import com.lecteur.core.model.MetadataProvider
import com.lecteur.core.model.MetadataSearchHit
import com.lecteur.core.model.MovieMetadata
import com.lecteur.core.model.SeriesMetadata
import com.lecteur.core.network.tmdb.RequestPacer
import com.lecteur.core.network.tmdb.TmdbApi
import com.lecteur.core.network.tmdb.TmdbAuthInterceptor
import com.lecteur.core.network.tmdb.TmdbClient
import com.lecteur.core.network.tmdb.TmdbRateLimitInterceptor
import com.lecteur.desktop.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

data class IdentifyResult(
    val identified: Int = 0,
    val toVerify: Int = 0,
    val unidentified: Int = 0,
    val offline: Boolean = false,
    val notConfigured: Boolean = false
) {
    val processed: Int get() = identified + toVerify + unidentified
}

/** Builds the TMDB client of core:network with the key of the settings (or of the build). */
class TmdbProvider(private val settings: SettingsStore) {
    private var cached: Pair<String, MetadataProvider>? = null
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    val apiKey: String get() = settings.value.tmdbApiKey.trim().ifBlank { BuildConfig.TMDB_API_KEY }

    @Synchronized
    fun get(): MetadataProvider {
        val key = apiKey
        cached?.takeIf { it.first == key }?.let { return it.second }
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(TmdbAuthInterceptor(key))
            .addInterceptor(TmdbRateLimitInterceptor(RequestPacer()))
            .build()
        val api = Retrofit.Builder()
            .baseUrl("https://api.themoviedb.org/3/")
            .client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(TmdbApi::class.java)
        val language = object : MetadataLanguageSource {
            override suspend fun primary(): String = settings.value.metadataLanguage
        }
        return TmdbClient(api, language, json, key).also { cached = key to it }
    }
}

/**
 * Online identification: port of core:data's MetadataRepository onto the desktop database. Every scanned title starts
 * UNIDENTIFIED with what the file name says; this pass searches TMDB, scores the candidates and applies the best one.
 */
class Identifier(private val store: LibraryStore, private val tmdb: TmdbProvider, private val images: ImageCache) {
    private val provider: MetadataProvider get() = tmdb.get()
    private val db get() = store.db

    suspend fun identifyPending(onProgress: (Int) -> Unit = {}): IdentifyResult {
        if (!provider.isConfigured) return IdentifyResult(notConfigured = true)
        var result = IdentifyResult()
        val retryBefore = System.currentTimeMillis() - RETRY_AFTER_MS
        try {
            for (movie in store.moviesToIdentify(retryBefore)) {
                result = result.count(identifyMovie(movie.id, movie.title, movie.originalTitle, movie.year, movie.tmdbId))
                onProgress(result.processed)
                store.changed()
            }
            for (show in store.seriesToIdentify(retryBefore)) {
                result = result.count(identifySeries(show.id, show.title, show.originalTitle, show.firstAirDate?.take(4)?.toIntOrNull(), show.tmdbId))
                onProgress(result.processed)
                store.changed()
            }
        } catch (_: MetadataException.Offline) {
            return result.copy(offline = true)
        } catch (_: MetadataException.NotConfigured) {
            return result.copy(notConfigured = true)
        }
        return result
    }

    private fun IdentifyResult.count(state: MatchState) = when (state) {
        MatchState.IDENTIFIED -> copy(identified = identified + 1)
        MatchState.TO_VERIFY -> copy(toVerify = toVerify + 1)
        MatchState.UNIDENTIFIED -> copy(unidentified = unidentified + 1)
    }

    private suspend fun identifyMovie(id: Long, title: String, originalTitle: String?, year: Int?, tmdbId: Long?): MatchState {
        try {
            tmdbId?.let {
                applyMovie(id, provider.movieDetails(it), fill = true, MatchState.IDENTIFIED, lock = false)
                return MatchState.IDENTIFIED
            }
            val runtime = db.read { long("SELECT MAX(duration_ms) FROM media_files WHERE movie_id = ?", id) }?.let { (it / 60_000).toInt() }
            val query = MatchQuery(title, year, runtime)
            val hits = searchWithFallbacks(MediaKind.MOVIE, title, originalTitle, year)
            if (hits.isEmpty()) return markTried("movie", id)
            val candidates = hits.map { MatchCandidate(it.tmdbId, it.title, it.originalTitle, it.year, popularity = it.popularity) }
            val leader = provider.movieDetails(MatchScorer.rank(query.copy(runtimeMinutes = null), candidates).first().candidate.id)
            val refined = candidates.map { if (it.id == leader.tmdbId) it.copy(runtimeMinutes = leader.runtimeMinutes) else it }
            val decision = MatchScorer.decide(query, refined)
            val best = decision.best ?: return markTried("movie", id)
            val meta = if (best.candidate.id == leader.tmdbId) leader else provider.movieDetails(best.candidate.id)
            applyMovie(id, meta, fill = true, decision.state, lock = false)
            return decision.state
        } catch (_: MetadataException.NotFound) {
            return markTried("movie", id)
        } catch (e: MetadataException.Http) {
            return markTried("movie", id)
        }
    }

    private suspend fun identifySeries(id: Long, title: String, originalTitle: String?, year: Int?, tmdbId: Long?): MatchState {
        try {
            tmdbId?.let {
                applySeries(id, provider.seriesDetails(it), fill = true, MatchState.IDENTIFIED, lock = false)
                return MatchState.IDENTIFIED
            }
            val hits = searchWithFallbacks(MediaKind.SERIES, title, originalTitle, year)
            if (hits.isEmpty()) return markTried("series", id)
            val candidates = hits.map { MatchCandidate(it.tmdbId, it.title, it.originalTitle, it.year, popularity = it.popularity) }
            val decision = MatchScorer.decide(MatchQuery(title, year), candidates)
            val best = decision.best ?: return markTried("series", id)
            applySeries(id, provider.seriesDetails(best.candidate.id), fill = true, decision.state, lock = false)
            return decision.state
        } catch (_: MetadataException.NotFound) {
            return markTried("series", id)
        } catch (_: MetadataException.Http) {
            return markTried("series", id)
        }
    }

    private suspend fun searchWithFallbacks(kind: MediaKind, title: String, originalTitle: String?, year: Int?): List<MetadataSearchHit> {
        provider.search(kind, title, year).takeIf { it.isNotEmpty() }?.let { return it }
        if (year != null) provider.search(kind, title, null).takeIf { it.isNotEmpty() }?.let { return it }
        if (!originalTitle.isNullOrBlank() && !originalTitle.equals(title, ignoreCase = true)) return provider.search(kind, originalTitle, year)
        return emptyList()
    }

    private fun markTried(kind: String, id: Long): MatchState {
        store.markTried(kind, id, System.currentTimeMillis())
        return MatchState.UNIDENTIFIED
    }

    // region manual correction

    suspend fun search(kind: MediaKind, query: String, year: Int?): List<MetadataSearchHit> {
        if (!provider.isConfigured) throw MetadataException.NotConfigured()
        return provider.search(kind, query.trim(), year)
    }

    /** Associates a title with a chosen TMDB id and locks it. Returns the id of the surviving row. */
    suspend fun applyManual(kind: MediaKind, localId: Long, tmdbId: Long): Long = when (kind) {
        MediaKind.MOVIE -> applyMovie(localId, provider.movieDetails(tmdbId), fill = false, MatchState.IDENTIFIED, lock = true)
        MediaKind.SERIES -> applySeries(localId, provider.seriesDetails(tmdbId), fill = false, MatchState.IDENTIFIED, lock = true)
    }.also { store.refreshNow() }

    suspend fun refresh(kind: MediaKind, localId: Long): Boolean {
        when (kind) {
            MediaKind.MOVIE -> {
                val m = store.movie(localId) ?: return false
                applyMovie(localId, provider.movieDetails(m.tmdbId ?: return false), fill = false, m.matchState, m.matchLocked)
            }
            MediaKind.SERIES -> {
                val s = store.series(localId) ?: return false
                applySeries(localId, provider.seriesDetails(s.tmdbId ?: return false), fill = false, s.matchState, s.matchLocked)
            }
        }
        store.refreshNow()
        return true
    }

    fun confirm(kind: MediaKind, localId: Long) {
        val table = if (kind == MediaKind.MOVIE) "movies" else "series"
        db.write { exec("UPDATE $table SET match_state = 'IDENTIFIED', match_locked = 1 WHERE id = ?", localId) }
        store.refreshNow()
    }

    // endregion

    // region applying: movies

    private fun applyMovie(movieId: Long, meta: MovieMetadata, fill: Boolean, state: MatchState, lock: Boolean): Long {
        var target = store.movie(movieId) ?: return movieId
        val twin = store.movieByTmdb(meta.tmdbId)
        if (twin != null && twin.id != target.id) {
            db.write {
                exec("UPDATE media_files SET movie_id = ? WHERE movie_id = ?", twin.id, target.id)
                exec("UPDATE OR IGNORE user_list_items SET movie_id = ? WHERE movie_id = ?", twin.id, target.id)
                exec("DELETE FROM movies WHERE id = ?", target.id)
            }
            if (fill && twin.matchState == MatchState.IDENTIFIED) return twin.id
            target = twin
        }
        val collectionId = meta.collection?.let { ref ->
            db.write {
                exec(
                    "INSERT INTO collections(tmdb_id, name, overview, poster, backdrop) VALUES (?, ?, ?, ?, ?) ON CONFLICT(tmdb_id) DO UPDATE SET name = excluded.name, poster = excluded.poster, backdrop = excluded.backdrop",
                    ref.tmdbId, ref.name, ref.overview, ref.posterPath, ref.backdropPath
                )
                long("SELECT id FROM collections WHERE tmdb_id = ?", ref.tmdbId)
            }
        }
        val title = meta.title.ifBlank { target.title }
        db.write {
            exec(
                """UPDATE movies SET tmdb_id = ?, imdb_id = ?, title = ?, original_title = ?, sort_title = ?, year = ?, release_date = ?,
                   overview = ?, runtime = ?, rating = ?, certification = ?, poster = ?, backdrop = ?, logo = ?, trailer = ?,
                   collection_id = ?, match_state = ?, match_locked = ?, identify_tried_at = ? WHERE id = ?""",
                meta.tmdbId, meta.imdbId ?: target.imdbId, title, meta.originalTitle ?: target.originalTitle, LibraryStore.sortKey(title),
                meta.year ?: target.year, meta.releaseDate ?: target.releaseDate,
                pick(fill, target.overview, meta.overview), pick(fill, target.runtimeMinutes, meta.runtimeMinutes),
                pick(fill, target.rating, meta.rating), pick(fill, target.certification, meta.certification),
                artwork(target.posterPath, meta.posterPath), artwork(target.backdropPath, meta.backdropPath),
                meta.logoPath ?: target.logoPath.takeIf { fill }, meta.trailerKey ?: target.trailerKey.takeIf { fill },
                collectionId, state, lock || target.matchLocked, System.currentTimeMillis(), target.id
            )
            exec("DELETE FROM movie_genres WHERE movie_id = ?", target.id)
            genreIds(this, meta.genres).forEach { exec("INSERT OR IGNORE INTO movie_genres(movie_id, genre_id) VALUES (?, ?)", target.id, it) }
            exec("DELETE FROM credits WHERE movie_id = ?", target.id)
            writeCredits(this, meta.cast, movieId = target.id, seriesId = null)
        }
        images.prefetch(listOfNotNull(ImageUrls.poster(meta.posterPath), ImageUrls.backdrop(meta.backdropPath)))
        return target.id
    }

    // endregion

    // region applying: series

    private fun applySeries(seriesId: Long, meta: SeriesMetadata, fill: Boolean, state: MatchState, lock: Boolean): Long {
        var target = store.series(seriesId) ?: return seriesId
        val twin = store.seriesByTmdb(meta.tmdbId)
        if (twin != null && twin.id != target.id) {
            mergeSeries(target.id, twin.id)
            if (fill && twin.matchState == MatchState.IDENTIFIED) return twin.id
            target = twin
        }
        val title = meta.title.ifBlank { target.title }
        db.write {
            exec(
                """UPDATE series SET tmdb_id = ?, imdb_id = ?, title = ?, original_title = ?, sort_title = ?, first_air_date = ?, status = ?,
                   overview = ?, rating = ?, certification = ?, poster = ?, backdrop = ?, logo = ?, trailer = ?,
                   match_state = ?, match_locked = ?, identify_tried_at = ? WHERE id = ?""",
                meta.tmdbId, meta.imdbId ?: target.imdbId, title, meta.originalTitle ?: target.originalTitle, LibraryStore.sortKey(title),
                meta.firstAirDate ?: target.firstAirDate, meta.status ?: target.status,
                pick(fill, target.overview, meta.overview), pick(fill, target.rating, meta.rating), pick(fill, target.certification, meta.certification),
                artwork(target.posterPath, meta.posterPath), artwork(target.backdropPath, meta.backdropPath),
                meta.logoPath ?: target.logoPath.takeIf { fill }, meta.trailerKey,
                state, lock || target.matchLocked, System.currentTimeMillis(), target.id
            )
            listOfNotNull(meta.title, meta.originalTitle).map(TitleSimilarity::normalize).filter(String::isNotEmpty).forEach {
                exec("INSERT OR IGNORE INTO series_aliases(alias, series_id) VALUES (?, ?)", it, target.id)
            }
            exec("DELETE FROM series_genres WHERE series_id = ?", target.id)
            genreIds(this, meta.genres).forEach { exec("INSERT OR IGNORE INTO series_genres(series_id, genre_id) VALUES (?, ?)", target.id, it) }
            exec("DELETE FROM credits WHERE series_id = ?", target.id)
            writeCredits(this, meta.cast, movieId = null, seriesId = target.id)
        }
        syncSeasons(target.id, meta)
        images.prefetch(listOfNotNull(ImageUrls.poster(meta.posterPath), ImageUrls.backdrop(meta.backdropPath)))
        return target.id
    }

    private fun mergeSeries(from: Long, into: Long) {
        for (episode in store.episodes(from)) {
            val files = db.read { query("SELECT id FROM media_files WHERE episode_id = ?", episode.id) { it.long("id") } }
            if (files.isEmpty()) continue
            val destination = store.episodeId(into, episode.seasonNumber, episode.episodeNumber, episode.absoluteNumber, episode.airDate)
            db.write { files.forEach { exec("UPDATE media_files SET episode_id = ? WHERE id = ?", destination, it) } }
        }
        db.write {
            exec("UPDATE OR REPLACE series_aliases SET series_id = ? WHERE series_id = ?", into, from)
            exec("UPDATE OR IGNORE user_list_items SET series_id = ? WHERE series_id = ?", into, from)
            exec("DELETE FROM series WHERE id = ?", from)
        }
    }

    /** Rebuilds seasons and episodes from TMDB, keeping every episode that has a file; moves provisional numbering onto the real episodes. */
    private fun syncSeasons(seriesId: Long, meta: SeriesMetadata) {
        db.write {
            exec("DELETE FROM episodes WHERE series_id = ? AND id NOT IN (SELECT episode_id FROM media_files WHERE episode_id IS NOT NULL)", seriesId)
            for (season in meta.seasons) {
                exec(
                    """INSERT INTO seasons(series_id, number, name, overview, poster, air_date) VALUES (?, ?, ?, ?, ?, ?)
                       ON CONFLICT(series_id, number) DO UPDATE SET name = excluded.name, overview = excluded.overview, poster = excluded.poster, air_date = excluded.air_date""",
                    seriesId, season.seasonNumber, season.name, season.overview, season.posterPath, season.airDate
                )
            }
        }
        val perSeason = meta.seasons.filter { it.seasonNumber > 0 }.sortedBy { it.seasonNumber }
            .associate { it.seasonNumber to maxOf(it.episodeCount, it.episodes.size) }
        val base = HashMap<Int, Int>()
        var running = 0
        for ((n, c) in perSeason) { base[n] = running; running += c }

        db.write {
            for (season in meta.seasons) {
                val seasonId = long("SELECT id FROM seasons WHERE series_id = ? AND number = ?", seriesId, season.seasonNumber) ?: continue
                for (e in season.episodes) {
                    exec(
                        """INSERT INTO episodes(series_id, season_id, season_number, episode_number, absolute_number, title, overview, still, air_date, runtime)
                           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                           ON CONFLICT(series_id, season_number, episode_number) DO UPDATE SET season_id = excluded.season_id,
                           absolute_number = COALESCE(excluded.absolute_number, absolute_number), title = excluded.title, overview = excluded.overview,
                           still = excluded.still, air_date = excluded.air_date, runtime = excluded.runtime""",
                        seriesId, seasonId, season.seasonNumber, e.episodeNumber, base[season.seasonNumber]?.plus(e.episodeNumber),
                        e.title, e.overview, e.stillPath, e.airDate, e.runtimeMinutes ?: meta.typicalRuntimeMinutes
                    )
                }
            }
        }

        // Absolute (anime) and date numbering found during the scan move onto the real episode
        val airDates = meta.seasons.flatMap { s -> s.episodes.filter { it.airDate != null }.map { it.airDate!! to (s.seasonNumber to it.episodeNumber) } }.toMap()
        val single = meta.seasons.filter { it.seasonNumber > 0 }.flatMap { s -> s.episodes.map { it.episodeNumber to s.seasonNumber } }
            .groupBy({ it.first }, { it.second }).filterValues { it.size == 1 }.mapValues { it.value.single() }
        fun locate(absolute: Int): Pair<Int, Int>? = single[absolute]?.let { it to absolute } ?: EpisodeNumbering.fromAbsolute(absolute, perSeason)

        for (episode in store.episodes(seriesId)) {
            val destination = when {
                EpisodeNumbering.isDateBased(episode.seasonNumber, episode.episodeNumber) -> airDates[EpisodeNumbering.dateOf(episode.seasonNumber, episode.episodeNumber)]
                episode.absoluteNumber != null && episode.seasonNumber == 1 && episode.title == null -> locate(episode.absoluteNumber!!)
                episode.seasonNumber == 1 && episode.episodeNumber > (perSeason[1] ?: Int.MAX_VALUE) -> locate(episode.episodeNumber)
                else -> null
            } ?: continue
            if (destination == (episode.seasonNumber to episode.episodeNumber)) continue
            db.write {
                val real = long("SELECT id FROM episodes WHERE series_id = ? AND season_number = ? AND episode_number = ?", seriesId, destination.first, destination.second)
                if (real != null && real != episode.id) {
                    exec("UPDATE media_files SET episode_id = ? WHERE episode_id = ?", real, episode.id)
                    exec("DELETE FROM episodes WHERE id = ?", episode.id)
                }
            }
        }
        db.write {
            exec(
                "DELETE FROM seasons WHERE series_id = ? AND id NOT IN (SELECT season_id FROM episodes) AND number NOT IN (${meta.seasons.joinToString { it.seasonNumber.toString() }.ifEmpty { "-1" }})",
                seriesId
            )
        }
    }

    // endregion

    private fun <T> pick(fill: Boolean, existing: T?, online: T?): T? = if (fill) existing ?: online else online

    private fun artwork(existing: String?, online: String?): String? = if (ImageUrls.isLocal(existing)) existing else online ?: existing

    private fun genreIds(s: Db.Session, genres: List<GenreRef>): List<Long> = genres.mapNotNull { g ->
        s.exec("INSERT INTO genres(tmdb_id, name) VALUES (?, ?) ON CONFLICT(tmdb_id) DO UPDATE SET name = excluded.name", g.tmdbId, g.name)
        s.long("SELECT id FROM genres WHERE tmdb_id = ?", g.tmdbId)
    }

    private fun writeCredits(s: Db.Session, cast: List<CastRef>, movieId: Long?, seriesId: Long?) {
        cast.forEach { ref ->
            s.exec(
                "INSERT INTO people(tmdb_id, name, profile) VALUES (?, ?, ?) ON CONFLICT(tmdb_id) DO UPDATE SET name = excluded.name, profile = COALESCE(excluded.profile, profile)",
                ref.personTmdbId, ref.name, ref.profilePath
            )
            val personId = s.long("SELECT id FROM people WHERE tmdb_id = ?", ref.personTmdbId) ?: return@forEach
            s.exec(
                "INSERT INTO credits(person_id, movie_id, series_id, role, ord, is_director) VALUES (?, ?, ?, ?, ?, ?)",
                personId, movieId, seriesId, ref.character, ref.order, ref.isDirector
            )
        }
    }

    companion object {
        const val RETRY_AFTER_MS = 7L * 24 * 60 * 60 * 1000
    }
}
