package com.lecteur.core.network.tmdb

import com.lecteur.core.model.CastRef
import com.lecteur.core.model.CollectionRef
import com.lecteur.core.model.EpisodeMetadata
import com.lecteur.core.model.GenreRef
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.MetadataSearchHit
import com.lecteur.core.model.MovieMetadata
import com.lecteur.core.model.SeasonMetadata
import com.lecteur.core.model.SeriesMetadata

/** TMDB DTOs to domain models. Pure functions: everything here is covered by JSON-fixture tests. */
object TmdbMapper {

    private const val MAX_CAST = 20
    private const val DIRECTOR_ORDER_BASE = 1000

    fun year(date: String?): Int? = date?.take(4)?.toIntOrNull()?.takeIf { it in 1870..2200 }

    fun hit(dto: SearchMovieDto) = MetadataSearchHit(
        tmdbId = dto.id,
        kind = MediaKind.MOVIE,
        title = dto.title.orEmpty(),
        originalTitle = dto.originalTitle?.takeIf(String::isNotBlank),
        year = year(dto.releaseDate),
        overview = dto.overview?.takeIf(String::isNotBlank),
        posterPath = dto.posterPath,
        popularity = dto.popularity
    )

    fun hit(dto: SearchTvDto) = MetadataSearchHit(
        tmdbId = dto.id,
        kind = MediaKind.SERIES,
        title = dto.name.orEmpty(),
        originalTitle = dto.originalName?.takeIf(String::isNotBlank),
        year = year(dto.firstAirDate),
        overview = dto.overview?.takeIf(String::isNotBlank),
        posterPath = dto.posterPath,
        popularity = dto.popularity
    )

    /**
     * @param fallback the same movie in the fallback language: only used for the text fields the primary
     * language left empty (a film without a French synopsis keeps its English one).
     */
    fun movie(dto: MovieDetailsDto, language: String, fallback: MovieDetailsDto? = null): MovieMetadata = MovieMetadata(
        tmdbId = dto.id,
        imdbId = dto.imdbId?.takeIf(String::isNotBlank),
        title = dto.title.nonBlankOr(fallback?.title).orEmpty(),
        originalTitle = dto.originalTitle?.takeIf(String::isNotBlank),
        releaseDate = dto.releaseDate?.takeIf(String::isNotBlank),
        year = year(dto.releaseDate),
        overview = dto.overview.nonBlankOr(fallback?.overview),
        runtimeMinutes = dto.runtime?.takeIf { it > 0 },
        rating = dto.voteAverage?.takeIf { dto.voteCount > 0 }?.toFloat(),
        certification = movieCertification(dto.releaseDates, regionOf(language)),
        posterPath = dto.posterPath,
        backdropPath = dto.backdropPath,
        logoPath = logo(dto.images, language),
        trailerKey = trailer(dto.videos, language),
        genres = dto.genres.map { GenreRef(it.id, it.name) },
        cast = cast(dto.credits),
        collection = dto.collection?.let { CollectionRef(it.id, it.name, posterPath = it.posterPath, backdropPath = it.backdropPath) }
    )

    fun series(
        dto: TvDetailsDto,
        language: String,
        seasons: List<SeasonMetadata>,
        fallback: TvDetailsDto? = null
    ): SeriesMetadata = SeriesMetadata(
        tmdbId = dto.id,
        imdbId = dto.externalIds?.imdbId?.takeIf(String::isNotBlank),
        title = dto.name.nonBlankOr(fallback?.name).orEmpty(),
        originalTitle = dto.originalName?.takeIf(String::isNotBlank),
        firstAirDate = dto.firstAirDate?.takeIf(String::isNotBlank),
        year = year(dto.firstAirDate),
        status = dto.status?.takeIf(String::isNotBlank),
        overview = dto.overview.nonBlankOr(fallback?.overview),
        rating = dto.voteAverage?.takeIf { it > 0.0 }?.toFloat(),
        certification = tvCertification(dto.contentRatings, regionOf(language)),
        posterPath = dto.posterPath,
        backdropPath = dto.backdropPath,
        logoPath = logo(dto.images, language),
        trailerKey = trailer(dto.videos, language),
        typicalRuntimeMinutes = dto.episodeRunTime.firstOrNull { it > 0 },
        genres = dto.genres.map { GenreRef(it.id, it.name) },
        cast = cast(dto.credits),
        seasons = seasons
    )

    /** Season list of the series; [details] (season number to its detailed form) adds the episodes where they were fetched. */
    fun seasons(
        summaries: List<SeasonSummaryDto>,
        details: Map<Int, SeasonDetailsDto>,
        fallbackDetails: Map<Int, SeasonDetailsDto> = emptyMap()
    ): List<SeasonMetadata> = summaries.sortedBy { it.seasonNumber }.map { summary ->
        val detail = details[summary.seasonNumber]
        val fallback = fallbackDetails[summary.seasonNumber]
        SeasonMetadata(
            seasonNumber = summary.seasonNumber,
            name = (detail?.name ?: summary.name).nonBlankOr(fallback?.name),
            overview = (detail?.overview ?: summary.overview).nonBlankOr(fallback?.overview),
            posterPath = detail?.posterPath ?: summary.posterPath,
            airDate = (detail?.airDate ?: summary.airDate)?.takeIf(String::isNotBlank),
            episodeCount = summary.episodeCount,
            episodes = detail?.episodes.orEmpty().map { episode ->
                val fb = fallback?.episodes?.firstOrNull { it.episodeNumber == episode.episodeNumber }
                EpisodeMetadata(
                    episodeNumber = episode.episodeNumber,
                    title = episode.name.nonBlankOr(fb?.name),
                    overview = episode.overview.nonBlankOr(fb?.overview),
                    stillPath = episode.stillPath,
                    airDate = episode.airDate?.takeIf(String::isNotBlank),
                    runtimeMinutes = episode.runtime?.takeIf { it > 0 }
                )
            }
        )
    }

    /** "fr-FR" -> "FR". */
    fun regionOf(language: String): String = language.substringAfter('-', missingDelimiterValue = language).uppercase()

    fun languageOf(language: String): String = language.substringBefore('-').lowercase()

    fun movieCertification(dto: ReleaseDatesDto?, region: String): String? {
        val byCountry = dto?.results.orEmpty().associateBy { it.country }
        return listOf(region, "US").firstNotNullOfOrNull { country ->
            byCountry[country]?.releaseDates?.firstNotNullOfOrNull { it.certification?.takeIf(String::isNotBlank) }
        }
    }

    fun tvCertification(dto: ContentRatingsDto?, region: String): String? {
        val byCountry = dto?.results.orEmpty().associateBy { it.country }
        return listOf(region, "US").firstNotNullOfOrNull { byCountry[it]?.rating?.takeIf(String::isNotBlank) }
    }

    /** Logo in the user's language, else English, else a language-less one; the best rated within that language. */
    fun logo(images: ImagesDto?, language: String): String? {
        val logos = images?.logos.orEmpty()
        val primary = languageOf(language)
        return listOf<(ImageDto) -> Boolean>(
            { it.language == primary },
            { it.language == "en" },
            { it.language == null }
        ).firstNotNullOfOrNull { matches -> logos.filter(matches).maxByOrNull { it.voteAverage }?.filePath }
    }

    /** YouTube trailer, official ones and the user's language first. */
    fun trailer(videos: VideosDto?, language: String): String? {
        val primary = languageOf(language)
        return videos?.results.orEmpty()
            .filter { it.site.equals("YouTube", ignoreCase = true) && it.type.equals("Trailer", ignoreCase = true) }
            .sortedWith(compareByDescending<VideoDto> { it.official }.thenByDescending { it.language == primary })
            .firstOrNull()?.key
    }

    fun cast(credits: CreditsDto?): List<CastRef> {
        if (credits == null) return emptyList()
        val actors = credits.cast.sortedBy { it.order }.take(MAX_CAST).map {
            CastRef(it.id, it.name, it.profilePath, it.character?.takeIf(String::isNotBlank), it.order)
        }
        val directors = credits.crew.filter { it.job == "Director" }.distinctBy { it.id }.mapIndexed { index, crew ->
            CastRef(crew.id, crew.name, crew.profilePath, "Réalisateur", DIRECTOR_ORDER_BASE + index, isDirector = true)
        }
        return actors + directors
    }

    private fun String?.nonBlankOr(other: String?): String? = this?.takeIf(String::isNotBlank) ?: other?.takeIf(String::isNotBlank)
}
