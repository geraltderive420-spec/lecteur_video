package com.lecteur.core.player

import com.google.common.truth.Truth.assertThat
import com.lecteur.core.model.HdrType
import com.lecteur.core.player.chapters.ChapterNavigator
import com.lecteur.core.player.chapters.MediaChapter
import com.lecteur.core.player.hdr.HdrOutput
import com.lecteur.core.player.hdr.HdrStatusResolver
import com.lecteur.core.player.probe.ChapterUnits
import com.lecteur.core.player.sleep.SleepTimer
import com.lecteur.core.player.sleep.SleepTimerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChaptersSleepHdrTest {

    private val chapters = listOf(
        MediaChapter(0, 0, 90_000, "Opening"),
        MediaChapter(1, 90_000, 600_000, "Chapter 1"),
        MediaChapter(2, 600_000, 1_200_000, null)
    )
    private val nav = ChapterNavigator(chapters)

    // region chapters

    @Test
    fun currentChapterFollowsPosition() {
        assertThat(nav.currentIndex(0)).isEqualTo(0)
        assertThat(nav.currentIndex(89_999)).isEqualTo(0)
        assertThat(nav.currentIndex(90_000)).isEqualTo(1)
        assertThat(nav.currentIndex(5_000_000)).isEqualTo(2)
        assertThat(ChapterNavigator(emptyList()).currentIndex(10)).isEqualTo(-1)
    }

    @Test
    fun nextChapterStartOrNullAtTheEnd() {
        assertThat(nav.nextStart(10_000)).isEqualTo(90_000)
        assertThat(nav.nextStart(700_000)).isNull()
    }

    @Test
    fun previousRestartsChapterWhenFarInThenGoesBack() {
        assertThat(nav.previousStart(100_000 + 10_000)).isEqualTo(90_000) // 20 s into chapter 1: restart it
        assertThat(nav.previousStart(90_000 + 1_000)).isEqualTo(0) // right after the start: previous chapter
        assertThat(nav.previousStart(1_000)).isEqualTo(0) // first chapter restarts itself
    }

    @Test
    fun unsortedChaptersAreSorted() {
        val shuffled = ChapterNavigator(chapters.reversed())
        assertThat(shuffled.chapters.map { it.index }).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun introSkipTargetsEndOfTheOpeningChapter() {
        assertThat(nav.introSkipTarget(30_000)).isEqualTo(90_000)
        assertThat(nav.introSkipTarget(100_000)).isNull()
        assertThat(ChapterNavigator(listOf(MediaChapter(0, 0, 50_000, "Générique"))).introSkipTarget(10_000)).isEqualTo(50_000)
        assertThat(ChapterNavigator(listOf(MediaChapter(0, 0, 50_000, "Opposition"))).introSkipTarget(10_000)).isNull()
    }

    @Test
    fun chapterUnitsMicrosecondsAreRescaled() {
        val micro = listOf(MediaChapter(0, 0, 600_000_000, "A"), MediaChapter(1, 600_000_000, 1_200_000_000, "B"))
        val fixed = ChapterUnits.normalize(micro, 1_200_000)
        assertThat(fixed.map { it.startMs }).containsExactly(0L, 600_000L).inOrder()
        assertThat(fixed.last().endMs).isEqualTo(1_200_000L)
    }

    @Test
    fun chapterUnitsMillisecondsAreKept() {
        assertThat(ChapterUnits.normalize(chapters, 1_200_000)).isEqualTo(chapters)
        assertThat(ChapterUnits.normalize(chapters, 0)).isEqualTo(chapters)
    }

    // endregion

    // region sleep timer

    @Test
    fun timerFiresAfterTheDelayAndCountsDown() = runTest {
        var fired = 0
        val timer = SleepTimer(backgroundScope) { fired++ }
        timer.startAfter(3_000)
        advanceTimeBy(1_001)
        assertThat(timer.state.value).isEqualTo(SleepTimerState.Counting(2_000))
        advanceTimeBy(2_000)
        runCurrent()
        assertThat(fired).isEqualTo(1)
        assertThat(timer.state.value).isEqualTo(SleepTimerState.Off)
    }

    @Test
    fun cancelledTimerNeverFires() = runTest {
        var fired = 0
        val timer = SleepTimer(backgroundScope) { fired++ }
        timer.startAfter(5_000)
        advanceTimeBy(2_000)
        timer.cancel()
        advanceTimeBy(10_000)
        assertThat(fired).isEqualTo(0)
        assertThat(timer.state.value).isEqualTo(SleepTimerState.Off)
    }

    @Test
    fun restartingReplacesThePreviousTimer() = runTest {
        var fired = 0
        val timer = SleepTimer(backgroundScope) { fired++ }
        timer.startAfter(2_000)
        advanceTimeBy(1_000)
        timer.startAfter(5_000)
        advanceTimeBy(2_500)
        assertThat(fired).isEqualTo(0)
        advanceTimeBy(3_000)
        runCurrent()
        assertThat(fired).isEqualTo(1)
    }

    @Test
    fun endOfMediaTimerFiresOnlyWhenTheMediaEnds() = runTest {
        var fired = 0
        val timer = SleepTimer(backgroundScope) { fired++ }
        timer.onMediaEnded() // no timer armed
        assertThat(fired).isEqualTo(0)
        timer.startUntilEndOfMedia()
        advanceTimeBy(10_000_000)
        assertThat(fired).isEqualTo(0)
        timer.onMediaEnded()
        assertThat(fired).isEqualTo(1)
        assertThat(timer.state.value).isEqualTo(SleepTimerState.Off)
    }

    // endregion

    // region HDR

    private val hdrScreen = setOf(HdrType.HDR10, HdrType.HLG)
    private val dvScreen = setOf(HdrType.HDR10, HdrType.HLG, HdrType.DOLBY_VISION)

    @Test
    fun sdrSourceIsSdr() {
        assertThat(HdrStatusResolver.resolve(HdrType.NONE, false, dvScreen)).isEqualTo(HdrOutput.SDR)
    }

    @Test
    fun dolbyVisionBadgeOnlyWhenDecoderAndScreenSupportIt() {
        val ok = HdrStatusResolver.resolve(HdrType.DOLBY_VISION, true, dvScreen)
        assertThat(ok).isEqualTo(HdrOutput.DOLBY_VISION)
        assertThat(ok.showsDolbyVisionBadge).isTrue()
    }

    @Test
    fun dolbyVisionFallsBackToHdr10WithoutDecoder() {
        val out = HdrStatusResolver.resolve(HdrType.DOLBY_VISION, false, dvScreen)
        assertThat(out).isEqualTo(HdrOutput.DOLBY_VISION_AS_HDR10)
        assertThat(out.showsDolbyVisionBadge).isFalse()
        assertThat(out.isDegraded).isTrue()
    }

    @Test
    fun dolbyVisionFallsBackToHdr10OnNonDolbyScreen() {
        val out = HdrStatusResolver.resolve(HdrType.DOLBY_VISION, true, hdrScreen)
        assertThat(out).isEqualTo(HdrOutput.DOLBY_VISION_AS_HDR10)
        assertThat(out.showsDolbyVisionBadge).isFalse()
    }

    @Test
    fun hdrOnSdrScreenIsToneMapped() {
        for (source in listOf(HdrType.DOLBY_VISION, HdrType.HDR10, HdrType.HDR10_PLUS, HdrType.HLG)) {
            val out = HdrStatusResolver.resolve(source, true, emptySet())
            assertThat(out).isEqualTo(HdrOutput.HDR_TONE_MAPPED_TO_SDR)
            assertThat(out.showsDolbyVisionBadge).isFalse()
        }
    }

    @Test
    fun hdr10PlusFallsBackToHdr10() {
        assertThat(HdrStatusResolver.resolve(HdrType.HDR10_PLUS, false, hdrScreen)).isEqualTo(HdrOutput.HDR10)
        assertThat(HdrStatusResolver.resolve(HdrType.HDR10_PLUS, false, hdrScreen + HdrType.HDR10_PLUS)).isEqualTo(HdrOutput.HDR10_PLUS)
    }

    @Test
    fun hlgNeedsHlgScreen() {
        assertThat(HdrStatusResolver.resolve(HdrType.HLG, false, hdrScreen)).isEqualTo(HdrOutput.HLG)
        assertThat(HdrStatusResolver.resolve(HdrType.HLG, false, setOf(HdrType.HDR10))).isEqualTo(HdrOutput.HDR_TONE_MAPPED_TO_SDR)
    }

    // endregion
}
