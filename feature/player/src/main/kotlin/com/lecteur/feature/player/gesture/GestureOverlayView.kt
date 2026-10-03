package com.lecteur.feature.player.gesture

import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.lecteur.core.player.gesture.GestureMath
import com.lecteur.core.player.gesture.HorizontalZone
import com.lecteur.core.player.gesture.SwipeAxis

/** Callbacks of [GestureOverlayView]. Coordinates are view pixels. */
interface PlayerGestureListener {
    fun onSingleTap()

    /** Fired for the 2nd tap and for every following quick tap (cumulative skipping). */
    fun onDoubleTap(zone: HorizontalZone)

    fun onLongPressStart()
    fun onLongPressEnd()

    fun onHorizontalSwipeStart()
    fun onHorizontalSwipe(totalDxPx: Float)
    fun onHorizontalSwipeEnd()

    fun onVerticalSwipeStart(leftHalf: Boolean)
    fun onVerticalSwipe(totalDyPx: Float, leftHalf: Boolean)
    fun onVerticalSwipeEnd()

    fun onPinch(scaleFactor: Float)
    fun onPan(dxPx: Float, dyPx: Float)
}

/**
 * Transparent touch surface implementing the VLC-style gestures. Decisions (zones, axis lock, dead zones) come from
 * [GestureMath]; this class only turns raw touch events into those decisions.
 *
 * Touches that start on the screen edges are ignored so system gestures (back, home, notification shade) still work.
 */
class GestureOverlayView(context: Context) : View(context) {

    var listener: PlayerGestureListener? = null

    /** False limits the surface to single taps (locked screen: a tap only brings up the unlock button). */
    var gesturesEnabled: Boolean = true

    /** When zoomed in, one-finger drags pan the picture instead of seeking / changing levels. */
    var isZoomed: Boolean = false

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val doubleTapTimeoutMs = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var ignoreSequence = false
    private var axis: SwipeAxis? = null
    private var leftHalf = false
    private var longPressActive = false
    private var scaling = false
    private var moved = false

    private var lastTapUpTime = 0L
    private var lastTapZone = HorizontalZone.CENTER
    private var doubleTapSeries = false

    private val singleTapRunnable = Runnable {
        doubleTapSeries = false
        listener?.onSingleTap()
    }

    private val longPressRunnable = Runnable {
        if (!moved && !scaling && gesturesEnabled) {
            longPressActive = true
            listener?.onLongPressStart()
        }
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            cancelPending()
            return gesturesEnabled
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            listener?.onPinch(detector.scaleFactor)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            // Swipes stay disabled until every finger is up, so lifting one finger does not trigger a seek
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (gesturesEnabled) scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(event)
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelPending()
                finishSwipe()
            }
            MotionEvent.ACTION_MOVE -> if (!ignoreSequence && !scaling && event.pointerCount == 1) onMove(event)
            MotionEvent.ACTION_UP -> onUp(event)
            MotionEvent.ACTION_CANCEL -> onCancel()
        }
        return true
    }

    private fun onDown(event: MotionEvent) {
        downX = event.x
        downY = event.y
        lastX = downX
        lastY = downY
        downTime = event.eventTime
        axis = null
        moved = false
        scaling = false
        longPressActive = false

        ignoreSequence = isInDeadZone(downX, downY)
        if (ignoreSequence) return

        removeCallbacks(singleTapRunnable)
        if (gesturesEnabled) postDelayed(longPressRunnable, longPressTimeoutMs)
    }

    private fun onMove(event: MotionEvent) {
        val totalDx = event.x - downX
        val totalDy = event.y - downY

        if (!gesturesEnabled) return

        if (axis == null && !moved) {
            if (GestureMath.lockAxis(totalDx, totalDy, touchSlop) == null) return
            moved = true
            removeCallbacks(longPressRunnable)
            if (longPressActive) return
            if (isZoomed) {
                axis = SwipeAxis.HORIZONTAL // value unused: panning handles both directions
                return
            }
            axis = GestureMath.lockAxis(totalDx, totalDy, touchSlop)
            when (axis) {
                SwipeAxis.HORIZONTAL -> listener?.onHorizontalSwipeStart()
                SwipeAxis.VERTICAL -> {
                    leftHalf = GestureMath.isLeftHalf(downX, width.toFloat())
                    listener?.onVerticalSwipeStart(leftHalf)
                }
                null -> Unit
            }
        }

        if (longPressActive) return
        when {
            isZoomed -> listener?.onPan(event.x - lastX, event.y - lastY)
            axis == SwipeAxis.HORIZONTAL -> listener?.onHorizontalSwipe(totalDx)
            axis == SwipeAxis.VERTICAL -> listener?.onVerticalSwipe(totalDy, leftHalf)
        }
        lastX = event.x
        lastY = event.y
    }

    private fun onUp(event: MotionEvent) {
        removeCallbacks(longPressRunnable)
        if (ignoreSequence) {
            ignoreSequence = false
            return
        }
        if (longPressActive) {
            longPressActive = false
            listener?.onLongPressEnd()
            return
        }
        if (scaling) {
            scaling = false
            return
        }
        if (finishSwipe()) return

        // A tap
        val now = event.eventTime
        val zone = GestureMath.zoneFor(event.x, width.toFloat())
        val withinSeries = doubleTapSeries && now - lastTapUpTime <= SERIES_WINDOW_MS
        val isSecondTap = now - lastTapUpTime <= doubleTapTimeoutMs && lastTapUpTime != 0L

        if (gesturesEnabled && (withinSeries || isSecondTap)) {
            removeCallbacks(singleTapRunnable)
            doubleTapSeries = true
            lastTapUpTime = now
            lastTapZone = zone
            listener?.onDoubleTap(zone)
        } else {
            doubleTapSeries = false
            lastTapUpTime = now
            lastTapZone = zone
            removeCallbacks(singleTapRunnable)
            // Wait to see whether a second tap follows before reporting a single tap
            if (gesturesEnabled) postDelayed(singleTapRunnable, doubleTapTimeoutMs) else listener?.onSingleTap()
        }
    }

    private fun onCancel() {
        cancelPending()
        ignoreSequence = false
        if (longPressActive) {
            longPressActive = false
            listener?.onLongPressEnd()
        }
        finishSwipe()
        scaling = false
    }

    /** Ends a swipe in progress; returns true when there was one (so the touch was not a tap). */
    private fun finishSwipe(): Boolean {
        val was = axis
        val hadMovement = moved
        axis = null
        moved = false
        if (isZoomed) return hadMovement
        when (was) {
            SwipeAxis.HORIZONTAL -> listener?.onHorizontalSwipeEnd()
            SwipeAxis.VERTICAL -> listener?.onVerticalSwipeEnd()
            null -> Unit
        }
        return hadMovement
    }

    private fun cancelPending() {
        removeCallbacks(longPressRunnable)
    }

    private fun isInDeadZone(x: Float, y: Float): Boolean {
        val gestureInsets = ViewCompat.getRootWindowInsets(this)?.getInsets(WindowInsetsCompat.Type.systemGestures())
        val side = maxOf(gestureInsets?.let { maxOf(it.left, it.right).toFloat() } ?: 0f, MIN_EDGE_DP * density)
        val top = maxOf(gestureInsets?.top?.toFloat() ?: 0f, MIN_EDGE_DP * density)
        val bottom = maxOf(gestureInsets?.bottom?.toFloat() ?: 0f, MIN_EDGE_DP * density)
        return GestureMath.isInDeadZone(x, y, width.toFloat(), height.toFloat(), side, top, bottom)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(singleTapRunnable)
        removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val MIN_EDGE_DP = 20f

        /** After a double tap, further taps this close count as more skips instead of starting over. */
        const val SERIES_WINDOW_MS = 600L
    }
}
