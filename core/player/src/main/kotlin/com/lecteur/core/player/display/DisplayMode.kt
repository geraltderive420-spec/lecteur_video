package com.lecteur.core.player.display

import kotlin.math.max
import kotlin.math.min

/** How the video picture is laid out inside the player surface. Persisted by [name] per media. */
enum class DisplayMode(val forcedRatio: Float?) {
    /** Whole picture visible, aspect ratio kept (letterbox). */
    FIT(null),

    /** Container fully covered, aspect ratio kept (picture is cropped). */
    FILL(null),

    /** Container fully covered, picture distorted. */
    STRETCH(null),

    /** One video pixel per screen pixel. */
    ORIGINAL(null),
    RATIO_16_9(16f / 9f),
    RATIO_4_3(4f / 3f),
    RATIO_21_9(21f / 9f);

    fun next(): DisplayMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromName(name: String?): DisplayMode = entries.firstOrNull { it.name == name } ?: FIT
    }
}

data class VideoSize(val width: Int, val height: Int, val pixelWidthHeightRatio: Float = 1f) {
    val isKnown: Boolean get() = width > 0 && height > 0

    /** Width / height as displayed (anamorphic pixels taken into account). */
    val displayAspect: Float
        get() = if (isKnown) width * pixelWidthHeightRatio / height else 0f
}

data class LayoutSize(val width: Float, val height: Float)

object VideoLayoutMath {

    /** Size the video view must have inside a [containerWidth] x [containerHeight] box (centered by the caller). */
    fun compute(mode: DisplayMode, video: VideoSize, containerWidth: Float, containerHeight: Float): LayoutSize {
        if (containerWidth <= 0f || containerHeight <= 0f) return LayoutSize(0f, 0f)
        if (!video.isKnown) return LayoutSize(containerWidth, containerHeight)

        return when (mode) {
            DisplayMode.STRETCH -> LayoutSize(containerWidth, containerHeight)
            DisplayMode.FIT -> fit(video.displayAspect, containerWidth, containerHeight)
            DisplayMode.FILL -> cover(video.displayAspect, containerWidth, containerHeight)
            DisplayMode.ORIGINAL -> LayoutSize(video.width * video.pixelWidthHeightRatio, video.height.toFloat())
            DisplayMode.RATIO_16_9, DisplayMode.RATIO_4_3, DisplayMode.RATIO_21_9 ->
                fit(mode.forcedRatio!!, containerWidth, containerHeight)
        }
    }

    private fun fit(aspect: Float, w: Float, h: Float): LayoutSize {
        val width = min(w, h * aspect)
        return LayoutSize(width, width / aspect)
    }

    private fun cover(aspect: Float, w: Float, h: Float): LayoutSize {
        val width = max(w, h * aspect)
        return LayoutSize(width, width / aspect)
    }
}

/** Pinch zoom state: [scale] >= 1, offsets in px relative to the centered layout. */
data class ZoomState(val scale: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f) {
    val isIdentity: Boolean get() = scale == 1f && offsetX == 0f && offsetY == 0f
}

object ZoomMath {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 4f

    /** Applies a pinch/pan gesture and keeps the picture covering the viewport (no empty border revealed by panning). */
    fun apply(
        current: ZoomState,
        scaleFactor: Float,
        panX: Float,
        panY: Float,
        viewWidth: Float,
        viewHeight: Float
    ): ZoomState {
        val scale = (current.scale * scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
        if (scale == MIN_SCALE) return ZoomState()
        val maxX = (viewWidth * (scale - 1f)) / 2f
        val maxY = (viewHeight * (scale - 1f)) / 2f
        return ZoomState(
            scale = scale,
            offsetX = (current.offsetX + panX).coerceIn(-maxX, maxX),
            offsetY = (current.offsetY + panY).coerceIn(-maxY, maxY)
        )
    }
}
