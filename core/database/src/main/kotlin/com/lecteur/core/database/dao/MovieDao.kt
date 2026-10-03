package com.lecteur.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.lecteur.core.database.entity.MovieEntity
import com.lecteur.core.database.relation.MovieDetailRelation
import com.lecteur.core.database.relation.MovieWithFiles
import com.lecteur.core.model.MatchState
import kotlinx.coroutines.flow.Flow

@Dao
interface MovieDao {

    @Upsert
    suspend fun insertMovie(movie: MovieEntity): Long

    @Upsert
    suspend fun insertMovies(movies: List<MovieEntity>): List<Long>

    @Update
    suspend fun updateMovie(movie: MovieEntity)

    @Query("DELETE FROM movies WHERE id = :id")
    suspend fun deleteMovie(id: Long)

    @Query("SELECT * FROM movies WHERE id = :id")
    suspend fun getMovieById(id: Long): MovieEntity?

    @Query("SELECT * FROM movies WHERE tmdbId = :tmdbId")
    suspend fun getMovieByTmdbId(tmdbId: Long): MovieEntity?

    @Transaction
    @Query("SELECT * FROM movies WHERE id = :id")
    suspend fun getMovieDetail(id: Long): MovieDetailRelation?

    @Transaction
    @Query("SELECT * FROM movies WHERE id = :id")
    fun observeMovieDetail(id: Long): Flow<MovieDetailRelation?>

    @Transaction
    @Query("SELECT * FROM movies WHERE id = :id")
    suspend fun getMovieWithFiles(id: Long): MovieWithFiles?

    // Home row: Recently Added
    @Query("SELECT * FROM movies ORDER BY addedAt DESC LIMIT :limit")
    fun getRecentlyAddedMovies(limit: Int = 20): Flow<List<MovieEntity>>

    // Media requiring verification
    @Query("SELECT * FROM movies WHERE matchState = :state ORDER BY addedAt DESC")
    fun getMoviesByMatchState(state: MatchState = MatchState.TO_VERIFY): Flow<List<MovieEntity>>

    // All movies sorted by title
    @Query("SELECT * FROM movies ORDER BY sortTitle ASC")
    fun getAllMoviesFlow(): Flow<List<MovieEntity>>

    // Paged query for Library screen
    @Query("SELECT * FROM movies ORDER BY sortTitle ASC")
    fun getMoviesPaged(): PagingSource<Int, MovieEntity>

    @Query("SELECT * FROM movies ORDER BY addedAt DESC")
    fun getMoviesPagedByAddedAtDesc(): PagingSource<Int, MovieEntity>

    @Query("SELECT * FROM movies ORDER BY rating DESC")
    fun getMoviesPagedByRatingDesc(): PagingSource<Int, MovieEntity>

    @Query("SELECT * FROM movies ORDER BY year DESC")
    fun getMoviesPagedByYearDesc(): PagingSource<Int, MovieEntity>

    @Query("UPDATE movies SET matchLocked = :locked WHERE id = :id")
    suspend fun setMatchLocked(id: Long, locked: Boolean)

    @Query("SELECT * FROM movies")
    suspend fun getAllMovies(): List<MovieEntity>

    /** Movies still to look up online: never tried, or tried before [retryBefore]. Locked ones are the user's call. */
    @Query(
        """
        SELECT * FROM movies
        WHERE matchState = 'UNIDENTIFIED' AND matchLocked = 0
          AND (matchAttemptedAt IS NULL OR matchAttemptedAt < :retryBefore)
        ORDER BY addedAt ASC
        LIMIT :limit
        """
    )
    suspend fun getMoviesToIdentify(retryBefore: Long, limit: Int): List<MovieEntity>

    /** Movies listed in the review screen (low confidence or unknown), excluding the ones the user locked. */
    @Query("SELECT * FROM movies WHERE matchState != 'IDENTIFIED' AND matchLocked = 0 ORDER BY sortTitle ASC")
    fun observeMoviesToReview(): Flow<List<MovieEntity>>

    @Query("SELECT COUNT(*) FROM movies WHERE matchState != 'IDENTIFIED' AND matchLocked = 0")
    fun observeReviewCount(): Flow<Int>

    @Query("UPDATE movies SET matchAttemptedAt = :at WHERE id = :id")
    suspend fun setMatchAttempted(id: Long, at: Long?)

    @Query("DELETE FROM movies WHERE id NOT IN (SELECT DISTINCT movieId FROM media_files WHERE movieId IS NOT NULL)")
    suspend fun deleteOrphanMovies(): Int
}
