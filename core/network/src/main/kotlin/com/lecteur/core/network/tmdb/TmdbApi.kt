package com.lecteur.core.network.tmdb

import kotlinx.serialization.json.JsonObject
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/** TMDB v3. The `api_key` query parameter is added by [TmdbAuthInterceptor]. */
interface TmdbApi {

    @GET("search/movie")
    suspend fun searchMovie(
        @Query("query") query: String,
        @Query("year") year: Int?,
        @Query("language") language: String,
        @Query("include_adult") includeAdult: Boolean = false
    ): SearchResponseDto<SearchMovieDto>

    @GET("search/tv")
    suspend fun searchTv(
        @Query("query") query: String,
        @Query("first_air_date_year") year: Int?,
        @Query("language") language: String,
        @Query("include_adult") includeAdult: Boolean = false
    ): SearchResponseDto<SearchTvDto>

    @GET("movie/{id}")
    suspend fun movieDetails(
        @Path("id") id: Long,
        @Query("language") language: String,
        @Query("append_to_response") append: String?,
        @Query("include_image_language") imageLanguages: String?,
        @Query("include_video_language") videoLanguages: String?
    ): MovieDetailsDto

    /**
     * `tv/{id}` kept raw: appended seasons arrive under dynamic keys (`season/1`, `season/2`...), which a typed
     * DTO cannot describe. [TmdbMapper] decodes the known parts.
     */
    @GET("tv/{id}")
    suspend fun tvDetails(
        @Path("id") id: Long,
        @Query("language") language: String,
        @Query("append_to_response") append: String?,
        @Query("include_image_language") imageLanguages: String?,
        @Query("include_video_language") videoLanguages: String?
    ): JsonObject
}
