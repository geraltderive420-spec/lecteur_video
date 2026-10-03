package com.lecteur.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.lecteur.core.database.entity.EpisodeEntity
import com.lecteur.core.database.entity.SeasonEntity
import com.lecteur.core.database.entity.SeriesEntity
import com.lecteur.core.database.relation.EpisodeWithFiles
import com.lecteur.core.database.relation.SeasonWithEpisodes
import com.lecteur.core.database.relation.SeriesDetailRelation
import com.lecteur.core.model.MatchState
import kotlinx.coroutines.flow.Flow

@Dao
interface SeriesDao {

    @Upsert
    suspend fun insertSeries(series: SeriesEntity): Long

    @Update
    suspend fun updateSeries(series: SeriesEntity)

    @Query("DELETE FROM series WHERE id = :id")
    suspend fun deleteSeries(id: Long)

    @Query("SELECT * FROM series WHERE id = :id")
    suspend fun getSeriesById(id: Long): SeriesEntity?

    @Query("SELECT * FROM series WHERE tmdbId = :tmdbId")
    suspend fun getSeriesByTmdbId(tmdbId: Long): SeriesEntity?

    @Transaction
    @Query("SELECT * FROM series WHERE id = :id")
    suspend fun getSeriesDetail(id: Long): SeriesDetailRelation?

    @Transaction
    @Query("SELECT * FROM series WHERE id = :id")
    fun observeSeriesDetail(id: Long): Flow<SeriesDetailRelation?>

    @Query("SELECT * FROM series ORDER BY addedAt DESC LIMIT :limit")
    fun getRecentlyAddedSeries(limit: Int = 20): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE matchState = :state ORDER BY addedAt DESC")
    fun getSeriesByMatchState(state: MatchState = MatchState.TO_VERIFY): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series ORDER BY sortTitle ASC")
    fun getAllSeriesFlow(): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series ORDER BY sortTitle ASC")
    fun getSeriesPaged(): PagingSource<Int, SeriesEntity>

    @Query("SELECT * FROM series")
    suspend fun getAllSeries(): List<SeriesEntity>

    @Query(
        """
        SELECT * FROM series
        WHERE matchState = 'UNIDENTIFIED' AND matchLocked = 0
          AND (matchAttemptedAt IS NULL OR matchAttemptedAt < :retryBefore)
        ORDER BY addedAt ASC
        LIMIT :limit
        """
    )
    suspend fun getSeriesToIdentify(retryBefore: Long, limit: Int): List<SeriesEntity>

    @Query("SELECT * FROM series WHERE matchState != 'IDENTIFIED' AND matchLocked = 0 ORDER BY sortTitle ASC")
    fun observeSeriesToReview(): Flow<List<SeriesEntity>>

    @Query("SELECT COUNT(*) FROM series WHERE matchState != 'IDENTIFIED' AND matchLocked = 0")
    fun observeReviewCount(): Flow<Int>

    @Query("UPDATE series SET matchAttemptedAt = :at WHERE id = :id")
    suspend fun setMatchAttempted(id: Long, at: Long?)

    /** Series none of whose episodes has a file any more (the folder was removed from the library). */
    @Query(
        """
        DELETE FROM series WHERE id NOT IN (
            SELECT DISTINCT e.seriesId FROM episodes e JOIN media_files m ON m.episodeId = e.id
        )
        """
    )
    suspend fun deleteOrphanSeries(): Int
}

@Dao
interface SeasonDao {

    @Upsert
    suspend fun insertSeason(season: SeasonEntity): Long

    @Upsert
    suspend fun insertSeasons(seasons: List<SeasonEntity>): List<Long>

    @Query("SELECT * FROM seasons WHERE seriesId = :seriesId ORDER BY seasonNumber ASC")
    suspend fun getSeasonsForSeries(seriesId: Long): List<SeasonEntity>

    @Query("SELECT * FROM seasons WHERE seriesId = :seriesId AND seasonNumber = :seasonNumber")
    suspend fun getSeasonByNumber(seriesId: Long, seasonNumber: Int): SeasonEntity?

    @Transaction
    @Query("SELECT * FROM seasons WHERE id = :seasonId")
    suspend fun getSeasonWithEpisodes(seasonId: Long): SeasonWithEpisodes?

    @Query("SELECT * FROM seasons WHERE id = :id")
    suspend fun getSeasonById(id: Long): SeasonEntity?

    @Query("DELETE FROM seasons WHERE id = :id")
    suspend fun deleteSeason(id: Long)
}

@Dao
interface EpisodeDao {

    @Upsert
    suspend fun insertEpisode(episode: EpisodeEntity): Long

    @Upsert
    suspend fun insertEpisodes(episodes: List<EpisodeEntity>): List<Long>

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun getEpisodeById(id: Long): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE seriesId = :seriesId AND seasonNumber = :seasonNumber AND episodeNumber = :episodeNumber")
    suspend fun getEpisode(seriesId: Long, seasonNumber: Int, episodeNumber: Int): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE seasonId = :seasonId ORDER BY episodeNumber ASC")
    suspend fun getEpisodesForSeason(seasonId: Long): List<EpisodeEntity>

    @Transaction
    @Query("SELECT * FROM episodes WHERE id = :episodeId")
    suspend fun getEpisodeWithFiles(episodeId: Long): EpisodeWithFiles?

    @Query("SELECT * FROM episodes WHERE seriesId = :seriesId ORDER BY seasonNumber ASC, episodeNumber ASC")
    suspend fun getEpisodesForSeries(seriesId: Long): List<EpisodeEntity>

    @Query("DELETE FROM episodes WHERE id = :id")
    suspend fun deleteEpisode(id: Long)

    /** Episodes known from TMDB only (no file of ours): safe to rebuild when the series is matched again. */
    @Query("SELECT * FROM episodes WHERE seriesId = :seriesId AND id NOT IN (SELECT episodeId FROM media_files WHERE episodeId IS NOT NULL)")
    suspend fun getEpisodesWithoutFiles(seriesId: Long): List<EpisodeEntity>
}
