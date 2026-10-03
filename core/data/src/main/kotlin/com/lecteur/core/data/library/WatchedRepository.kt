package com.lecteur.core.data.library

import com.lecteur.core.database.dao.EpisodeDao
import com.lecteur.core.database.dao.MediaFileDao
import com.lecteur.core.database.dao.WatchStateDao
import com.lecteur.core.database.entity.MediaFileEntity
import com.lecteur.core.database.entity.WatchStateEntity
import javax.inject.Inject

/**
 * "Marquer comme vu / non vu" for a film, an episode, a season or a whole series.
 *
 * Every file of the target is marked (a film kept in 1080p and 4K is seen whichever copy was played). Marking something
 * seen leaves it at position 0 with the completed flag, so it is never offered for resuming; marking it unseen keeps the
 * track and delay choices and only resets the progress.
 */
class WatchedRepository @Inject constructor(
    private val mediaFileDao: MediaFileDao,
    private val watchDao: WatchStateDao,
    private val episodeDao: EpisodeDao
) {
    /** Replaced by tests. */
    internal var clock: () -> Long = System::currentTimeMillis

    suspend fun setMovieWatched(movieId: Long, watched: Boolean) =
        mark(mediaFileDao.getMediaFilesForMovie(movieId), watched, clock())

    suspend fun setEpisodeWatched(episodeId: Long, watched: Boolean) =
        mark(mediaFileDao.getMediaFilesForEpisode(episodeId), watched, clock())

    suspend fun setSeasonWatched(seriesId: Long, seasonNumber: Int, watched: Boolean) =
        markEpisodes(episodeDao.getEpisodesForSeries(seriesId).filter { it.seasonNumber == seasonNumber }.map { it.id }, watched)

    suspend fun setSeriesWatched(seriesId: Long, watched: Boolean) =
        markEpisodes(episodeDao.getEpisodesForSeries(seriesId).map { it.id }, watched)

    /**
     * Episodes get consecutive timestamps in their order: the "next episode" is computed after the last one watched, so
     * marking a season must leave its final episode as the most recent.
     */
    private suspend fun markEpisodes(episodeIds: List<Long>, watched: Boolean) {
        val base = clock()
        episodeIds.forEachIndexed { index, id -> mark(mediaFileDao.getMediaFilesForEpisode(id), watched, base + index) }
    }

    private suspend fun mark(files: List<MediaFileEntity>, watched: Boolean, at: Long) {
        for (file in files) {
            val existing = watchDao.getWatchState(file.id)
            if (watched) {
                val base = existing ?: WatchStateEntity(mediaFileId = file.id, durationMs = file.durationMs ?: 0)
                watchDao.upsertWatchState(
                    base.copy(
                        isCompleted = true,
                        positionMs = 0,
                        lastWatchedAt = at,
                        playCount = base.playCount + if (existing?.isCompleted == true) 0 else 1
                    )
                )
            } else if (existing != null) {
                watchDao.upsertWatchState(existing.copy(isCompleted = false, positionMs = 0))
            }
        }
    }
}
