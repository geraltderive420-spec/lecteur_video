package com.lecteur.core.player

import com.google.common.truth.Truth.assertThat
import com.lecteur.core.player.chapters.ChapterNavigator
import com.lecteur.core.player.chapters.MediaChapter
import com.lecteur.core.player.queue.PlaybackQueue
import com.lecteur.core.player.queue.QueueItem
import com.lecteur.core.player.queue.RepeatMode
import com.lecteur.core.player.queue.UpNext
import com.lecteur.core.player.queue.UpNextPrompt
import org.junit.Test
import kotlin.random.Random

class QueueTest {

    private fun items(count: Int) = (1..count).map { QueueItem("uri$it", "Item $it") }
    private fun queue(count: Int = 4, start: Int = 0, shuffle: Boolean = false) = PlaybackQueue(items(count), start, shuffle, Random(42))

    // region order and moves

    @Test
    fun startsAtTheRequestedItem() {
        val q = queue(start = 2)
        assertThat(q.current.uri).isEqualTo("uri3")
        assertThat(q.position).isEqualTo(3)
        assertThat(q.size).isEqualTo(4)
    }

    @Test
    fun nextAndPreviousWalkTheOrderAndStopAtTheEnds() {
        val q = queue(3)
        assertThat(q.hasPrevious()).isFalse()
        assertThat(q.next()?.uri).isEqualTo("uri2")
        assertThat(q.next()?.uri).isEqualTo("uri3")
        assertThat(q.hasNext()).isFalse()
        assertThat(q.next()).isNull()
        assertThat(q.current.uri).isEqualTo("uri3")
        assertThat(q.previous()?.uri).isEqualTo("uri2")
        assertThat(q.previous()?.uri).isEqualTo("uri1")
        assertThat(q.previous()).isNull()
    }

    @Test
    fun aSingleItemQueueHasNothingToSkipTo() {
        val q = queue(1)
        q.setRepeat(RepeatMode.ALL)
        assertThat(q.hasNext()).isFalse()
        assertThat(q.hasPrevious()).isFalse()
        assertThat(q.next()).isNull()
    }

    @Test
    fun jumpToMovesTheCursor() {
        val q = queue(4)
        assertThat(q.jumpTo(3)?.uri).isEqualTo("uri4")
        assertThat(q.jumpTo(9)).isNull()
        assertThat(q.current.uri).isEqualTo("uri4")
    }

    // endregion

    // region repeat

    @Test
    fun theQueueEndsWithoutRepeat() {
        val q = queue(2, start = 1)
        assertThat(q.peekNextOnEnd()).isNull()
        assertThat(q.advanceOnEnd()).isNull()
    }

    @Test
    fun repeatAllWrapsAround() {
        val q = queue(2, start = 1)
        q.setRepeat(RepeatMode.ALL)
        assertThat(q.peekNextOnEnd()?.uri).isEqualTo("uri1")
        assertThat(q.advanceOnEnd()?.uri).isEqualTo("uri1")
        assertThat(q.position).isEqualTo(1)
        assertThat(q.previous()?.uri).isEqualTo("uri2")
    }

    @Test
    fun repeatOneReplaysTheSameItemOnEndButNotOnADeliberateSkip() {
        val q = queue(3, start = 1)
        q.setRepeat(RepeatMode.ONE)
        assertThat(q.advanceOnEnd()?.uri).isEqualTo("uri2")
        assertThat(q.position).isEqualTo(2)
        assertThat(q.next()?.uri).isEqualTo("uri3")
    }

    @Test
    fun repeatCyclesOffAllOne() {
        val q = queue()
        assertThat(q.repeat).isEqualTo(RepeatMode.OFF)
        q.cycleRepeat(); assertThat(q.repeat).isEqualTo(RepeatMode.ALL)
        q.cycleRepeat(); assertThat(q.repeat).isEqualTo(RepeatMode.ONE)
        q.cycleRepeat(); assertThat(q.repeat).isEqualTo(RepeatMode.OFF)
    }

    // endregion

    // region shuffle

    @Test
    fun shufflingKeepsTheCurrentItemAndPlaysEveryOtherOneOnce() {
        val q = queue(10, start = 4)
        q.setShuffle(true)
        assertThat(q.isShuffled).isTrue()
        assertThat(q.current.uri).isEqualTo("uri5")
        assertThat(q.playOrder.map { it.uri }).containsExactlyElementsIn(items(10).map { it.uri })
        assertThat(q.playOrder.first().uri).isEqualTo("uri5")
        assertThat(q.playOrder.map { it.uri }).isNotEqualTo(items(10).map { it.uri })

        val seen = mutableListOf(q.current.uri)
        while (true) seen += q.next()?.uri ?: break
        assertThat(seen).containsNoDuplicates()
        assertThat(seen).hasSize(10)
    }

    @Test
    fun unshufflingRestoresTheOriginalOrderAroundTheCurrentItem() {
        val q = queue(6)
        q.setShuffle(true)
        q.next(); q.next()
        val playing = q.current
        q.setShuffle(false)
        assertThat(q.current).isEqualTo(playing)
        assertThat(q.playOrder.map { it.uri }).containsExactlyElementsIn(items(6).map { it.uri }).inOrder()
        assertThat(q.currentIndexInPlayOrder).isEqualTo(items(6).indexOf(playing))
    }

    @Test
    fun shuffleCanBeRequestedAtCreation() {
        val q = queue(8, start = 2, shuffle = true)
        assertThat(q.isShuffled).isTrue()
        assertThat(q.current.uri).isEqualTo("uri3")
    }

    @Test
    fun shuffledRepeatAllLoopsThroughTheShuffledOrder() {
        val q = queue(3)
        q.setShuffle(true)
        q.setRepeat(RepeatMode.ALL)
        val first = q.current.uri
        repeat(3) { q.advanceOnEnd() }
        assertThat(q.current.uri).isEqualTo(first)
    }

    // endregion

    // region up-next card

    @Test
    fun noCardBeforeTheCountdownWindow() {
        assertThat(UpNext.prompt(100_000, 1_000_000, 1f, 10, null)).isNull()
    }

    @Test
    fun cardCountsDownInTheLastSeconds() {
        assertThat(UpNext.prompt(990_000, 1_000_000, 1f, 10, null)).isEqualTo(UpNextPrompt(10))
        assertThat(UpNext.prompt(995_500, 1_000_000, 1f, 10, null)).isEqualTo(UpNextPrompt(5))
        assertThat(UpNext.prompt(999_999, 1_000_000, 1f, 10, null)).isEqualTo(UpNextPrompt(1))
        assertThat(UpNext.prompt(1_000_000, 1_000_000, 1f, 10, null)).isEqualTo(UpNextPrompt(0))
    }

    @Test
    fun playbackSpeedShortensTheRealCountdown() {
        // 10 s of media left at 2x is 5 s of waiting
        assertThat(UpNext.prompt(990_000, 1_000_000, 2f, 10, null)).isEqualTo(UpNextPrompt(5))
        assertThat(UpNext.prompt(970_000, 1_000_000, 2f, 10, null)).isNull()
    }

    @Test
    fun creditsShowTheCardWithoutACountdown() {
        assertThat(UpNext.prompt(900_000, 1_000_000, 1f, 10, creditsStartMs = 880_000)).isEqualTo(UpNextPrompt(null))
        assertThat(UpNext.prompt(870_000, 1_000_000, 1f, 10, creditsStartMs = 880_000)).isNull()
        // The countdown takes over in the last seconds
        assertThat(UpNext.prompt(995_000, 1_000_000, 1f, 10, creditsStartMs = 880_000)).isEqualTo(UpNextPrompt(5))
    }

    @Test
    fun aZeroCountdownOnlyShowsTheCardInTheCredits() {
        assertThat(UpNext.prompt(995_000, 1_000_000, 1f, 0, null)).isNull()
        assertThat(UpNext.prompt(995_000, 1_000_000, 1f, 0, creditsStartMs = 900_000)).isEqualTo(UpNextPrompt(null))
    }

    @Test
    fun unknownDurationShowsNothing() {
        assertThat(UpNext.prompt(5_000, 0, 1f, 10, null)).isNull()
    }

    // endregion

    // region credits chapter

    @Test
    fun creditsChapterIsFoundInTheLastPartOfTheFile() {
        val nav = ChapterNavigator(
            listOf(
                MediaChapter(0, 0, 90_000, "Opening"),
                MediaChapter(1, 90_000, 1_300_000, "Episode"),
                MediaChapter(2, 1_300_000, 1_400_000, "Ending Credits")
            )
        )
        assertThat(nav.creditsStart(1_400_000)).isEqualTo(1_300_000)
    }

    @Test
    fun aCreditsChapterAtTheStartIsTheOpeningTitlesNotTheEndCredits() {
        val nav = ChapterNavigator(listOf(MediaChapter(0, 0, 60_000, "Credits"), MediaChapter(1, 60_000, 1_400_000, "Film")))
        assertThat(nav.creditsStart(1_400_000)).isNull()
    }

    @Test
    fun noChaptersNoCredits() {
        assertThat(ChapterNavigator(emptyList()).creditsStart(1_000_000)).isNull()
        assertThat(ChapterNavigator(listOf(MediaChapter(0, 900_000, 1_000_000, null))).creditsStart(1_000_000)).isNull()
    }

    // endregion
}
