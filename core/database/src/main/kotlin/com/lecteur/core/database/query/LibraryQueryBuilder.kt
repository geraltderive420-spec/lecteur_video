package com.lecteur.core.database.query

import androidx.sqlite.db.SimpleSQLiteQuery
import com.lecteur.core.model.HdrFilter
import com.lecteur.core.model.LanguageVariants
import com.lecteur.core.model.LibraryFilters
import com.lecteur.core.model.LibraryQuery
import com.lecteur.core.model.LibrarySection
import com.lecteur.core.model.SortField
import com.lecteur.core.model.SortOrder
import com.lecteur.core.model.WatchFilter

/**
 * Builds the SQL behind the library grids and the home rows.
 *
 * The inner select computes, per film or series, everything a poster shows or a sort/filter needs (watch counts, best
 * progress, file size, availability) with correlated sub-queries on indexed columns; the outer select filters and sorts
 * on those aliases. Every user-supplied value goes through a `?` argument, never into the SQL text.
 *
 * A film "is watched" when one of its files is; a series when every episode that has a file is.
 */
object LibraryQueryBuilder {

    /** Tables whose changes must refresh a query built here (for Room's invalidation tracking). */
    val OBSERVED_TABLES = arrayOf(
        "movies", "series", "episodes", "media_files", "watch_states", "movie_genres", "series_genres",
        "cast_members", "audio_tracks", "user_list_items"
    )

    private const val NOT_STARTED = "0.02"

    fun build(query: LibraryQuery): SimpleSQLiteQuery {
        val args = ArrayList<Any?>()
        val inner = if (query.section == LibrarySection.MOVIES) MOVIE_SELECT else SERIES_SELECT
        val where = conditions(query, args)

        val sql = buildString {
            append("SELECT * FROM (").append(inner).append(")")
            if (where.isNotEmpty()) append(" WHERE ").append(where.joinToString(" AND "))
            append(" ORDER BY ").append(orderBy(query))
            query.limit?.let { append(" LIMIT ").append(it.coerceIn(1, 500)) }
        }
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    private fun conditions(query: LibraryQuery, args: MutableList<Any?>): List<String> {
        val movies = query.section == LibrarySection.MOVIES
        val f = query.filters
        val out = ArrayList<String>()

        when (f.watch) {
            WatchFilter.ALL -> Unit
            WatchFilter.WATCHED -> out += "(unitCount > 0 AND watchedCount >= unitCount)"
            WatchFilter.UNWATCHED -> out += "(watchedCount = 0 AND startedCount = 0)"
            WatchFilter.IN_PROGRESS -> out += "(NOT (unitCount > 0 AND watchedCount >= unitCount) AND (watchedCount > 0 OR startedCount > 0))"
        }

        f.genreId?.let {
            out += if (movies) "id IN (SELECT movieId FROM movie_genres WHERE genreId = ?)"
            else "id IN (SELECT seriesId FROM series_genres WHERE genreId = ?)"
            args += it
        }
        f.yearFrom?.let { out += "year >= ?"; args += it }
        f.yearTo?.let { out += "year <= ?"; args += it }
        f.certification?.let { out += "certification = ?"; args += it }

        if (f.resolution.minWidth > 0) {
            out += fileCondition(movies, "(f.width >= ? OR f.height >= ?)")
            args += f.resolution.minWidth
            args += f.resolution.minHeight
        }
        when (f.hdr) {
            HdrFilter.ANY -> Unit
            HdrFilter.ANY_HDR -> out += fileCondition(movies, "f.hdrType != 'NONE'")
            HdrFilter.DOLBY_VISION -> out += fileCondition(movies, "f.hdrType = 'DOLBY_VISION'")
        }
        f.folderId?.let { out += fileCondition(movies, "f.folderId = ?"); args += it }
        f.audioLanguage?.let { code ->
            val spellings = LanguageVariants.of(code)
            out += fileCondition(
                movies,
                "EXISTS (SELECT 1 FROM audio_tracks a WHERE a.mediaFileId = f.id AND LOWER(a.language) IN (${spellings.joinToString(",") { "?" }}))"
            )
            args.addAll(spellings)
        }

        f.personId?.let {
            out += if (movies) "id IN (SELECT movieId FROM cast_members WHERE personId = ? AND movieId IS NOT NULL)"
            else "id IN (SELECT seriesId FROM cast_members WHERE personId = ? AND seriesId IS NOT NULL)"
            args += it
        }
        if (movies) f.collectionId?.let { out += "id IN (SELECT id FROM movies WHERE collectionId = ?)"; args += it }
        f.userListId?.let {
            out += if (movies) "id IN (SELECT movieId FROM user_list_items WHERE listId = ? AND movieId IS NOT NULL)"
            else "id IN (SELECT seriesId FROM user_list_items WHERE listId = ? AND seriesId IS NOT NULL)"
            args += it
        }
        return out
    }

    /** "Some file of this title satisfies [fileClause]" (a series: some file of one of its episodes). */
    private fun fileCondition(movies: Boolean, fileClause: String): String =
        if (movies) "id IN (SELECT f.movieId FROM media_files f WHERE f.movieId IS NOT NULL AND $fileClause)"
        else "id IN (SELECT e.seriesId FROM episodes e JOIN media_files f ON f.episodeId = e.id WHERE $fileClause)"

    private fun orderBy(query: LibraryQuery): String {
        val order = if (query.sort.order == SortOrder.ASC) "ASC" else "DESC"
        val tieBreak = "sortTitle COLLATE NOCASE ASC, id ASC"
        val column = when (query.sort.field) {
            SortField.TITLE -> return "sortTitle COLLATE NOCASE $order, id $order"
            SortField.ADDED -> "addedAt"
            SortField.RELEASE -> "releaseKey"
            SortField.RATING -> "rating"
            SortField.DURATION -> "duration"
            SortField.LAST_PLAYED -> "lastWatchedAt"
            SortField.FILE_SIZE -> "fileSize"
        }
        // Unknown values (no rating yet, no date) go last whichever way the order points
        return "($column IS NULL) ASC, $column $order, $tieBreak"
    }

    private val MOVIE_SELECT = """
        SELECT m.id AS id, m.title AS title, m.sortTitle AS sortTitle, m.year AS year,
            COALESCE(m.releaseDate, CAST(m.year AS TEXT)) AS releaseKey,
            m.posterPath AS posterPath, m.rating AS rating, m.addedAt AS addedAt, m.matchState AS matchState,
            m.certification AS certification,
            COALESCE(m.runtime, (SELECT MAX(f.durationMs) / 60000 FROM media_files f WHERE f.movieId = m.id)) AS duration,
            (SELECT MAX(f.size) FROM media_files f WHERE f.movieId = m.id) AS fileSize,
            1 AS unitCount,
            CASE WHEN EXISTS (
                SELECT 1 FROM watch_states w JOIN media_files f ON f.id = w.mediaFileId
                WHERE f.movieId = m.id AND w.isCompleted = 1) THEN 1 ELSE 0 END AS watchedCount,
            CASE WHEN EXISTS (
                SELECT 1 FROM watch_states w JOIN media_files f ON f.id = w.mediaFileId
                WHERE f.movieId = m.id AND w.isCompleted = 0 AND w.durationMs > 0
                  AND w.positionMs > w.durationMs * $NOT_STARTED) THEN 1 ELSE 0 END AS startedCount,
            COALESCE((SELECT MAX(1.0 * w.positionMs / w.durationMs) FROM watch_states w JOIN media_files f ON f.id = w.mediaFileId
                WHERE f.movieId = m.id AND w.isCompleted = 0 AND w.durationMs > 0
                  AND w.positionMs > w.durationMs * $NOT_STARTED), 0.0) AS progress,
            COALESCE((SELECT MAX(w.lastWatchedAt) FROM watch_states w JOIN media_files f ON f.id = w.mediaFileId
                WHERE f.movieId = m.id), 0) AS lastWatchedAt,
            (SELECT COUNT(*) FROM media_files f WHERE f.movieId = m.id AND f.isAvailable = 1) AS availableCount
        FROM movies m
    """.trimIndent()

    private val SERIES_SELECT = """
        SELECT s.id AS id, s.title AS title, s.sortTitle AS sortTitle,
            CAST(NULLIF(substr(s.firstAirDate, 1, 4), '') AS INTEGER) AS year,
            s.firstAirDate AS releaseKey,
            s.posterPath AS posterPath, s.rating AS rating, s.addedAt AS addedAt, s.matchState AS matchState,
            s.certification AS certification,
            COALESCE((SELECT MAX(e.runtime) FROM episodes e WHERE e.seriesId = s.id),
                (SELECT MAX(f.durationMs) / 60000 FROM media_files f JOIN episodes e ON e.id = f.episodeId WHERE e.seriesId = s.id)) AS duration,
            (SELECT SUM(f.size) FROM media_files f JOIN episodes e ON e.id = f.episodeId WHERE e.seriesId = s.id) AS fileSize,
            (SELECT COUNT(DISTINCT f.episodeId) FROM media_files f JOIN episodes e ON e.id = f.episodeId
                WHERE e.seriesId = s.id) AS unitCount,
            (SELECT COUNT(DISTINCT f.episodeId) FROM media_files f JOIN episodes e ON e.id = f.episodeId
                JOIN watch_states w ON w.mediaFileId = f.id
                WHERE e.seriesId = s.id AND w.isCompleted = 1) AS watchedCount,
            (SELECT COUNT(DISTINCT f.episodeId) FROM media_files f JOIN episodes e ON e.id = f.episodeId
                JOIN watch_states w ON w.mediaFileId = f.id
                WHERE e.seriesId = s.id AND w.isCompleted = 0 AND w.durationMs > 0
                  AND w.positionMs > w.durationMs * $NOT_STARTED) AS startedCount,
            0.0 AS progress,
            COALESCE((SELECT MAX(w.lastWatchedAt) FROM watch_states w JOIN media_files f ON f.id = w.mediaFileId
                JOIN episodes e ON e.id = f.episodeId WHERE e.seriesId = s.id), 0) AS lastWatchedAt,
            (SELECT COUNT(*) FROM media_files f JOIN episodes e ON e.id = f.episodeId
                WHERE e.seriesId = s.id AND f.isAvailable = 1) AS availableCount
        FROM series s
    """.trimIndent()
}

/** The filters the SQL knows how to apply, as a quick check for callers that skip querying when there is nothing to show. */
fun LibraryFilters.touchesFiles(): Boolean = resolution.minWidth > 0 || hdr != HdrFilter.ANY || folderId != null || audioLanguage != null
