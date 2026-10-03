package com.lecteur.core.common.playback

import com.google.common.truth.Truth.assertThat
import com.lecteur.core.model.SeriesPlayKind
import org.junit.Test

class NextUpResolverTest {

    private var nextFileId = 1L

    private fun file(
        season: Int, episode: Int, series: Long = 1, completed: Boolean = false, position: Long = 0, duration: Long = 3_000_000,
        watchedAt: Long = 0, available: Boolean = true, height: Int? = 1080, size: Long = 100, episodeId: Long = series * 1000 + season * 100 + episode
    ) = EpisodeFileState(series, episodeId, season, episode, nextFileId++, available, completed, position, duration, watchedAt, 1920, height, size)

    @Test
    fun nothingWatchedMeansNothingNext() {
        assertThat(NextUpResolver.resolve(listOf(file(1, 1), file(1, 2)))).isEmpty()
    }

    @Test
    fun nextIsTheEpisodeAfterTheLastCompletedOne() {
        val rows = listOf(file(1, 1, completed = true, watchedAt = 10), file(1, 2), file(1, 3))
        val next = NextUpResolver.resolve(rows).single()
        assertThat(next.episodeId).isEqualTo(1102)
    }

    @Test
    fun anEpisodeInProgressBelongsToResumeNotToNext() {
        val rows = listOf(file(1, 1, completed = true, watchedAt = 10), file(1, 2, position = 1_000_000, watchedAt = 20), file(1, 3))
        assertThat(NextUpResolver.resolve(rows)).isEmpty()
    }

    @Test
    fun anEpisodeBelowTheNotStartedThresholdIsNotInProgress() {
        val rows = listOf(file(1, 1, completed = true, watchedAt = 10), file(1, 2, position = 10_000, watchedAt = 20), file(1, 3))
        assertThat(NextUpResolver.resolve(rows).single().episodeId).isEqualTo(1102)
    }

    @Test
    fun nextCrossesTheSeasonBoundary() {
        val rows = listOf(file(1, 9, completed = true, watchedAt = 5), file(2, 1), file(2, 2))
        assertThat(NextUpResolver.resolve(rows).single().episodeId).isEqualTo(1201)
    }

    @Test
    fun aFinishedSeriesHasNoNextEpisode() {
        val rows = listOf(file(1, 1, completed = true, watchedAt = 1), file(1, 2, completed = true, watchedAt = 2))
        assertThat(NextUpResolver.resolve(rows)).isEmpty()
    }

    @Test
    fun specialsAreNeverNext() {
        val rows = listOf(file(1, 1, completed = true, watchedAt = 1), file(0, 1))
        assertThat(NextUpResolver.resolve(rows)).isEmpty()
    }

    @Test
    fun rewatchingAnOldEpisodeSkipsWhatWasAlreadySeen() {
        val rows = listOf(
            file(1, 1, completed = true, watchedAt = 100), file(1, 2, completed = true, watchedAt = 5),
            file(1, 3, completed = true, watchedAt = 6), file(1, 4)
        )
        // Last watched is episode 1, but 2 and 3 are done: the next one to watch is 4
        assertThat(NextUpResolver.resolve(rows).single().episodeId).isEqualTo(1104)
    }

    @Test
    fun anUnavailableNextEpisodeHidesTheSeries() {
        val rows = listOf(file(1, 1, completed = true, watchedAt = 1), file(1, 2, available = false))
        assertThat(NextUpResolver.resolve(rows)).isEmpty()
    }

    @Test
    fun versionsOfAnEpisodeAreFoldedAndTheBestOneIsPlayed() {
        val e1a = file(1, 1, completed = true, watchedAt = 1, episodeId = 11)
        val e2lo = file(1, 2, height = 720, episodeId = 12)
        val e2hi = file(1, 2, height = 2160, episodeId = 12)
        val next = NextUpResolver.resolve(listOf(e1a, e2lo, e2hi)).single()
        assertThat(next.mediaFileId).isEqualTo(e2hi.mediaFileId)
    }

    @Test
    fun anAvailableVersionBeatsASharperUnavailableOne() {
        val seen = file(1, 1, completed = true, watchedAt = 1, episodeId = 11)
        val away = file(1, 2, height = 2160, available = false, episodeId = 12)
        val here = file(1, 2, height = 720, episodeId = 12)
        assertThat(NextUpResolver.resolve(listOf(seen, away, here)).single().mediaFileId).isEqualTo(here.mediaFileId)
    }

    @Test
    fun aSeenVersionMakesTheEpisodeSeen() {
        val seenLo = file(1, 1, completed = true, watchedAt = 1, height = 720, episodeId = 11)
        val unseenHi = file(1, 1, height = 2160, episodeId = 11)
        val next = file(1, 2, episodeId = 12)
        assertThat(NextUpResolver.resolve(listOf(seenLo, unseenHi, next)).single().episodeId).isEqualTo(12)
    }

    @Test
    fun seriesAreOrderedByMostRecentActivity() {
        val rows = listOf(
            file(1, 1, series = 1, completed = true, watchedAt = 10), file(1, 2, series = 1),
            file(1, 1, series = 2, completed = true, watchedAt = 50), file(1, 2, series = 2)
        )
        assertThat(NextUpResolver.resolve(rows).map { it.seriesId }).containsExactly(2L, 1L).inOrder()
    }

    // region play target

    private fun target(vararg rows: EpisodeFileState) = NextUpResolver.playTarget(NextUpResolver.summarize(rows.toList()))

    @Test
    fun anUntouchedSeriesStartsAtItsFirstEpisode() {
        val (episode, kind) = target(file(1, 2), file(1, 1), file(2, 1))!!
        assertThat(kind).isEqualTo(SeriesPlayKind.START)
        assertThat(episode.episodeId).isEqualTo(1101)
    }

    @Test
    fun anEpisodeInProgressIsResumed() {
        val (episode, kind) = target(file(1, 1, completed = true, watchedAt = 1), file(1, 2, position = 1_000_000, watchedAt = 5), file(1, 3))!!
        assertThat(kind).isEqualTo(SeriesPlayKind.RESUME)
        assertThat(episode.episodeId).isEqualTo(1102)
    }

    @Test
    fun theMostRecentlyStartedEpisodeIsTheOneResumed() {
        val (episode, _) = target(file(1, 1, position = 1_000_000, watchedAt = 5), file(1, 2, position = 1_000_000, watchedAt = 9))!!
        assertThat(episode.episodeId).isEqualTo(1102)
    }

    @Test
    fun afterAFinishedEpisodeTheNextOneIsOffered() {
        val (episode, kind) = target(file(1, 1, completed = true, watchedAt = 1), file(1, 2))!!
        assertThat(kind).isEqualTo(SeriesPlayKind.NEXT)
        assertThat(episode.episodeId).isEqualTo(1102)
    }

    @Test
    fun aFinishedSeriesOffersARewatchFromTheStart() {
        val (episode, kind) = target(file(1, 1, completed = true, watchedAt = 1), file(1, 2, completed = true, watchedAt = 2))!!
        assertThat(kind).isEqualTo(SeriesPlayKind.REWATCH)
        assertThat(episode.episodeId).isEqualTo(1101)
    }

    @Test
    fun anUnavailableNextEpisodeIsSkippedForTheNextAvailableOne() {
        val (episode, kind) = target(file(1, 1, completed = true, watchedAt = 1), file(1, 2, available = false), file(1, 3))!!
        assertThat(kind).isEqualTo(SeriesPlayKind.NEXT)
        assertThat(episode.episodeId).isEqualTo(1103)
    }

    @Test
    fun nothingPlayableMeansNoTarget() {
        assertThat(target(file(1, 1, available = false))).isNull()
    }

    @Test
    fun aSeriesOfSpecialsOnlyStillStarts() {
        val (episode, kind) = target(file(0, 1))!!
        assertThat(kind).isEqualTo(SeriesPlayKind.START)
        assertThat(episode.seasonNumber).isEqualTo(0)
    }

    // endregion
}
