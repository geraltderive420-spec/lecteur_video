package com.lecteur.core.data.playback

import com.lecteur.core.common.playback.EpisodeSummary
import com.lecteur.core.common.playback.NextUpResolver
import com.lecteur.core.data.library.HomeRepository
import com.lecteur.core.data.library.toState
import com.lecteur.core.database.dao.LibraryDao
import com.lecteur.core.database.dao.MediaFileDao
import com.lecteur.core.database.dao.MovieDao
import com.lecteur.core.database.entity.MediaFileEntity
import com.lecteur.core.database.relation.EpisodeStateRow
import com.lecteur.core.model.PlayPlan
import com.lecteur.core.model.QueueEntry
import com.lecteur.core.model.ResolutionTier
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns "play this" into what the player runs: a film is one entry; an episode is the rest of its series from there on,
 * so the next episode follows by itself. A null plan means nothing playable is left (every copy is on an unplugged drive).
 */
class PlayPlanner @Inject constructor(
    private val mediaFileDao: MediaFileDao,
    private val movieDao: MovieDao,
    private val libraryDao: LibraryDao
) {

    suspend fun forFile(mediaFileId: Long): PlayPlan? {
        val file = mediaFileDao.getMediaFileById(mediaFileId) ?: return null
        return PlayPlan(listOf(QueueEntry(file.uri, titleFor(file), file.id)))
    }

    /** [preferredFileId] picks a version (1080p or 4K); without it the best available copy plays. */
    suspend fun forMovie(movieId: Long, preferredFileId: Long? = null): PlayPlan? {
        val movie = movieDao.getMovieById(movieId) ?: return null
        val files = mediaFileDao.getMediaFilesForMovie(movieId)
        val file = files.firstOrNull { it.id == preferredFileId && it.isAvailable } ?: bestOf(files) ?: return null
        val title = movie.year?.let { "${movie.title} ($it)" } ?: movie.title
        return PlayPlan(listOf(QueueEntry(file.uri, title, file.id)))
    }

    /** The chosen episode and those after it in the same run (specials stay with specials), best copy of each. */
    suspend fun forEpisode(seriesId: Long, episodeId: Long, preferredFileId: Long? = null): PlayPlan? {
        val summaries = NextUpResolver.summarize(libraryDao.episodeStates(seriesId).map(EpisodeStateRow::toState))
            .filter { it.bestFile.isAvailable }
        val start = summaries.indexOfFirst { it.episodeId == episodeId }
        if (start < 0) return null

        val isSpecial = summaries[start].seasonNumber == 0
        val run = summaries.drop(start).filter { (it.seasonNumber == 0) == isSpecial }
        val cards = libraryDao.episodeCards(run.map { it.episodeId }).associateBy { it.episodeId }

        val entries = run.mapIndexedNotNull { index, summary ->
            val chosen = if (index == 0 && preferredFileId != null) mediaFileDao.getMediaFileById(preferredFileId) else null
            val file = chosen?.takeIf { it.isAvailable } ?: mediaFileDao.getMediaFileById(summary.bestFile.mediaFileId) ?: return@mapIndexedNotNull null
            val card = cards[summary.episodeId]
            val title = card?.let { "${it.seriesTitle} · " + HomeRepository.episodeLabel(it.seasonNumber, it.episodeNumber, it.episodeTitle) }
                ?: PlaybackRequestFactory.displayTitle(file.fileName)
            QueueEntry(file.uri, title, file.id)
        }
        if (entries.isEmpty()) return null
        return PlayPlan(entries, 0, label = cards.values.firstOrNull()?.seriesTitle)
    }

    /** The episode the "Lire" button of the series stands for: resume, next, first, or a rewatch. */
    suspend fun forSeries(seriesId: Long): PlayPlan? {
        val summaries: List<EpisodeSummary> = NextUpResolver.summarize(libraryDao.episodeStates(seriesId).map(EpisodeStateRow::toState))
        val (target, _) = NextUpResolver.playTarget(summaries) ?: return null
        return forEpisode(seriesId, target.episodeId)
    }

    /** Files of a folder in the order given, e.g. what the explorer lists. */
    fun forEntries(entries: List<QueueEntry>, startIndex: Int = 0, label: String? = null, shuffle: Boolean = false): PlayPlan? =
        if (entries.isEmpty()) null else PlayPlan(entries, startIndex.coerceIn(entries.indices), label, shuffle)

    private suspend fun titleFor(file: MediaFileEntity): String {
        val movie = file.movieId?.let { movieDao.getMovieById(it) }
        return movie?.title ?: PlaybackRequestFactory.displayTitle(file.fileName)
    }

    private fun bestOf(files: List<MediaFileEntity>): MediaFileEntity? =
        files.filter { it.isAvailable }.sortedWith(
            compareByDescending<MediaFileEntity> { ResolutionTier.of(it.width, it.height).ordinal }.thenByDescending { it.size }.thenBy { it.id }
        ).firstOrNull()
}

/** The plan the next call to the player activity runs, handed over in memory (a long folder would overflow an Intent). */
@Singleton
class PlaybackQueueStore @Inject constructor() {

    @Volatile
    private var pending: PlayPlan? = null

    fun publish(plan: PlayPlan) {
        pending = plan
    }

    /** Hands the plan to the player once: a recreated screen must not restart it. */
    fun take(): PlayPlan? = pending.also { pending = null }
}
