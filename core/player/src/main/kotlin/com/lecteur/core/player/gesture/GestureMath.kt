package com.lecteur.core.player.gesture

import kotlin.math.abs
import kotlin.math.roundToLong

enum class HorizontalZone { LEFT, CENTER, RIGHT }

enum class SwipeAxis { HORIZONTAL, VERTICAL }

data class SeekPreview(val targetMs: Long, val deltaMs: Long)

/** Pure touch maths for the VLC-style gestures; the Android view only feeds it raw coordinates. */
object GestureMath {

    /** Share of the width given to each of the left and right double-tap zones; the rest is the center. */
    const val SIDE_ZONE_FRACTION = 0.35f

    /** A full-width horizontal swipe moves the playback position by this much. */
    const val SEEK_MS_PER_FULL_WIDTH = 120_000L

    fun zoneFor(x: Float, width: Float): HorizontalZone = when {
        width <= 0f -> HorizontalZone.CENTER
        x < width * SIDE_ZONE_FRACTION -> HorizontalZone.LEFT
        x > width * (1f - SIDE_ZONE_FRACTION) -> HorizontalZone.RIGHT
        else -> HorizontalZone.CENTER
    }

    /** Vertical swipes: left half = brightness, right half = volume. */
    fun isLeftHalf(x: Float, width: Float): Boolean = x < width / 2f

    /** Touches starting on the screen edges are left to the system (back / home / notification gestures). */
    fun isInDeadZone(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        sideInsetPx: Float,
        topInsetPx: Float,
        bottomInsetPx: Float
    ): Boolean = x < sideInsetPx || x > width - sideInsetPx || y < topInsetPx || y > height - bottomInsetPx

    /** Decides the swipe direction once the finger has moved past the touch slop; null while undecided. */
    fun lockAxis(totalDx: Float, totalDy: Float, slopPx: Float): SwipeAxis? {
        val ax = abs(totalDx)
        val ay = abs(totalDy)
        if (ax < slopPx && ay < slopPx) return null
        return if (ax >= ay) SwipeAxis.HORIZONTAL else SwipeAxis.VERTICAL
    }

    /** [dyPx] is positive when the finger moves down, which lowers the level. A full-height swipe spans the whole range. */
    fun levelAfterVerticalSwipe(startLevel: Float, dyPx: Float, heightPx: Float, minLevel: Float, maxLevel: Float): Float {
        if (heightPx <= 0f) return startLevel.coerceIn(minLevel, maxLevel)
        val delta = -dyPx / heightPx * (maxLevel - minLevel)
        return (startLevel + delta).coerceIn(minLevel, maxLevel)
    }

    fun seekPreview(startMs: Long, dxPx: Float, widthPx: Float, durationMs: Long): SeekPreview {
        if (widthPx <= 0f) return SeekPreview(startMs, 0)
        val raw = (dxPx / widthPx * SEEK_MS_PER_FULL_WIDTH).roundToLong()
        val upper = if (durationMs > 0) durationMs else Long.MAX_VALUE
        val target = (startMs + raw).coerceIn(0L, upper)
        return SeekPreview(target, target - startMs)
    }
}

/**
 * Cumulative double-tap skipping: tapping again on the same side within [windowMs] adds another step,
 * tapping the other side or waiting too long starts a new series.
 */
class DoubleTapSkipAccumulator(private val windowMs: Long = 900L) {
    private var side: HorizontalZone? = null
    private var lastTapAt = 0L
    private var taps = 0

    /** Signed total skipped by the current series (for the on-screen "+20 s"), 0 for the center zone. */
    fun onDoubleTap(zone: HorizontalZone, nowMs: Long, stepMs: Long): Long {
        if (zone == HorizontalZone.CENTER) {
            reset()
            return 0L
        }
        if (zone != side || nowMs - lastTapAt > windowMs) {
            taps = 0
        }
        side = zone
        lastTapAt = nowMs
        taps++
        val sign = if (zone == HorizontalZone.RIGHT) 1 else -1
        return sign * taps * stepMs
    }

    fun reset() {
        side = null
        taps = 0
        lastTapAt = 0L
    }
}

/** Volume gesture level: 0..1 is the system stream volume, 1..2 is software amplification. */
object VolumeMath {
    const val MAX_BOOST_MILLIBELS = 1000

    fun maxLevel(boostEnabled: Boolean): Float = if (boostEnabled) 2f else 1f

    fun systemFraction(level: Float): Float = level.coerceIn(0f, 1f)

    fun boostFraction(level: Float): Float = (level - 1f).coerceIn(0f, 1f)

    fun gainMillibels(level: Float): Int = (boostFraction(level) * MAX_BOOST_MILLIBELS).toInt()

    fun systemIndex(level: Float, maxIndex: Int): Int =
        (systemFraction(level) * maxIndex).roundToLong().toInt().coerceIn(0, maxIndex)

    fun percent(level: Float): Int = (level * 100f).roundToLong().toInt()
}
