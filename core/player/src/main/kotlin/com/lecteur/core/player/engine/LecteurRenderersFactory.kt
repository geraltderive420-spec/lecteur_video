package com.lecteur.core.player.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.common.Format
import androidx.media3.exoplayer.source.SampleStream
import com.lecteur.core.player.settings.DecoderMode
import com.lecteur.core.player.settings.PassthroughMode
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * Renderers for the player:
 * - FFmpeg audio decoders (DTS, DTS-HD, TrueHD, AC3, E-AC3, FLAC...) behind the device decoders,
 * - optional software-first or hardware-only video decoding,
 * - audio delay ([audioDelay]) in the PCM chain, optional passthrough disabling,
 * - subtitle delay by shifting the text renderer clock.
 */
@UnstableApi
class LecteurRenderersFactory(
    context: Context,
    private val decoderMode: DecoderMode,
    private val passthrough: PassthroughMode,
    private val audioDelay: AudioDelayProcessor,
    private val subtitleDelayUs: AtomicLong
) : NextRenderersFactory(context) {

    init {
        setExtensionRendererMode(
            if (decoderMode == DecoderMode.SOFTWARE_PREFERRED) EXTENSION_RENDERER_MODE_PREFER else EXTENSION_RENDERER_MODE_ON
        )
        setEnableDecoderFallback(true)
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        // Hardware-only: keep the FFmpeg extension for audio, but not for video
        val mode = if (decoderMode == DecoderMode.HARDWARE_ONLY) EXTENSION_RENDERER_MODE_OFF else extensionRendererMode
        val before = out.size
        super.buildVideoRenderers(
            context, mode, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener,
            allowedVideoJoiningTimeMs, out
        )
        // Swap the stock MediaCodec renderer for one that keeps Dolby Vision files off a decoder that renders them black
        for (i in before until out.size) {
            if (out[i].javaClass == MediaCodecVideoRenderer::class.java) {
                out[i] = DolbyVisionAwareRenderer.create(
                    context, mediaCodecSelector, allowedVideoJoiningTimeMs, enableDecoderFallback, eventHandler, eventListener
                )
            }
        }
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        val builder = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(audioDelay))
        if (passthrough == PassthroughMode.OFF) {
            builder.setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
        }
        return builder.build()
    }

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>
    ) {
        val before = out.size
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
        for (i in before until out.size) {
            if (out[i] is TextRenderer) out[i] = DelayedTextRenderer(out[i], subtitleDelayUs)
        }
    }
}

/** Shows subtitles [delayUs] later (positive) or earlier (negative) than the media clock says. */
@UnstableApi
internal class DelayedTextRenderer(
    delegate: Renderer,
    private val delayUs: AtomicLong
) : ForwardingRenderer(delegate) {

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) =
        super.render(positionUs - delayUs.get(), elapsedRealtimeUs)

    override fun resetPosition(positionUs: Long) = super.resetPosition(positionUs - delayUs.get())

    override fun getDurationToProgressUs(positionUs: Long, elapsedRealtimeUs: Long): Long =
        super.getDurationToProgressUs(positionUs - delayUs.get(), elapsedRealtimeUs)

    override fun enable(
        configuration: RendererConfiguration,
        formats: Array<Format>,
        stream: SampleStream,
        positionUs: Long,
        joining: Boolean,
        mayRenderStartOfStream: Boolean,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId
    ) = super.enable(
        configuration, formats, stream, positionUs - delayUs.get(), joining, mayRenderStartOfStream,
        startPositionUs - delayUs.get(), offsetUs, mediaPeriodId
    )
}
