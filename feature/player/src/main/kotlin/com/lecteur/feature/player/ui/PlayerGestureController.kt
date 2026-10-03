package com.lecteur.feature.player.ui

import androidx.media3.common.util.UnstableApi
import com.lecteur.core.player.display.ZoomMath
import com.lecteur.core.player.display.ZoomState
import com.lecteur.core.player.engine.PlayerEngine
import com.lecteur.core.player.gesture.DoubleTapSkipAccumulator
import com.lecteur.core.player.gesture.GestureMath
import com.lecteur.core.player.gesture.HorizontalZone
import com.lecteur.core.player.gesture.SeekPreview
import com.lecteur.core.player.gesture.VolumeMath
import com.lecteur.core.player.settings.PlayerSettings
import com.lecteur.feature.player.PlayerViewModel
import com.lecteur.feature.player.gesture.PlayerGestureListener

/** What the activity provides to the gestures: window brightness and the system volume. */
class PlayerHostActions(
    /** Current window brightness, 0..1. */
    val brightness: () -> Float,
    val setBrightness: (Float) -> Unit,
    /** Current media stream volume as a 0..1 fraction. */
    val systemVolume: () -> Float,
    val setSystemVolume: (Float) -> Unit,
    val enterPictureInPicture: () -> Unit,
    val canEnterPictureInPicture: Boolean,
    val pickSubtitleFile: () -> Unit,
    val toggleRotationLock: () -> Unit,
    val close: () -> Unit
)

/**
 * Applies the gestures to the player. Reads the latest settings, playback state and view size through lambdas
 * so one instance can live as long as the screen.
 */
@UnstableApi
class PlayerGestureController(
    private val viewModel: PlayerViewModel,
    private val host: PlayerHostActions,
    private val hud: HudState,
    private val settings: () -> PlayerSettings,
    private val engine: () -> PlayerEngine?,
    private val viewSize: () -> Pair<Float, Float>,
    private val zoom: () -> ZoomState,
    private val setZoom: (ZoomState) -> Unit,
    private val onSingleTapped: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis
) : PlayerGestureListener {

    private val skipAccumulator = DoubleTapSkipAccumulator()

    private var swipeStartPositionMs = 0L
    private var lastSeekPreview: SeekPreview? = null
    private var swipeStartLevel = 0f
    private var volumeLevel = -1f
    private var speedBeforeLongPress = 1f

    override fun onSingleTap() = onSingleTapped()

    override fun onDoubleTap(zone: HorizontalZone) {
        val stepMs = settings().seekStepSeconds * 1000L
        when (zone) {
            HorizontalZone.CENTER -> {
                skipAccumulator.reset()
                viewModel.togglePlayPause()
            }
            else -> {
                val total = skipAccumulator.onDoubleTap(zone, now(), stepMs)
                viewModel.seekBy(if (zone == HorizontalZone.RIGHT) stepMs else -stepMs)
                hud.showSkip(total)
            }
        }
    }

    override fun onLongPressStart() {
        val engine = engine() ?: return
        speedBeforeLongPress = engine.state.value.speed
        val boosted = settings().longPressSpeed
        viewModel.setSpeed(boosted)
        hud.holdSpeed(boosted)
    }

    override fun onLongPressEnd() {
        viewModel.setSpeed(speedBeforeLongPress)
        hud.holdSpeed(null)
    }

    override fun onHorizontalSwipeStart() {
        swipeStartPositionMs = engine()?.progress?.value?.positionMs ?: 0L
        lastSeekPreview = null
    }

    override fun onHorizontalSwipe(totalDxPx: Float) {
        val engine = engine() ?: return
        val (width, _) = viewSize()
        val preview = GestureMath.seekPreview(swipeStartPositionMs, totalDxPx, width, engine.progress.value.durationMs)
        lastSeekPreview = preview
        hud.showSeek(preview)
    }

    override fun onHorizontalSwipeEnd() {
        lastSeekPreview?.let { viewModel.seekTo(it.targetMs) }
        lastSeekPreview = null
        hud.hideSeek()
    }

    override fun onVerticalSwipeStart(leftHalf: Boolean) {
        swipeStartLevel = if (leftHalf) {
            host.brightness()
        } else {
            if (volumeLevel < 0f) volumeLevel = host.systemVolume()
            volumeLevel
        }
    }

    override fun onVerticalSwipe(totalDyPx: Float, leftHalf: Boolean) {
        val (_, height) = viewSize()
        if (leftHalf) {
            val level = GestureMath.levelAfterVerticalSwipe(swipeStartLevel, totalDyPx, height, MIN_BRIGHTNESS, 1f)
            host.setBrightness(level)
            hud.showBrightness(level)
        } else {
            val max = VolumeMath.maxLevel(settings().volumeBoostEnabled)
            val level = GestureMath.levelAfterVerticalSwipe(swipeStartLevel, totalDyPx, height, 0f, max)
            volumeLevel = level
            host.setSystemVolume(VolumeMath.systemFraction(level))
            viewModel.setVolumeBoost(VolumeMath.boostFraction(level))
            hud.showVolume(level)
        }
    }

    override fun onVerticalSwipeEnd() = Unit

    override fun onPinch(scaleFactor: Float) {
        val (w, h) = viewSize()
        val next = ZoomMath.apply(zoom(), scaleFactor, 0f, 0f, w, h)
        setZoom(next)
        hud.showZoom(next.scale)
    }

    override fun onPan(dxPx: Float, dyPx: Float) {
        val (w, h) = viewSize()
        setZoom(ZoomMath.apply(zoom(), 1f, dxPx, dyPx, w, h))
    }

    private companion object {
        /** Never fully black: the user could not find the screen again. */
        const val MIN_BRIGHTNESS = 0.02f
    }
}
