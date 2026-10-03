package com.lecteur.core.player

import com.google.common.truth.Truth.assertThat
import com.lecteur.core.model.WatchState
import com.lecteur.core.player.resume.PlaybackSnapshot
import com.lecteur.core.player.resume.ResumePolicy
import com.lecteur.core.player.resume.ResumeStatus
import com.lecteur.core.player.resume.ResumeTracker
import com.lecteur.core.player.resume.WatchStateStore
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ResumeTest {

    private val hour = 3_600_000L

    // region ResumePolicy

    @Test
    fun belowTwoPercentIsNotStarted() {
        assertThat(ResumePolicy.status(10_000, 1_000_000)).isEqualTo(ResumeStatus.NOT_STARTED)
        assertThat(ResumePolicy.status(0, hour)).isEqualTo(ResumeStatus.NOT_STARTED)
    }

    @Test
    fun exactlyTwoPercentIsInProgress() {
        assertThat(ResumePolicy.status(20_000, 1_000_000)).isEqualTo(ResumeStatus.IN_PROGRESS)
    }

    @Test
    fun middleIsInProgress() {
        assertThat(ResumePolicy.status(hour / 2, hour)).isEqualTo(ResumeStatus.IN_PROGRESS)
    }

    @Test
    fun ninetyPercentIsCompleted() {
        val twoHours = 2 * hour
        assertThat(ResumePolicy.status((twoHours * 0.9).toLong(), twoHours)).isEqualTo(ResumeStatus.COMPLETED)
        assertThat(ResumePolicy.status((twoHours * 0.9).toLong() - 1, twoHours)).isEqualTo(ResumeStatus.IN_PROGRESS)
    }

    @Test
    fun lastTwoMinutesOfALongMediaAreCompleted() {
        val duration = 10 * hour // 90% would only trigger after 9 h; the end window still applies
        assertThat(ResumePolicy.status(duration - 100_000, duration)).isEqualTo(ResumeStatus.COMPLETED)
    }

    @Test
    fun endWindowDoesNotApplyToShortMedia() {
        // 5 minute clip, 1m50 left: only the 90% rule counts (not reached)
        assertThat(ResumePolicy.status(190_000, 300_000)).isEqualTo(ResumeStatus.IN_PROGRESS)
    }

    @Test
    fun unknownDurationIsInProgressOnceStarted() {
        assertThat(ResumePolicy.status(5_000, 0)).isEqualTo(ResumeStatus.IN_PROGRESS)
        assertThat(ResumePolicy.status(0, 0)).isEqualTo(ResumeStatus.NOT_STARTED)
    }

    @Test
    fun resumePromptOnlyForMediaInProgress() {
        assertThat(ResumePolicy.resumePosition(hour / 2, hour, isCompleted = false)).isEqualTo(hour / 2)
        assertThat(ResumePolicy.resumePosition(hour / 2, hour, isCompleted = true)).isNull()
        assertThat(ResumePolicy.resumePosition(1_000, hour, isCompleted = false)).isNull()
        assertThat(ResumePolicy.resumePosition(hour - 1_000, hour, isCompleted = false)).isNull()
    }

    // endregion

    // region ResumeTracker

    private class FakeStore : WatchStateStore {
        val saved = mutableListOf<WatchState>()
        override suspend fun get(mediaFileId: Long): WatchState? = saved.lastOrNull()
        override suspend fun save(state: WatchState) {
            saved += state
        }
    }

    private fun snapshot(
        position: Long,
        duration: Long = hour,
        playing: Boolean = true,
        audio: Int? = null,
        subtitle: Int? = null
    ) = PlaybackSnapshot(position, duration, playing, audio, subtitle, 0, 0, "FIT")

    @Test
    fun savesAtMostEveryFiveSecondsWhilePlaying() = runTest {
        var now = 1_000L
        val store = FakeStore()
        val tracker = ResumeTracker(1, store, null, clock = { now })

        tracker.onProgress(snapshot(600_000))
        now += 1_000
        tracker.onProgress(snapshot(601_000))
        now += 3_000
        tracker.onProgress(snapshot(604_000))
        assertThat(store.saved).hasSize(1)

        now += 1_500 // 5.5 s since the first write
        tracker.onProgress(snapshot(605_500))
        assertThat(store.saved).hasSize(2)
        assertThat(store.saved.last().positionMs).isEqualTo(605_500)
    }

    @Test
    fun doesNotWritePeriodicallyWhilePaused() = runTest {
        val store = FakeStore()
        val tracker = ResumeTracker(1, store, null, clock = { 1_000L })
        tracker.onProgress(snapshot(600_000, playing = false))
        assertThat(store.saved).isEmpty()
    }

    @Test
    fun flushWritesImmediatelyThenSkipsIdenticalSnapshots() = runTest {
        val store = FakeStore()
        val tracker = ResumeTracker(1, store, null, clock = { 1_000L })
        val s = snapshot(600_000, playing = false)
        tracker.flush(s)
        tracker.flush(s)
        assertThat(store.saved).hasSize(1)
        tracker.flush(s.copy(positionMs = 650_000))
        assertThat(store.saved).hasSize(2)
    }

    @Test
    fun positionBelowTwoPercentIsStoredAsZero() = runTest {
        val store = FakeStore()
        ResumeTracker(1, store, null, clock = { 1L }).flush(snapshot(5_000))
        assertThat(store.saved.single().positionMs).isEqualTo(0)
        assertThat(store.saved.single().isCompleted).isFalse()
    }

    @Test
    fun reachingTheEndMarksCompletedAndCountsOnePlay() = runTest {
        var now = 0L
        val store = FakeStore()
        val tracker = ResumeTracker(1, store, null, clock = { now })

        tracker.flush(snapshot(hour - 60_000, playing = false))
        now += 10_000
        tracker.flush(snapshot(hour - 30_000, playing = false))

        assertThat(store.saved.last().isCompleted).isTrue()
        assertThat(store.saved.last().playCount).isEqualTo(1)
    }

    @Test
    fun rewatchingACompletedMediaUncompletesIt() = runTest {
        val previous = WatchState(id = 7, mediaFileId = 1, positionMs = hour, durationMs = hour, isCompleted = true, playCount = 1)
        val store = FakeStore()
        ResumeTracker(1, store, previous, clock = { 1L }).flush(snapshot(hour / 2, playing = false))

        val saved = store.saved.single()
        assertThat(saved.id).isEqualTo(7)
        assertThat(saved.isCompleted).isFalse()
        assertThat(saved.playCount).isEqualTo(1)
    }

    @Test
    fun manualSeenFlagSurvivesJustOpeningTheFile() = runTest {
        val previous = WatchState(id = 7, mediaFileId = 1, isCompleted = true, playCount = 1)
        val store = FakeStore()
        ResumeTracker(1, store, previous, clock = { 1L }).flush(snapshot(3_000, playing = false))
        assertThat(store.saved.single().isCompleted).isTrue()
    }

    @Test
    fun trackChoicesAndDisplayModeArePersisted() = runTest {
        val store = FakeStore()
        ResumeTracker(1, store, null, clock = { 1L })
            .flush(snapshot(hour / 2, audio = 2, subtitle = 1).copy(audioDelayMs = 150, subtitleDelayMs = -300))
        val saved = store.saved.single()
        assertThat(saved.selectedAudioTrackIndex).isEqualTo(2)
        assertThat(saved.selectedSubtitleTrackIndex).isEqualTo(1)
        assertThat(saved.audioDelayMs).isEqualTo(150)
        assertThat(saved.subtitleDelayMs).isEqualTo(-300)
        assertThat(saved.displayMode).isEqualTo("FIT")
    }

    // endregion
}
