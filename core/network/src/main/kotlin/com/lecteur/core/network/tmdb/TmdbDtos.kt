package com.lecteur.core.network.tmdb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchResponseDto<T>(val results: List<T> = emptyList())

@Serializable
data class SearchMovieDto(
    val id: Long,
    val title: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    val popularity: Double = 0.0
)

@Serializable
data class SearchTvDto(
    val id: Long,
    val name: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    val popularity: Double = 0.0
)

@Serializable
data class GenreDto(val id: Long, val name: String)

@Serializable
data class CastDto(
    val id: Long,
    val name: String,
    @SerialName("profile_path") val profilePath: String? = null,
    val character: String? = null,
    val order: Int = 0
)

@Serializable
data class CrewDto(
    val id: Long,
    val name: String,
    @SerialName("profile_path") val profilePath: String? = null,
    val job: String? = null
)

@Serializable
data class CreditsDto(val cast: List<CastDto> = emptyList(), val crew: List<CrewDto> = emptyList())

@Serializable
data class VideoDto(
    val key: String,
    val site: String? = null,
    val type: String? = null,
    val official: Boolean = false,
    @SerialName("iso_639_1") val language: String? = null
)

@Serializable
data class VideosDto(val results: List<VideoDto> = emptyList())

@Serializable
data class ImageDto(
    @SerialName("file_path") val filePath: String,
    @SerialName("iso_639_1") val language: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0
)

@Serializable
data class ImagesDto(val logos: List<ImageDto> = emptyList())

@Serializable
data class CollectionDto(
    val id: Long,
    val name: String,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null
)

@Serializable
data class ReleaseDateDto(val certification: String? = null)

@Serializable
data class ReleaseDatesCountryDto(
    @SerialName("iso_3166_1") val country: String,
    @SerialName("release_dates") val releaseDates: List<ReleaseDateDto> = emptyList()
)

@Serializable
data class ReleaseDatesDto(val results: List<ReleaseDatesCountryDto> = emptyList())

@Serializable
data class ContentRatingDto(
    @SerialName("iso_3166_1") val country: String,
    val rating: String? = null
)

@Serializable
data class ContentRatingsDto(val results: List<ContentRatingDto> = emptyList())

@Serializable
data class ExternalIdsDto(@SerialName("imdb_id") val imdbId: String? = null)

@Serializable
data class MovieDetailsDto(
    val id: Long,
    @SerialName("imdb_id") val imdbId: String? = null,
    val title: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val overview: String? = null,
    val runtime: Int? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int = 0,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val genres: List<GenreDto> = emptyList(),
    @SerialName("belongs_to_collection") val collection: CollectionDto? = null,
    val credits: CreditsDto? = null,
    @SerialName("release_dates") val releaseDates: ReleaseDatesDto? = null,
    val videos: VideosDto? = null,
    val images: ImagesDto? = null
)

@Serializable
data class SeasonSummaryDto(
    @SerialName("season_number") val seasonNumber: Int,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("episode_count") val episodeCount: Int = 0
)

@Serializable
data class EpisodeDto(
    @SerialName("episode_number") val episodeNumber: Int,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    val runtime: Int? = null
)

@Serializable
data class SeasonDetailsDto(
    @SerialName("season_number") val seasonNumber: Int,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    val episodes: List<EpisodeDto> = emptyList()
)

@Serializable
data class TvDetailsDto(
    val id: Long,
    val name: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val status: String? = null,
    val overview: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    val genres: List<GenreDto> = emptyList(),
    val seasons: List<SeasonSummaryDto> = emptyList(),
    val credits: CreditsDto? = null,
    @SerialName("content_ratings") val contentRatings: ContentRatingsDto? = null,
    val videos: VideosDto? = null,
    val images: ImagesDto? = null,
    @SerialName("external_ids") val externalIds: ExternalIdsDto? = null
)
