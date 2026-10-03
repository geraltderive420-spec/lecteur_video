package com.lecteur.core.common.playback

import com.lecteur.core.model.SeriesPlayKind

/** One file of one episode with what the user did with it: the raw material of the "next episodes" row. */
data class EpisodeFileState(
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
    val width: Int? = null,
    val height: Int? = null,
    val size: Long = 0
) {
    /** Started (beyond the 2% "not started" threshold of the resume rules) and not finished. */
    val inProgress: Boolean get() = !completed && durationMs > 0 && positionMs > durationMs * NOT_STARTED_FRACTION

    private companion object {
        const val NOT_STARTED_FRACTION = 0.02
    }
}

data class NextUp(val seriesId: Long, val episodeId: Long, val mediaFileId: Long, val lastActivityAt: Long)

/** An episode once its versions (1080p, 4K...) are folded together. */
data class EpisodeSummary(
    val seriesId: Long,
    val episodeId: Long,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val completed: Boolean,
    val inProgress: Boolean,
    val lastWatchedAt: Long,
    /** The version to play: available first, then the one already started, then the sharpest, then the biggest. */
    val bestFile: EpisodeFileState
)

object NextUpResolver {

    /** Season 0 holds the specials: they never come "next". */
    private const val SPECIALS = 0

    fun summarize(rows: List<EpisodeFileState>): List<EpisodeSummary> =
        rows.groupBy { it.episodeId }.values.map { versions ->
            val best = versions.sortedWith(PREFERRED_VERSION).first()
            EpisodeSummary(
                seriesId = best.seriesId,
                episodeId = best.episodeId,
                seasonNumber = best.seasonNumber,
                episodeNumber = best.episodeNumber,
                completed = versions.any { it.completed },
                inProgress = versions.any { it.inProgress },
                lastWatchedAt = versions.maxOf { it.lastWatchedAt },
                bestFile = best
            )
        }.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))

    /** For each series being followed, the episode to watch after the last one seen; most recently watched series first. */
    fun resolve(rows: List<EpisodeFileState>): List<NextUp> =
        rows.groupBy { it.seriesId }.mapNotNull { (_, seriesRows) -> nextFor(summarize(seriesRows)) }
            .sortedByDescending { it.lastActivityAt }

    /** Null when the series has not been started, has an episode to resume, is finished, or its next episode is out of reach. */
    fun nextFor(episodes: List<EpisodeSummary>): NextUp? {
        val ordered = episodes.filter { it.seasonNumber != SPECIALS }
        val touched = ordered.filter { it.completed || it.inProgress }
        if (touched.isEmpty() || touched.any { it.inProgress }) return null

        val last = touched.maxByOrNull { it.lastWatchedAt } ?: return null
        val after = ordered.dropWhile { it.episodeId != last.episodeId }.drop(1)
        val next = after.firstOrNull { !it.completed } ?: return null
        if (!next.bestFile.isAvailable) return null
        return NextUp(next.seriesId, next.episodeId, next.bestFile.mediaFileId, last.lastWatchedAt)
    }

    /** What the "Lire" button of a series plays and what it says: resume, next episode, first episode, or a rewatch. */
    fun playTarget(episodes: List<EpisodeSummary>): Pair<EpisodeSummary, SeriesPlayKind>? {
        episodes.filter { it.inProgress && it.bestFile.isAvailable }.maxByOrNull { it.lastWatchedAt }
            ?.let { return it to SeriesPlayKind.RESUME }

        val ordered = episodes.filter { it.seasonNumber != SPECIALS }.ifEmpty { episodes }
        val started = ordered.any { it.completed || it.inProgress }
        val byId = episodes.associateBy { it.episodeId }
        nextFor(episodes)?.let { next -> byId[next.episodeId]?.let { return it to SeriesPlayKind.NEXT } }

        ordered.firstOrNull { !it.completed && it.bestFile.isAvailable }
            ?.let { return it to if (started) SeriesPlayKind.NEXT else SeriesPlayKind.START }
        return ordered.firstOrNull { it.bestFile.isAvailable }?.let { it to SeriesPlayKind.REWATCH }
    }

    private val PREFERRED_VERSION: Comparator<EpisodeFileState> =
        compareByDescending<EpisodeFileState> { it.isAvailable }
            .thenByDescending { it.inProgress }
            .thenByDescending { it.height ?: 0 }
            .thenByDescending { it.size }
            .thenBy { it.mediaFileId }
}
