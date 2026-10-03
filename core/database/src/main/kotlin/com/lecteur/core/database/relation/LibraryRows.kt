package com.lecteur.core.database.relation

/**
 * One poster of the library, aggregated in SQL (see LibraryQueryBuilder): the watch figures are computed over all the files
 * of the film, or all the episodes of the series, so the grid never loads a file or a watch state itself.
 */
data class LibraryRow(
    val id: Long,
    val title: String,
    val year: Int?,
    val posterPath: String?,
    val rating: Float?,
    val addedAt: Long,
    val matchState: String,
    val unitCount: Int,
    val watchedCount: Int,
    val startedCount: Int,
    val progress: Float,
    val availableCount: Int
)

/** A started and unfinished file for the "Reprendre" row; film or episode, identified or not. */
data class ContinueRow(
    val mediaFileId: Long,
    val fileName: String,
    val positionMs: Long,
    val durationMs: Long,
    val lastWatchedAt: Long,
    val isAvailable: Boolean,
    val movieId: Long?,
    val episodeId: Long?,
    val seriesId: Long?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val episodeTitle: String?,
    val title: String?,
    val posterPath: String?,
    val backdropPath: String?,
    val stillPath: String?
)

/** Episode of a followed series with the state of one of its files: the input of the "next episodes" computation. */
data class EpisodeStateRow(
    val seriesId: Long,
    val episodeId: Long,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val mediaFileId: Long,
    val isAvailable: Boolean,
    val completed: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val lastWatchedAt: Long,
    val width: Int?,
    val height: Int?,
    val size: Long
)

/** What the "next episode" card shows once the episode is known. */
data class EpisodeCardRow(
    val episodeId: Long,
    val seriesId: Long,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeTitle: String?,
    val stillPath: String?,
    val seriesTitle: String,
    val posterPath: String?,
    val backdropPath: String?
)

/** One line of a series' episode list: the episode and, when the library has it, one of its files with its progress. */
data class EpisodeDetailRow(
    val episodeId: Long,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String?,
    val overview: String?,
    val stillPath: String?,
    val airDate: String?,
    val runtime: Int?,
    val mediaFileId: Long?,
    val fileName: String?,
    val isAvailable: Boolean?,
    val width: Int?,
    val height: Int?,
    val size: Long?,
    val fileDurationMs: Long?,
    val hdrType: String?,
    val completed: Boolean?,
    val positionMs: Long?,
    val watchDurationMs: Long?,
    val lastWatchedAt: Long?
)

/** Everything the search index needs about a title, nothing more. */
data class TitleRow(
    val id: Long,
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val posterPath: String?
)

data class PersonRow(
    val personId: Long,
    val personName: String,
    val profilePath: String?,
    val titleCount: Int,
    val isDirector: Boolean
)

data class FileHitRow(
    val mediaFileId: Long,
    val fileName: String,
    val movieId: Long?,
    val seriesId: Long?,
    val isAvailable: Boolean
)

data class UserListRow(val id: Long, val name: String, val itemCount: Int)
