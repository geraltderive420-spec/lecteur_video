package com.lecteur.core.network.tmdb

import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.MetadataException
import com.lecteur.core.model.MetadataLanguageSource
import com.lecteur.core.model.MetadataProvider
import com.lecteur.core.model.MetadataSearchHit
import com.lecteur.core.model.MovieMetadata
import com.lecteur.core.model.SeriesMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TmdbClient @Inject constructor(
    private val api: TmdbApi,
    private val language: MetadataLanguageSource,
    private val json: Json,
    @TmdbApiKey private val apiKey: String
) : MetadataProvider {

    override val isConfigured: Boolean get() = apiKey.isNotBlank()

    override suspend fun search(kind: MediaKind, query: String, year: Int?): List<MetadataSearchHit> = guarded {
        val lang = language.primary()
        when (kind) {
            MediaKind.MOVIE -> api.searchMovie(query, year, lang).results.map(TmdbMapper::hit)
            MediaKind.SERIES -> api.searchTv(query, year, lang).results.map(TmdbMapper::hit)
        }.filter { it.title.isNotBlank() }
    }

    override suspend fun movieDetails(tmdbId: Long): MovieMetadata = guarded(tmdbId) {
        val lang = language.primary()
        val primary = api.movieDetails(tmdbId, lang, MOVIE_APPEND, imageLanguages(lang), videoLanguages(lang))
        val fallback = if (primary.overview.isNullOrBlank() || primary.title.isNullOrBlank()) {
            runCatching { api.movieDetails(tmdbId, MetadataLanguageSource.FALLBACK, null, null, null) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
        } else null
        TmdbMapper.movie(primary, lang, fallback)
    }

    override suspend fun seriesDetails(tmdbId: Long, seasonNumbers: Set<Int>?): SeriesMetadata = guarded(tmdbId) {
        val lang = language.primary()
        val rootJson = api.tvDetails(tmdbId, lang, TV_APPEND, imageLanguages(lang), videoLanguages(lang))
        val root = decode<TvDetailsDto>(rootJson)

        val fallbackRoot = if (root.overview.isNullOrBlank() || root.name.isNullOrBlank()) {
            optional { decode<TvDetailsDto>(api.tvDetails(tmdbId, MetadataLanguageSource.FALLBACK, null, null, null)) }
        } else null

        val wanted = root.seasons.map { it.seasonNumber }.filter { seasonNumbers == null || it in seasonNumbers }
        val details = HashMap<Int, SeasonDetailsDto>()
        val fallbackDetails = HashMap<Int, SeasonDetailsDto>()

        for (chunk in wanted.chunked(SEASONS_PER_CALL)) {
            val append = chunk.joinToString(",") { "season/$it" }
            val chunkDetails = seasonDetails(api.tvDetails(tmdbId, lang, append, imageLanguages(lang), videoLanguages(lang)), chunk)
            details.putAll(chunkDetails)

            val hasBlanks = chunkDetails.values.any { season -> season.episodes.any { it.name.isNullOrBlank() || it.overview.isNullOrBlank() } }
            if (hasBlanks && lang != MetadataLanguageSource.FALLBACK) {
                optional { seasonDetails(api.tvDetails(tmdbId, MetadataLanguageSource.FALLBACK, append, null, null), chunk) }
                    ?.let(fallbackDetails::putAll)
            }
        }

        TmdbMapper.series(root, lang, TmdbMapper.seasons(root.seasons, details, fallbackDetails), fallbackRoot)
    }

    private fun seasonDetails(raw: JsonObject, seasons: List<Int>): Map<Int, SeasonDetailsDto> =
        seasons.mapNotNull { number ->
            (raw["season/$number"] as? JsonObject)?.let { number to decode<SeasonDetailsDto>(it) }
        }.toMap()

    private inline fun <reified T> decode(element: JsonObject): T = json.decodeFromJsonElement(element)

    /** The fallback language is a courtesy: its failure must not fail the main request. */
    private inline fun <T> optional(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun <T> guarded(notFoundId: Long? = null, block: suspend () -> T): T {
        if (!isConfigured) throw MetadataException.NotConfigured()
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: MetadataException) {
            throw e
        } catch (e: HttpException) {
            throw when {
                e.code() == 404 && notFoundId != null -> MetadataException.NotFound(notFoundId)
                e.code() == 401 -> MetadataException.NotConfigured()
                else -> MetadataException.Http(e.code())
            }
        } catch (e: IOException) {
            throw MetadataException.Offline(e)
        } catch (e: kotlinx.serialization.SerializationException) {
            // A response the app cannot read is a server-side problem, not a reason to crash the whole scan
            throw MetadataException.Http(-1)
        }
    }

    private fun imageLanguages(language: String) = "${TmdbMapper.languageOf(language)},en,null"
    private fun videoLanguages(language: String) = "${TmdbMapper.languageOf(language)},en,null"

    private companion object {
        const val MOVIE_APPEND = "credits,release_dates,videos,images"
        const val TV_APPEND = "credits,content_ratings,videos,images,external_ids"
        // TMDB allows up to 20 appended sub-requests per call
        const val SEASONS_PER_CALL = 20
    }
}
