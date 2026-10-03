package com.lecteur.core.player

import com.google.common.truth.Truth.assertThat
import com.lecteur.core.player.display.DisplayMode
import com.lecteur.core.player.display.VideoLayoutMath
import com.lecteur.core.player.display.VideoSize
import com.lecteur.core.player.display.ZoomMath
import com.lecteur.core.player.display.ZoomState
import com.lecteur.core.player.gesture.DoubleTapSkipAccumulator
import com.lecteur.core.player.gesture.GestureMath
import com.lecteur.core.player.gesture.HorizontalZone
import com.lecteur.core.player.gesture.SwipeAxis
import com.lecteur.core.player.gesture.VolumeMath
import org.junit.Test

class DisplayAndGestureTest {

    private val video169 = VideoSize(1920, 1080)
    private val video219 = VideoSize(2560, 1080)

    // region display modes

    @Test
    fun fitLetterboxesWideVideoInTallContainer() {
        val size = VideoLayoutMath.compute(DisplayMode.FIT, video169, 1000f, 1000f)
        assertThat(size.width).isWithin(0.01f).of(1000f)
        assertThat(size.height).isWithin(0.01f).of(562.5f)
    }

    @Test
    fun fitPillarboxesNarrowVideoInWideContainer() {
        val size = VideoLayoutMath.compute(DisplayMode.FIT, VideoSize(1440, 1080), 2000f, 1000f)
        assertThat(size.height).isWithin(0.01f).of(1000f)
        assertThat(size.width).isWithin(0.01f).of(1333.33f)
    }

    @Test
    fun fillCoversContainerAndKeepsRatio() {
        val size = VideoLayoutMath.compute(DisplayMode.FILL, video169, 1000f, 1000f)
        assertThat(size.height).isWithin(0.01f).of(1000f)
        assertThat(size.width).isWithin(0.01f).of(1777.78f)
    }

    @Test
    fun stretchUsesWholeContainer() {
        val size = VideoLayoutMath.compute(DisplayMode.STRETCH, video169, 800f, 600f)
        assertThat(size.width).isEqualTo(800f)
        assertThat(size.height).isEqualTo(600f)
    }

    @Test
    fun originalIsPixelForPixel() {
        val size = VideoLayoutMath.compute(DisplayMode.ORIGINAL, video169, 800f, 600f)
        assertThat(size.width).isEqualTo(1920f)
        assertThat(size.height).isEqualTo(1080f)
    }

    @Test
    fun originalHonoursAnamorphicPixels() {
        val size = VideoLayoutMath.compute(DisplayMode.ORIGINAL, VideoSize(720, 576, 1.4568f), 800f, 600f)
        assertThat(size.width).isWithin(0.5f).of(1049f)
        assertThat(size.height).isEqualTo(576f)
    }

    @Test
    fun forcedRatiosAreFittedWhateverTheSource() {
        val s43 = VideoLayoutMath.compute(DisplayMode.RATIO_4_3, video169, 1200f, 600f)
        assertThat(s43.width / s43.height).isWithin(0.001f).of(4f / 3f)
        assertThat(s43.height).isWithin(0.01f).of(600f)

        val s219 = VideoLayoutMath.compute(DisplayMode.RATIO_21_9, video169, 900f, 900f)
        assertThat(s219.width / s219.height).isWithin(0.001f).of(21f / 9f)
        assertThat(s219.width).isWithin(0.01f).of(900f)
    }

    @Test
    fun unknownVideoSizeFallsBackToContainer() {
        val size = VideoLayoutMath.compute(DisplayMode.FIT, VideoSize(0, 0), 640f, 360f)
        assertThat(size.width).isEqualTo(640f)
        assertThat(size.height).isEqualTo(360f)
    }

    @Test
    fun emptyContainerGivesEmptyLayout() {
        val size = VideoLayoutMath.compute(DisplayMode.FIT, video219, 0f, 500f)
        assertThat(size.width).isEqualTo(0f)
    }

    @Test
    fun displayModeCyclesAndParses() {
        assertThat(DisplayMode.FIT.next()).isEqualTo(DisplayMode.FILL)
        assertThat(DisplayMode.RATIO_21_9.next()).isEqualTo(DisplayMode.FIT)
        assertThat(DisplayMode.fromName("RATIO_4_3")).isEqualTo(DisplayMode.RATIO_4_3)
        assertThat(DisplayMode.fromName("garbage")).isEqualTo(DisplayMode.FIT)
        assertThat(DisplayMode.fromName(null)).isEqualTo(DisplayMode.FIT)
    }

    // endregion

    // region zoom

    @Test
    fun zoomIsClampedBetweenOneAndFour() {
        assertThat(ZoomMath.apply(ZoomState(), 10f, 0f, 0f, 1000f, 500f).scale).isEqualTo(4f)
        assertThat(ZoomMath.apply(ZoomState(2f), 0.1f, 0f, 0f, 1000f, 500f).scale).isEqualTo(1f)
    }

    @Test
    fun zoomingBackToOneResetsPan() {
        val zoomed = ZoomMath.apply(ZoomState(), 2f, 100f, 50f, 1000f, 500f)
        assertThat(zoomed.offsetX).isEqualTo(100f)
        val reset = ZoomMath.apply(zoomed, 0.4f, 0f, 0f, 1000f, 500f)
        assertThat(reset.isIdentity).isTrue()
    }

    @Test
    fun panCannotRevealEmptyBorders() {
        val zoomed = ZoomMath.apply(ZoomState(), 2f, 5_000f, -5_000f, 1000f, 500f)
        assertThat(zoomed.offsetX).isEqualTo(500f)
        assertThat(zoomed.offsetY).isEqualTo(-250f)
    }

    // endregion

    // region gestures

    @Test
    fun doubleTapZones() {
        assertThat(GestureMath.zoneFor(100f, 1000f)).isEqualTo(HorizontalZone.LEFT)
        assertThat(GestureMath.zoneFor(500f, 1000f)).isEqualTo(HorizontalZone.CENTER)
        assertThat(GestureMath.zoneFor(900f, 1000f)).isEqualTo(HorizontalZone.RIGHT)
        assertThat(GestureMath.zoneFor(10f, 0f)).isEqualTo(HorizontalZone.CENTER)
    }

    @Test
    fun verticalSwipeSideDecidesBrightnessOrVolume() {
        assertThat(GestureMath.isLeftHalf(200f, 1000f)).isTrue()
        assertThat(GestureMath.isLeftHalf(800f, 1000f)).isFalse()
    }

    @Test
    fun deadZonesIgnoreEdgesOnly() {
        assertThat(GestureMath.isInDeadZone(5f, 500f, 1000f, 1000f, 24f, 24f, 48f)).isTrue()
        assertThat(GestureMath.isInDeadZone(995f, 500f, 1000f, 1000f, 24f, 24f, 48f)).isTrue()
        assertThat(GestureMath.isInDeadZone(500f, 10f, 1000f, 1000f, 24f, 24f, 48f)).isTrue()
        assertThat(GestureMath.isInDeadZone(500f, 980f, 1000f, 1000f, 24f, 24f, 48f)).isTrue()
        assertThat(GestureMath.isInDeadZone(500f, 500f, 1000f, 1000f, 24f, 24f, 48f)).isFalse()
    }

    @Test
    fun axisIsLockedOnlyAfterSlop() {
        assertThat(GestureMath.lockAxis(3f, 2f, 8f)).isNull()
        assertThat(GestureMath.lockAxis(30f, 10f, 8f)).isEqualTo(SwipeAxis.HORIZONTAL)
        assertThat(GestureMath.lockAxis(-5f, -40f, 8f)).isEqualTo(SwipeAxis.VERTICAL)
    }

    @Test
    fun swipingUpRaisesAndDownLowersTheLevel() {
        val up = GestureMath.levelAfterVerticalSwipe(0.5f, -250f, 1000f, 0f, 1f)
        assertThat(up).isWithin(0.001f).of(0.75f)
        val down = GestureMath.levelAfterVerticalSwipe(0.5f, 250f, 1000f, 0f, 1f)
        assertThat(down).isWithin(0.001f).of(0.25f)
    }

    @Test
    fun levelIsClamped() {
        assertThat(GestureMath.levelAfterVerticalSwipe(0.9f, -900f, 1000f, 0f, 1f)).isEqualTo(1f)
        assertThat(GestureMath.levelAfterVerticalSwipe(0.1f, 900f, 1000f, 0f, 2f)).isEqualTo(0f)
    }

    @Test
    fun boostRangeScalesSwipeDistance() {
        // With boost the range is 0..2: the same swipe moves twice as far
        val level = GestureMath.levelAfterVerticalSwipe(1f, -250f, 1000f, 0f, 2f)
        assertThat(level).isWithin(0.001f).of(1.5f)
    }

    @Test
    fun horizontalSwipeProducesTargetAndDelta() {
        val preview = GestureMath.seekPreview(60_000, 250f, 1000f, 7_200_000)
        assertThat(preview.deltaMs).isEqualTo(30_000)
        assertThat(preview.targetMs).isEqualTo(90_000)
    }

    @Test
    fun seekPreviewIsClampedToMediaBounds() {
        assertThat(GestureMath.seekPreview(10_000, -1000f, 1000f, 100_000).targetMs).isEqualTo(0)
        assertThat(GestureMath.seekPreview(90_000, 1000f, 1000f, 100_000).targetMs).isEqualTo(100_000)
        assertThat(GestureMath.seekPreview(90_000, 1000f, 1000f, 100_000).deltaMs).isEqualTo(10_000)
    }

    @Test
    fun doubleTapsAccumulateOnTheSameSide() {
        val acc = DoubleTapSkipAccumulator(windowMs = 900)
        assertThat(acc.onDoubleTap(HorizontalZone.RIGHT, 0, 10_000)).isEqualTo(10_000)
        assertThat(acc.onDoubleTap(HorizontalZone.RIGHT, 400, 10_000)).isEqualTo(20_000)
        assertThat(acc.onDoubleTap(HorizontalZone.RIGHT, 800, 10_000)).isEqualTo(30_000)
    }

    @Test
    fun doubleTapSeriesRestartsOnOtherSideOrAfterPause() {
        val acc = DoubleTapSkipAccumulator(windowMs = 900)
        acc.onDoubleTap(HorizontalZone.RIGHT, 0, 10_000)
        assertThat(acc.onDoubleTap(HorizontalZone.LEFT, 300, 10_000)).isEqualTo(-10_000)
        assertThat(acc.onDoubleTap(HorizontalZone.LEFT, 5_000, 10_000)).isEqualTo(-10_000)
    }

    @Test
    fun centerDoubleTapSkipsNothingAndResets() {
        val acc = DoubleTapSkipAccumulator()
        acc.onDoubleTap(HorizontalZone.RIGHT, 0, 10_000)
        assertThat(acc.onDoubleTap(HorizontalZone.CENTER, 100, 10_000)).isEqualTo(0)
        assertThat(acc.onDoubleTap(HorizontalZone.RIGHT, 200, 10_000)).isEqualTo(10_000)
    }

    // endregion

    // region volume

    @Test
    fun volumeBelowOneIsSystemVolumeOnly() {
        assertThat(VolumeMath.systemIndex(0.5f, 15)).isEqualTo(8)
        assertThat(VolumeMath.gainMillibels(0.5f)).isEqualTo(0)
    }

    @Test
    fun volumeAboveOneKeepsSystemAtMaxAndAddsGain() {
        assertThat(VolumeMath.systemIndex(1.5f, 15)).isEqualTo(15)
        assertThat(VolumeMath.gainMillibels(1.5f)).isEqualTo(500)
        assertThat(VolumeMath.gainMillibels(2f)).isEqualTo(VolumeMath.MAX_BOOST_MILLIBELS)
    }

    @Test
    fun maxLevelFollowsBoostSetting() {
        assertThat(VolumeMath.maxLevel(false)).isEqualTo(1f)
        assertThat(VolumeMath.maxLevel(true)).isEqualTo(2f)
        assertThat(VolumeMath.percent(1.5f)).isEqualTo(150)
    }

    // endregion
}
