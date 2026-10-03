package com.lecteur.feature.player.ui

import android.graphics.Color as AndroidColor
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.lecteur.core.player.display.DisplayMode
import com.lecteur.core.player.display.VideoLayoutMath
import com.lecteur.core.player.display.VideoSize
import com.lecteur.core.player.display.ZoomState
import com.lecteur.core.player.engine.Media3PlayerEngine
import com.lecteur.core.player.settings.SubtitleEdge
import com.lecteur.core.player.settings.SubtitleStyle
import kotlin.math.roundToInt

/**
 * Video picture and subtitles. The picture is a SurfaceView (best for HDR and hardware compositing) sized by
 * [VideoLayoutMath] and zoomed by resizing, which works with SurfaceView where view transforms may not.
 * Subtitles are drawn full screen above it so they stay readable whatever the display mode or zoom.
 */
@UnstableApi
@Composable
fun VideoLayer(
    engine: Media3PlayerEngine,
    videoSize: VideoSize,
    mode: DisplayMode,
    zoom: ZoomState,
    subtitleStyle: SubtitleStyle,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val surface = remember { SurfaceView(context) }
    val subtitles = remember { SubtitleView(context) }

    DisposableEffect(engine) {
        engine.player.setVideoSurfaceView(surface)
        onDispose { engine.player.clearVideoSurfaceView(surface) }
    }

    DisposableEffect(engine) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                subtitles.setCues(cueGroup.cues)
            }
        }
        engine.player.addListener(listener)
        subtitles.setCues(engine.player.currentCues.cues)
        onDispose { engine.player.removeListener(listener) }
    }

    LaunchedEffect(subtitleStyle) {
        subtitles.setStyle(
            CaptionStyleCompat(
                subtitleStyle.textColorArgb,
                subtitleStyle.backgroundArgb,
                AndroidColor.TRANSPARENT,
                when (subtitleStyle.edge) {
                    SubtitleEdge.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
                    SubtitleEdge.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
                    SubtitleEdge.DROP_SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
                },
                subtitleStyle.edgeColorArgb,
                null
            )
        )
        subtitles.setFractionalTextSize(subtitleStyle.sizeFraction)
        subtitles.setBottomPaddingFraction(subtitleStyle.bottomPaddingFraction)
        // ASS colours and positions are kept, the font size is the user's
        subtitles.setApplyEmbeddedStyles(true)
        subtitles.setApplyEmbeddedFontSizes(false)
    }

    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black).clipToBounds()) {
        val density = LocalDensity.current
        val containerWidth = constraints.maxWidth.toFloat()
        val containerHeight = constraints.maxHeight.toFloat()
        val layout = remember(mode, videoSize, containerWidth, containerHeight) {
            VideoLayoutMath.compute(mode, videoSize, containerWidth, containerHeight)
        }

        Box(
            Modifier
                .align(Alignment.Center)
                .requiredSize(
                    with(density) { (layout.width * zoom.scale).toDp() },
                    with(density) { (layout.height * zoom.scale).toDp() }
                )
                .offset { IntOffset(zoom.offsetX.roundToInt(), zoom.offsetY.roundToInt()) }
        ) {
            AndroidView(factory = { surface }, modifier = Modifier.fillMaxSize())
        }

        // Subtitle size and position are fractions of the visible picture, not of the whole screen
        // (otherwise a portrait phone gets huge text). A cropped or zoomed picture is limited to the screen.
        Box(
            Modifier
                .align(Alignment.Center)
                .requiredSize(
                    with(density) { minOf(layout.width, containerWidth).toDp() },
                    with(density) { minOf(layout.height, containerHeight).toDp() }
                )
        ) {
            AndroidView(factory = { subtitles }, modifier = Modifier.fillMaxSize())
        }
    }
}
