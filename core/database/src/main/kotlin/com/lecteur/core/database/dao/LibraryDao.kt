package com.lecteur.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.lecteur.core.database.entity.AudioTrackInfoEntity
import com.lecteur.core.database.entity.CastMemberEntity
import com.lecteur.core.database.entity.EpisodeEntity
import com.lecteur.core.database.entity.GenreEntity
import com.lecteur.core.database.entity.MediaFileEntity
import com.lecteur.core.database.entity.MovieEntity
import com.lecteur.core.database.entity.MovieGenreCrossRef
import com.lecteur.core.database.entity.PersonEntity
import com.lecteur.core.database.entity.SeriesEntity
import com.lecteur.core.database.entity.SeriesGenreCrossRef
import com.lecteur.core.database.entity.SubtitleTrackInfoEntity
import com.lecteur.core.database.entity.UserListItemEntity
import com.lecteur.core.database.entity.WatchStateEntity
import com.lecteur.core.database.relation.ContinueRow
import com.lecteur.core.database.relation.EpisodeCardRow
import com.lecteur.core.database.relation.EpisodeDetailRow
import com.lecteur.core.database.relation.EpisodeStateRow
import com.lecteur.core.database.relation.FileHitRow
import com.lecteur.core.database.relation.LibraryRow
import com.lecteur.core.database.relation.PersonRow
import com.lecteur.core.database.relation.TitleRow
import kotlinx.coroutines.flow.Flow

/**
 * Read side of the interface: grids, home rows, facets, details and the sources of the search. Writes stay in the
 * entity DAOs; nothing here modifies the database.
 */
@Dao
interface LibraryDao {

    // region library grids (SQL from LibraryQueryBuilder)

    @RawQuery(
        observedEntities = [
            MovieEntity::class, SeriesEntity::class,
            EpisodeEntity::class, MediaFileEntity::class,
            WatchStateEntity::class, MovieGenreCrossRef::class,
            SeriesGenreCrossRef::class, CastMemberEntity::class,
            AudioTrackInfoEntity::class, UserListItemEntity::class
        ]
    )
    fun pagedRows(query: SupportSQLiteQuery): PagingSource<Int, LibraryRow>

    @RawQuery(
        observedEntities = [
            MovieEntity::class, SeriesEntity::class,
            EpisodeEntity::class, MediaFileEntity::class,
            WatchStateEntity::class, MovieGenreCrossRef::class,
            SeriesGenreCrossRef::class, CastMemberEntity::class,
            AudioTrackInfoEntity::class, UserListItemEntity::class
        ]
    )
    fun observeRows(query: SupportSQLiteQuery): Flow<List<LibraryRow>>

    @RawQuery
    suspend fun rows(query: SupportSQLiteQuery): List<LibraryRow>

    // endregion

    // region filter facets

    @Query("SELECT * FROM genres WHERE id IN (SELECT genreId FROM movie_genres) ORDER BY name COLLATE NOCASE")
    suspend fun movieGenres(): List<GenreEntity>

    @Query("SELECT * FROM genres WHERE id IN (SELECT genreId FROM series_genres) ORDER BY name COLLATE NOCASE")
    suspend fun seriesGenres(): List<GenreEntity>

    @Query("SELECT DISTINCT year FROM movies WHERE year IS NOT NULL")
    suspend fun movieYears(): List<Int>

    @Query("SELECT DISTINCT CAST(substr(firstAirDate, 1, 4) AS INTEGER) FROM series WHERE firstAirDate IS NOT NULL AND length(firstAirDate) >= 4")
    suspend fun seriesYears(): List<Int>

    @Query("SELECT DISTINCT certification FROM movies WHERE certification IS NOT NULL AND certification != '' ORDER BY certification")
    suspend fun movieCertifications(): List<String>

    @Query("SELECT DISTINCT certification FROM series WHERE certification IS NOT NULL AND certification != '' ORDER BY certification")
    suspend fun seriesCertifications(): List<String>

    @Query("SELECT DISTINCT language FROM audio_tracks WHERE language IS NOT NULL AND language != '' ORDER BY language")
    suspend fun audioLanguages(): List<String>

    // endregion

    // region home rows

    @Query(
        """
        SELECT f.id AS mediaFileId, f.fileName AS fileName, w.positionMs AS positionMs, w.durationMs AS durationMs,
            w.lastWatchedAt AS lastWatchedAt, f.isAvailable AS isAvailable,
            f.movieId AS movieId, e.id AS episodeId, e.seriesId AS seriesId,
            e.seasonNumber AS seasonNumber, e.episodeNumber AS episodeNumber, e.title AS episodeTitle,
            COALESCE(m.title, s.title) AS title, COALESCE(m.posterPath, s.posterPath) AS posterPath,
            COALESCE(m.backdropPath, s.backdropPath) AS backdropPath, e.stillPath AS stillPath
        FROM watch_states w
        JOIN media_files f ON f.id = w.mediaFileId
        LEFT JOIN movies m ON m.id = f.movieId
        LEFT JOIN episodes e ON e.id = f.episodeId
        LEFT JOIN series s ON s.id = e.seriesId
        WHERE w.isCompleted = 0 AND w.durationMs > 0
          AND w.positionMs > w.durationMs * 0.02 AND w.positionMs < w.durationMs * 0.90
        ORDER BY w.lastWatchedAt DESC
        LIMIT :limit
        """
    )
    fun observeContinueWatching(limit: Int): Flow<List<ContinueRow>>

    /** Every file of every series the user has started: [com.lecteur.core.common.playback.NextUpResolver] picks the next episodes. */
    @Query(
        """
        SELECT e.seriesId AS seriesId, e.id AS episodeId, e.seasonNumber AS seasonNumber, e.episodeNumber AS episodeNumber,
            f.id AS mediaFileId, f.isAvailable AS isAvailable, COALESCE(w.isCompleted, 0) AS completed,
            COALESCE(w.positionMs, 0) AS positionMs, COALESCE(w.durationMs, 0) AS durationMs,
            COALESCE(w.lastWatchedAt, 0) AS lastWatchedAt, f.width AS width, f.height AS height, f.size AS size
        FROM episodes e
        JOIN media_files f ON f.episodeId = e.id
        LEFT JOIN watch_states w ON w.mediaFileId = f.id
        WHERE e.seriesId IN (
            SELECT e2.seriesId FROM episodes e2
            JOIN media_files f2 ON f2.episodeId = e2.id
            JOIN watch_states w2 ON w2.mediaFileId = f2.id
            WHERE w2.isCompleted = 1 OR w2.positionMs > 0
        )
        """
    )
    fun observeFollowedEpisodeStates(): Flow<List<EpisodeStateRow>>

    @Query(
        """
        SELECT e.seriesId AS seriesId, e.id AS episodeId, e.seasonNumber AS seasonNumber, e.episodeNumber AS episodeNumber,
            f.id AS mediaFileId, f.isAvailable AS isAvailable, COALESCE(w.isCompleted, 0) AS completed,
            COALESCE(w.positionMs, 0) AS positionMs, COALESCE(w.durationMs, 0) AS durationMs,
            COALESCE(w.lastWatchedAt, 0) AS lastWatchedAt, f.width AS width, f.height AS height, f.size AS size
        FROM episodes e
        JOIN media_files f ON f.episodeId = e.id
        LEFT JOIN watch_states w ON w.mediaFileId = f.id
        WHERE e.seriesId = :seriesId
        """
    )
    suspend fun episodeStates(seriesId: Long): List<EpisodeStateRow>

    @Query(
        """
        SELECT e.id AS episodeId, e.seriesId AS seriesId, e.seasonNumber AS seasonNumber, e.episodeNumber AS episodeNumber,
            e.title AS episodeTitle, e.stillPath AS stillPath, s.title AS seriesTitle, s.posterPath AS posterPath,
            s.backdropPath AS backdropPath
        FROM episodes e JOIN series s ON s.id = e.seriesId
        WHERE e.id IN (:episodeIds)
        """
    )
    suspend fun episodeCards(episodeIds: List<Long>): List<EpisodeCardRow>

    // endregion

    // region details

    @Query(
        """
        SELECT e.id AS episodeId, e.seasonNumber AS seasonNumber, e.episodeNumber AS episodeNumber, e.title AS title,
            e.overview AS overview, e.stillPath AS stillPath, e.airDate AS airDate, e.runtime AS runtime,
            f.id AS mediaFileId, f.fileName AS fileName, f.isAvailable AS isAvailable, f.width AS width, f.height AS height,
            f.size AS size, f.durationMs AS fileDurationMs, f.hdrType AS hdrType,
            w.isCompleted AS completed, w.positionMs AS positionMs, w.durationMs AS watchDurationMs, w.lastWatchedAt AS lastWatchedAt
        FROM episodes e
        LEFT JOIN media_files f ON f.episodeId = e.id
        LEFT JOIN watch_states w ON w.mediaFileId = f.id
        WHERE e.seriesId = :seriesId
        ORDER BY e.seasonNumber, e.episodeNumber, f.id
        """
    )
    fun observeEpisodeRows(seriesId: Long): Flow<List<EpisodeDetailRow>>

    @Query(
        """
        SELECT w.* FROM watch_states w JOIN media_files f ON f.id = w.mediaFileId WHERE f.movieId = :movieId
        """
    )
    fun observeWatchStatesForMovie(movieId: Long): Flow<List<WatchStateEntity>>

    @Query("SELECT a.* FROM audio_tracks a JOIN media_files f ON f.id = a.mediaFileId WHERE f.movieId = :movieId ORDER BY a.mediaFileId, a.trackIndex")
    fun observeAudioTracksForMovie(movieId: Long): Flow<List<AudioTrackInfoEntity>>

    @Query("SELECT s.* FROM subtitle_tracks s JOIN media_files f ON f.id = s.mediaFileId WHERE f.movieId = :movieId ORDER BY s.mediaFileId, s.trackIndex")
    fun observeSubtitleTracksForMovie(movieId: Long): Flow<List<SubtitleTrackInfoEntity>>

    @Query("SELECT * FROM movies WHERE collectionId = :collectionId AND id != :excludeId ORDER BY year, sortTitle COLLATE NOCASE")
    suspend fun otherMoviesInCollection(collectionId: Long, excludeId: Long): List<MovieEntity>

    @Query("SELECT * FROM cast_members WHERE movieId = :movieId ORDER BY isDirector DESC, `order`")
    suspend fun castForMovie(movieId: Long): List<CastMemberEntity>

    @Query("SELECT name FROM genres WHERE id = :id")
    suspend fun genreName(id: Long): String?

    @Query("SELECT * FROM persons WHERE id = :id")
    suspend fun person(id: Long): PersonEntity?

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun episode(id: Long): EpisodeEntity?

    // endregion

    // region search sources

    @Query("SELECT id, title, originalTitle, year, posterPath FROM movies")
    fun observeMovieTitles(): Flow<List<TitleRow>>

    @Query("SELECT id, title, originalTitle, CAST(NULLIF(substr(firstAirDate, 1, 4), '') AS INTEGER) AS year, posterPath FROM series")
    fun observeSeriesTitles(): Flow<List<TitleRow>>

    @Query(
        """
        SELECT personId, personName, MAX(profilePath) AS profilePath, COUNT(*) AS titleCount, MAX(isDirector) AS isDirector
        FROM cast_members GROUP BY personId
        """
    )
    fun observePeople(): Flow<List<PersonRow>>

    /** Plain SQL `LIKE`: file names are mostly ASCII, so accent folding is not needed here. [pattern] is already escaped. */
    @Query(
        """
        SELECT f.id AS mediaFileId, f.fileName AS fileName, f.movieId AS movieId, e.seriesId AS seriesId, f.isAvailable AS isAvailable
        FROM media_files f LEFT JOIN episodes e ON e.id = f.episodeId
        WHERE f.fileName LIKE :pattern ESCAPE '\'
        ORDER BY f.fileName COLLATE NOCASE
        LIMIT :limit
        """
    )
    suspend fun findFiles(pattern: String, limit: Int): List<FileHitRow>

    // endregion
}
