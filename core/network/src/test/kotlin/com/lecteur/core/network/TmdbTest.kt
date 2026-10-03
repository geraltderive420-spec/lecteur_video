package com.lecteur.core.network

import com.google.common.truth.Truth.assertThat
import com.lecteur.core.network.tmdb.ImageDto
import com.lecteur.core.network.tmdb.ImagesDto
import com.lecteur.core.network.tmdb.MovieDetailsDto
import com.lecteur.core.network.tmdb.RequestPacer
import com.lecteur.core.network.tmdb.SeasonDetailsDto
import com.lecteur.core.network.tmdb.SeasonSummaryDto
import com.lecteur.core.network.tmdb.TmdbMapper
import com.lecteur.core.network.tmdb.TmdbRateLimitInterceptor
import com.lecteur.core.network.tmdb.TvDetailsDto
import com.lecteur.core.network.tmdb.VideoDto
import com.lecteur.core.network.tmdb.VideosDto
import kotlinx.serialization.json.Json
import org.junit.Test

class TmdbTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    private val movieJson = """
        {
          "id": 603, "imdb_id": "tt0133093", "title": "Matrix", "original_title": "The Matrix",
          "release_date": "1999-03-31", "overview": "Un hacker...", "runtime": 136,
          "vote_average": 8.2, "vote_count": 25000, "poster_path": "/p.jpg", "backdrop_path": "/b.jpg",
          "genres": [{"id": 28, "name": "Action"}, {"id": 878, "name": "Science-Fiction"}],
          "belongs_to_collection": {"id": 2344, "name": "Matrix - Saga", "poster_path": "/cp.jpg", "backdrop_path": null},
          "credits": {
            "cast": [
              {"id": 6384, "name": "Keanu Reeves", "profile_path": "/k.jpg", "character": "Neo", "order": 0},
              {"id": 2975, "name": "Laurence Fishburne", "profile_path": null, "character": "Morpheus", "order": 1}
            ],
            "crew": [
              {"id": 9340, "name": "Lana Wachowski", "job": "Director"},
              {"id": 9341, "name": "Lilly Wachowski", "job": "Director"},
              {"id": 1, "name": "Someone", "job": "Producer"}
            ]
          },
          "release_dates": {"results": [
            {"iso_3166_1": "US", "release_dates": [{"certification": "R"}]},
            {"iso_3166_1": "FR", "release_dates": [{"certification": ""}, {"certification": "12"}]}
          ]},
          "videos": {"results": [
            {"key": "unofficial", "site": "YouTube", "type": "Trailer", "official": false, "iso_639_1": "fr"},
            {"key": "official-en", "site": "YouTube", "type": "Trailer", "official": true, "iso_639_1": "en"},
            {"key": "clip", "site": "YouTube", "type": "Clip", "official": true, "iso_639_1": "fr"}
          ]},
          "images": {"logos": [
            {"file_path": "/logo-en.png", "iso_639_1": "en", "vote_average": 9.0},
            {"file_path": "/logo-fr.png", "iso_639_1": "fr", "vote_average": 1.0}
          ]},
          "unknown_field": 1
        }
    """.trimIndent()

    @Test
    fun movieDetailsAreMappedWithLocalCertificationLanguageLogoAndOfficialTrailer() {
        val movie = TmdbMapper.movie(json.decodeFromString<MovieDetailsDto>(movieJson), "fr-FR")

        assertThat(movie.tmdbId).isEqualTo(603)
        assertThat(movie.imdbId).isEqualTo("tt0133093")
        assertThat(movie.year).isEqualTo(1999)
        assertThat(movie.runtimeMinutes).isEqualTo(136)
        assertThat(movie.certification).isEqualTo("12") // FR, skipping the empty entry
        assertThat(movie.logoPath).isEqualTo("/logo-fr.png") // user's language wins over the better rated English one
        assertThat(movie.trailerKey).isEqualTo("official-en") // official before language match
        assertThat(movie.genres.map { it.name }).containsExactly("Action", "Science-Fiction").inOrder()
        assertThat(movie.collection?.name).isEqualTo("Matrix - Saga")
        assertThat(movie.cast.filter { !it.isDirector }.map { it.name }).containsExactly("Keanu Reeves", "Laurence Fishburne").inOrder()
        assertThat(movie.cast.filter { it.isDirector }.map { it.name }).containsExactly("Lana Wachowski", "Lilly Wachowski")
    }

    @Test
    fun certificationFallsBackToUsWhenTheRegionHasNone() {
        val dto = json.decodeFromString<MovieDetailsDto>(movieJson).copy(
            releaseDates = json.decodeFromString<MovieDetailsDto>(movieJson).releaseDates!!.let { it.copy(results = it.results.filter { r -> r.country == "US" }) }
        )
        assertThat(TmdbMapper.movie(dto, "fr-FR").certification).isEqualTo("R")
    }

    @Test
    fun blankOverviewIsFilledFromTheFallbackLanguage() {
        val primary = json.decodeFromString<MovieDetailsDto>(movieJson).copy(overview = "", title = "")
        val fallback = primary.copy(overview = "English synopsis", title = "The Matrix")

        val movie = TmdbMapper.movie(primary, "fr-FR", fallback)

        assertThat(movie.overview).isEqualTo("English synopsis")
        assertThat(movie.title).isEqualTo("The Matrix")
    }

    @Test
    fun ratingIsAbsentWhenNobodyVoted() {
        val dto = json.decodeFromString<MovieDetailsDto>(movieJson).copy(voteCount = 0, voteAverage = 0.0)
        assertThat(TmdbMapper.movie(dto, "fr-FR").rating).isNull()
    }

    @Test
    fun logoFallsBackToEnglishThenToLanguageless() {
        val english = ImagesDto(listOf(ImageDto("/en.png", "en", 5.0), ImageDto("/de.png", "de", 9.0)))
        assertThat(TmdbMapper.logo(english, "fr-FR")).isEqualTo("/en.png")

        val languageless = ImagesDto(listOf(ImageDto("/none.png", null, 1.0), ImageDto("/de.png", "de", 9.0)))
        assertThat(TmdbMapper.logo(languageless, "fr-FR")).isEqualTo("/none.png")
        assertThat(TmdbMapper.logo(null, "fr-FR")).isNull()
    }

    @Test
    fun trailerIgnoresNonYouTubeAndNonTrailers() {
        val videos = VideosDto(listOf(VideoDto("v", "Vimeo", "Trailer"), VideoDto("t", "YouTube", "Teaser")))
        assertThat(TmdbMapper.trailer(videos, "fr-FR")).isNull()
    }

    @Test
    fun yearRejectsGarbage() {
        assertThat(TmdbMapper.year("2021-05-01")).isEqualTo(2021)
        assertThat(TmdbMapper.year("")).isNull()
        assertThat(TmdbMapper.year(null)).isNull()
        assertThat(TmdbMapper.year("0000-00-00")).isNull()
    }

    private val tvJson = """
        {
          "id": 1396, "name": "Breaking Bad", "original_name": "Breaking Bad", "first_air_date": "2008-01-20",
          "status": "Ended", "overview": "Un prof de chimie...", "vote_average": 8.9, "episode_run_time": [47],
          "external_ids": {"imdb_id": "tt0903747"},
          "content_ratings": {"results": [{"iso_3166_1": "FR", "rating": "16"}]},
          "seasons": [
            {"season_number": 0, "name": "Épisodes spéciaux", "episode_count": 2},
            {"season_number": 1, "name": "Saison 1", "episode_count": 7, "air_date": "2008-01-20"}
          ],
          "season/1": {"season_number": 1, "name": "Saison 1", "episodes": [
            {"episode_number": 1, "name": "Pilote", "overview": "", "air_date": "2008-01-20", "runtime": 58},
            {"episode_number": 2, "name": "", "overview": "Suite", "still_path": "/s2.jpg"}
          ]}
        }
    """.trimIndent()

    @Test
    fun seriesAreMappedWithSeasonsAndEpisodesAndFallbackText() {
        val tv = json.decodeFromString<TvDetailsDto>(tvJson)
        val season1 = json.decodeFromString<SeasonDetailsDto>(
            (json.parseToJsonElement(tvJson) as kotlinx.serialization.json.JsonObject)["season/1"].toString()
        )
        val fallbackSeason = season1.copy(
            episodes = listOf(
                season1.episodes[0].copy(overview = "The pilot"),
                season1.episodes[1].copy(name = "Cat's in the Bag...")
            )
        )

        val seasons = TmdbMapper.seasons(tv.seasons, mapOf(1 to season1), mapOf(1 to fallbackSeason))
        val series = TmdbMapper.series(tv, "fr-FR", seasons)

        assertThat(series.imdbId).isEqualTo("tt0903747")
        assertThat(series.year).isEqualTo(2008)
        assertThat(series.certification).isEqualTo("16")
        assertThat(series.typicalRuntimeMinutes).isEqualTo(47)
        assertThat(series.seasons.map { it.seasonNumber }).containsExactly(0, 1).inOrder()
        assertThat(series.seasons[0].episodes).isEmpty() // not fetched: only the announced count is known
        assertThat(series.seasons[0].episodeCount).isEqualTo(2)

        val episodes = series.seasons[1].episodes
        assertThat(episodes[0].title).isEqualTo("Pilote")
        assertThat(episodes[0].overview).isEqualTo("The pilot")
        assertThat(episodes[1].title).isEqualTo("Cat's in the Bag...")
        assertThat(episodes[1].stillPath).isEqualTo("/s2.jpg")
    }

    @Test
    fun seasonSummaryAloneStillProducesASeason() {
        val seasons = TmdbMapper.seasons(listOf(SeasonSummaryDto(2, "S2", episodeCount = 10)), emptyMap())
        assertThat(seasons.single().episodeCount).isEqualTo(10)
    }

    @Test
    fun pacerSpacesRequestsAndForgetsIdleTime() {
        var clock = 1_000L
        val pacer = RequestPacer(minIntervalMs = 50, now = { clock })

        assertThat(pacer.reserve()).isEqualTo(0)
        assertThat(pacer.reserve()).isEqualTo(50)
        assertThat(pacer.reserve()).isEqualTo(100)

        clock += 10_000 // long idle: no accumulated debt
        assertThat(pacer.reserve()).isEqualTo(0)
    }

    @Test
    fun retryPolicyHonoursRetryAfterAndBacksOffOnServerErrors() {
        assertThat(TmdbRateLimitInterceptor.retryDelayMs(429, "3", 0)).isEqualTo(3_000)
        assertThat(TmdbRateLimitInterceptor.retryDelayMs(429, null, 0)).isEqualTo(2_000)
        assertThat(TmdbRateLimitInterceptor.retryDelayMs(429, "999", 0)).isEqualTo(10_000) // capped
        assertThat(TmdbRateLimitInterceptor.retryDelayMs(503, null, 1)).isEqualTo(1_000)
        assertThat(TmdbRateLimitInterceptor.retryDelayMs(404, null, 0)).isNull()
        assertThat(TmdbRateLimitInterceptor.retryDelayMs(200, null, 0)).isNull()
    }
}
