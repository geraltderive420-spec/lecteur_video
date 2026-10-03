package com.lecteur.core.model

enum class MediaKind { MOVIE, SERIES }

data class MetadataSearchHit(
    val tmdbId: Long,
    val kind: MediaKind,
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val overview: String?,
    val posterPath: String?,
    val popularity: Double = 0.0
)

data class GenreRef(val tmdbId: Long, val name: String)

data class CastRef(
    val personTmdbId: Long,
    val name: String,
    val profilePath: String?,
    val character: String?,
    val order: Int,
    val isDirector: Boolean = false
)

data class CollectionRef(
    val tmdbId: Long,
    val name: String,
    val overview: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null
)

data class MovieMetadata(
    val tmdbId: Long,
    val imdbId: String?,
    val title: String,
    val originalTitle: String?,
    val releaseDate: String?,
    val year: Int?,
    val overview: String?,
    val runtimeMinutes: Int?,
    val rating: Float?,
    val certification: String?,
    val posterPath: String?,
    val backdropPath: String?,
    val logoPath: String?,
    val trailerKey: String?,
    val genres: List<GenreRef>,
    val cast: List<CastRef>,
    val collection: CollectionRef?
)

data class EpisodeMetadata(
    val episodeNumber: Int,
    val title: String?,
    val overview: String?,
    val stillPath: String?,
    val airDate: String?,
    val runtimeMinutes: Int?
)

data class SeasonMetadata(
    val seasonNumber: Int,
    val name: String?,
    val overview: String?,
    val posterPath: String?,
    val airDate: String?,
    /** Episode count announced by the series listing; the episode list can be empty when it was not fetched. */
    val episodeCount: Int,
    val episodes: List<EpisodeMetadata>
)

data class SeriesMetadata(
    val tmdbId: Long,
    val imdbId: String?,
    val title: String,
    val originalTitle: String?,
    val firstAirDate: String?,
    val year: Int?,
    val status: String?,
    val overview: String?,
    val rating: Float?,
    val certification: String?,
    val posterPath: String?,
    val backdropPath: String?,
    val logoPath: String?,
    val trailerKey: String?,
    val typicalRuntimeMinutes: Int?,
    val genres: List<GenreRef>,
    val cast: List<CastRef>,
    val seasons: List<SeasonMetadata>
)

/** Why a metadata request failed, so callers can tell "no network" (retry later) from "no such title" (give up). */
sealed class MetadataException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotConfigured : MetadataException("Cle API TMDB absente (tmdb.apiKey dans local.properties)")
    class Offline(cause: Throwable) : MetadataException("Reseau indisponible", cause)
    class NotFound(val tmdbId: Long) : MetadataException("Identifiant TMDB introuvable : $tmdbId")
    class Http(val code: Int) : MetadataException("Erreur TMDB HTTP $code")
}

/**
 * Online source of movie and series metadata. TMDB is the only implementation; the interface lets the
 * identification logic be tested without a network and keeps room for another provider.
 * All functions throw [MetadataException] and nothing else.
 */
interface MetadataProvider {
    val isConfigured: Boolean

    suspend fun search(kind: MediaKind, query: String, year: Int?): List<MetadataSearchHit>

    suspend fun movieDetails(tmdbId: Long): MovieMetadata

    /** @param seasonNumbers seasons whose episode list is wanted; the others come back without episodes. */
    suspend fun seriesDetails(tmdbId: Long, seasonNumbers: Set<Int>? = null): SeriesMetadata
}

/** Single place that knows how to turn a TMDB image path into a URL, so prefetching and display use the same sizes. */
object ImageUrls {
    const val BASE = "https://image.tmdb.org/t/p/"

    const val POSTER = "w342"
    const val BACKDROP = "w780"
    const val LOGO = "w500"
    const val STILL = "w300"
    const val PROFILE = "w185"

    /** Local artwork (poster.jpg next to the file, ...) is stored as its content URI and used as is. */
    fun isLocal(path: String?): Boolean = path != null && (path.startsWith("content:") || path.startsWith("file:"))

    fun url(path: String?, size: String): String? = when {
        path.isNullOrBlank() -> null
        isLocal(path) -> path
        else -> BASE + size + (if (path.startsWith("/")) path else "/$path")
    }

    fun poster(path: String?) = url(path, POSTER)
    fun backdrop(path: String?) = url(path, BACKDROP)
    fun logo(path: String?) = url(path, LOGO)
    fun still(path: String?) = url(path, STILL)
    fun profile(path: String?) = url(path, PROFILE)
}

/** Language of the metadata requested online (a TMDB tag such as "fr-FR"); read per request so a settings change applies at once. */
interface MetadataLanguageSource {
    suspend fun primary(): String

    companion object {
        const val FALLBACK = "en-US"
    }
}
