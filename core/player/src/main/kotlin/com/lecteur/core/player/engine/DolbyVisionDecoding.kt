package com.lecteur.core.player.engine

import android.content.Context
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.mediacodec.DefaultMediaCodecAdapterFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/** Which Dolby Vision streams can be played as their plain base layer. Pure, so it can be tested without a device. */
object DolbyVisionProfiles {

    /** `dvhe.07.06` / `dvh1.08.07` / `dav1.10.09` -> the profile number (7, 8, 10); null for anything else. */
    fun profileOf(codecs: String?): Int? {
        val dv = codecs?.split(',')?.map { it.trim() }?.firstOrNull { it.startsWith("dvhe.") || it.startsWith("dvh1.") || it.startsWith("dvav.") || it.startsWith("dva1.") || it.startsWith("dav1.") }
            ?: return null
        return dv.split('.').getOrNull(1)?.toIntOrNull()
    }

    /**
     * Profiles 5 (IPT-PQ colours, unreadable as plain HEVC) and 10 (AV1) really need a Dolby Vision decoder.
     * The others carry an HDR10/HLG/SDR-compatible base layer that a plain decoder plays correctly: profile 7
     * (UHD Blu-ray remux, dual layer) and 4 in particular come out as a black picture, with sound only, on several
     * device Dolby Vision decoders, because the demuxer hands them the base layer without its enhancement layer.
     */
    fun usesBaseLayer(profile: Int?): Boolean = profile != null && profile !in NEEDS_DOLBY_DECODER

    /** MIME type of the base layer: HEVC for dvhe/dvh1, AVC for dvav/dva1, AV1 for dav1. */
    fun baseLayerMime(codecs: String?): String {
        val prefix = codecs?.split(',')?.map { it.trim() }?.firstOrNull { it.startsWith("dv") || it.startsWith("dav1") }.orEmpty()
        return when {
            prefix.startsWith("dvav") || prefix.startsWith("dva1") -> MimeTypes.VIDEO_H264
            prefix.startsWith("dav1") -> MimeTypes.VIDEO_AV1
            else -> MimeTypes.VIDEO_H265
        }
    }

    /**
     * Whether to hand a Dolby Vision stream to a plain decoder instead of a Dolby Vision one.
     * @param deviceHasDolbyDecoder false on devices without a licensed Dolby Vision decoder (the track would be
     * reported unsupported and never played: black picture, sound only). Profile 5 then shows with wrong colours,
     * which still beats no picture.
     */
    fun shouldPlayAsBaseLayer(codecs: String?, deviceHasDolbyDecoder: Boolean): Boolean =
        !deviceHasDolbyDecoder || usesBaseLayer(profileOf(codecs))

    private val NEEDS_DOLBY_DECODER = setOf(5, 10)
}

/**
 * Video renderer that plays Dolby Vision streams through the ordinary HEVC/AVC/AV1 decoders when their base layer is
 * playable, or when the device has no Dolby Vision decoder at all. Nothing changes for other formats, nor for
 * profile 5 on a device whose Dolby Vision decoder works.
 */
@UnstableApi
internal class DolbyVisionAwareRenderer(builder: Builder) : MediaCodecVideoRenderer(builder) {

    private val deviceHasDolbyDecoder: Boolean by lazy {
        runCatching { MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION, false, false).isNotEmpty() }.getOrDefault(false)
    }

    private fun asBaseLayer(format: Format): Format {
        if (format.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) return format
        if (!DolbyVisionProfiles.shouldPlayAsBaseLayer(format.codecs, deviceHasDolbyDecoder)) return format
        return format.buildUpon()
            .setSampleMimeType(DolbyVisionProfiles.baseLayerMime(format.codecs))
            .setCodecs(null)
            .build()
    }

    // Capability check: without this the track is declared unsupported and never selected
    override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int =
        super.supportsFormat(mediaCodecSelector, asBaseLayer(format))

    // The codec is then chosen, sized and configured for the base layer's real type
    @Throws(ExoPlaybackException::class)
    override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
        formatHolder.format?.let { formatHolder.format = asBaseLayer(it) }
        return super.onInputFormatChanged(formatHolder)
    }

    companion object {
        /** Same configuration as the stock renderer of DefaultRenderersFactory. */
        fun create(
            context: Context,
            selector: MediaCodecSelector,
            allowedJoiningTimeMs: Long,
            enableDecoderFallback: Boolean,
            eventHandler: Handler,
            eventListener: VideoRendererEventListener
        ): DolbyVisionAwareRenderer = DolbyVisionAwareRenderer(
            Builder(context)
                .setCodecAdapterFactory(DefaultMediaCodecAdapterFactory(context))
                .setMediaCodecSelector(selector)
                .setAllowedJoiningTimeMs(allowedJoiningTimeMs)
                .setEnableDecoderFallback(enableDecoderFallback)
                .setEventHandler(eventHandler)
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(MAX_DROPPED_FRAMES_TO_NOTIFY)
        )

        private const val MAX_DROPPED_FRAMES_TO_NOTIFY = 50
    }
}
