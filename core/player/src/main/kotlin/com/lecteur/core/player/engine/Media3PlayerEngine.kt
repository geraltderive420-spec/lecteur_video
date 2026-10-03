package com.lecteur.core.player.engine

import android.content.Context
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize as Media3VideoSize
import androidx.media3.common.util.Util
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.lecteur.core.model.HdrType
import com.lecteur.core.player.display.VideoSize
import com.lecteur.core.player.gesture.VolumeMath
import com.lecteur.core.player.settings.DecoderMode
import com.lecteur.core.player.settings.EqualizerPreset
import com.lecteur.core.player.settings.PassthroughMode
import com.lecteur.core.player.tracks.AudioOption
import com.lecteur.core.player.tracks.SUBTITLE_OFF
import com.lecteur.core.player.tracks.SubtitleOption
import com.lecteur.core.player.tracks.TrackSelectionPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** Settings that can only be applied when the ExoPlayer instance is built. */
data class EngineConfig(
    val decoderMode: DecoderMode = DecoderMode.AUTO,
    val passthrough: PassthroughMode = PassthroughMode.AUTO,
    val tunneling: Boolean = false
)

/**
 * [PlayerEngine] on top of Media3 ExoPlayer with the FFmpeg extension.
 * Must be created and used on the main thread.
 */
@UnstableApi
class Media3PlayerEngine(
    context: Context,
    val config: EngineConfig = EngineConfig()
) : PlayerEngine {

    private val audioDelay = AudioDelayProcessor()
    private val subtitleDelayUs = AtomicLong(0)

    private val trackSelector = DefaultTrackSelector(context).apply {
        parameters = buildUponParameters()
            .setTunnelingEnabled(config.tunneling)
            // Subtitles stay off until the language policy has looked at the available tracks
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    /** The ExoPlayer instance, exposed for the video surface (PlayerView) and the MediaSession. */
    val player: ExoPlayer = ExoPlayer.Builder(
        context,
        LecteurRenderersFactory(context, config.decoderMode, config.passthrough, audioDelay, subtitleDelayUs)
    )
        .setLooper(Looper.getMainLooper())
        .setTrackSelector(trackSelector)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(Progress())
    override val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 8)
    override val events: Flow<PlayerEvent> = _events

    private var request: PlaybackRequest? = null
    private var initialSelectionPending = false
    private var pendingExternalSubtitleId: String? = null

    // Technical info gathered from analytics callbacks
    private var videoDecoderName: String? = null
    private var audioDecoderName: String? = null
    private var droppedFrames = 0L
    private var audioPassthrough = false

    // Audio effects bound to the player's audio session
    private var effectsSessionId = C.AUDIO_SESSION_ID_UNSET
    private var loudness: LoudnessEnhancer? = null
    private var dynamics: DynamicsProcessing? = null
    private var equalizer: Equalizer? = null
    private var boostMillibels = 0
    private var nightMode = false
    private var equalizerPreset = EqualizerPreset.FLAT

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_TRACKS_CHANGED)) onTracksReady()
            publish()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) _events.tryEmit(PlayerEvent.Ended)
        }

        override fun onPlayerError(error: PlaybackException) {
            val mapped = PlaybackErrorMapper.map(error.errorCode, error.message)
            Log.w(TAG, "Playback error ${error.errorCodeName}", error)
            _events.tryEmit(PlayerEvent.Failed(mapped))
            _state.update { it.copy(status = PlaybackStatus.ERROR, error = mapped) }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) = rebuildEffects(audioSessionId)
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            videoDecoderName = decoderName
            PlaybackDiagnostics.line("Video decoder: $decoderName")
        }

        // Diagnostics for "black picture with sound": which format reached which decoder, and whether a frame ever came out
        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: androidx.media3.common.Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?
        ) {
            PlaybackDiagnostics.line("Video format: mime=${format.sampleMimeType} codecs=${format.codecs} ${format.width}x${format.height} color=${format.colorInfo}")
        }

        override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: androidx.media3.common.Tracks) {
            tracks.groups.forEach { group ->
                for (i in 0 until group.length) {
                    val f = group.getTrackFormat(i)
                    PlaybackDiagnostics.line(
                        "Track type=${group.type} mime=${f.sampleMimeType} codecs=${f.codecs} ${f.width}x${f.height} " +
                            "support=${group.getTrackSupport(i)} selected=${group.isTrackSelected(i)} color=${f.colorInfo}"
                    )
                }
            }
        }

        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
            PlaybackDiagnostics.line("First video frame rendered")
        }

        override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime, videoCodecError: Exception) {
            PlaybackDiagnostics.line("Video codec error: $videoCodecError")
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            audioDecoderName = decoderName
        }

        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            this@Media3PlayerEngine.droppedFrames += droppedFrames
        }

        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
            audioPassthrough = !Util.isEncodingLinearPcm(audioTrackConfig.encoding)
        }

        override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: Media3VideoSize) = publish()
    }

    init {
        PlaybackDiagnostics.init(context)
        player.addListener(playerListener)
        player.addAnalyticsListener(analyticsListener)
        scope.launch {
            while (isActive) {
                publishProgress()
                delay(if (player.isPlaying) PROGRESS_TICK_PLAYING_MS else PROGRESS_TICK_IDLE_MS)
            }
        }
    }

    // region PlayerEngine

    override fun open(request: PlaybackRequest) {
        this.request = request
        initialSelectionPending = true
        pendingExternalSubtitleId = null
        videoDecoderName = null
        audioDecoderName = null
        droppedFrames = 0
        audioPassthrough = false
        setAudioDelayMs(request.audioDelayMs)
        setSubtitleDelayMs(request.subtitleDelayMs)
        setSpeed(1f)
        // New media: subtitles off and automatic track choice again
        updateParameters { it.clearOverrides().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true) }

        player.setMediaItem(buildMediaItem(request), request.startPositionMs.coerceAtLeast(0))
        player.prepare()
        player.playWhenReady = request.playWhenReady
        _state.update { it.copy(status = PlaybackStatus.BUFFERING, error = null) }
    }

    override fun play() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    override fun pause() = player.pause()

    override fun stop() {
        player.stop()
        player.clearMediaItems()
        request = null
    }

    override fun seekTo(positionMs: Long) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        player.seekTo(positionMs.coerceIn(0, duration))
        publishProgress()
    }

    override fun seekBy(deltaMs: Long) = seekTo(player.currentPosition + deltaMs)

    override fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(PlayerEngine.MIN_SPEED, PlayerEngine.MAX_SPEED)
        // Pitch stays at 1.0: speech is not chipmunked
        player.playbackParameters = PlaybackParameters(clamped, 1f)
    }

    override fun selectAudio(index: Int) {
        val group = audioGroups().getOrNull(index) ?: return
        updateParameters { it.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0)) }
    }

    override fun selectSubtitle(index: Int) {
        if (index == SUBTITLE_OFF) {
            updateParameters { it.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true) }
            return
        }
        val group = subtitleGroups().getOrNull(index) ?: return
        updateParameters {
            it.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
        }
    }

    override fun addExternalSubtitle(source: ExternalSubtitleSource) {
        val current = request ?: return
        val updated = current.copy(externalSubtitles = current.externalSubtitles + source)
        request = updated
        initialSelectionPending = false
        pendingExternalSubtitleId = externalId(updated.externalSubtitles.lastIndex)
        val position = player.currentPosition
        val playWhenReady = player.playWhenReady
        player.setMediaItem(buildMediaItem(updated), position)
        player.prepare()
        player.playWhenReady = playWhenReady
    }

    override fun setAudioDelayMs(delayMs: Long) {
        audioDelay.setDelayMs(delayMs)
        _state.update { it.copy(audioDelayMs = delayMs.coerceIn(-AudioDelayProcessor.MAX_DELAY_MS, AudioDelayProcessor.MAX_DELAY_MS)) }
    }

    override fun setSubtitleDelayMs(delayMs: Long) {
        val clamped = delayMs.coerceIn(-MAX_SUBTITLE_DELAY_MS, MAX_SUBTITLE_DELAY_MS)
        subtitleDelayUs.set(clamped * 1000L)
        _state.update { it.copy(subtitleDelayMs = clamped) }
    }

    override fun setVolumeBoost(fraction: Float) {
        boostMillibels = (fraction.coerceIn(0f, 1f) * VolumeMath.MAX_BOOST_MILLIBELS).toInt()
        applyEffects()
    }

    override fun setNightMode(enabled: Boolean) {
        nightMode = enabled
        applyEffects()
    }

    override fun setEqualizer(preset: EqualizerPreset) {
        equalizerPreset = preset
        applyEffects()
    }

    override fun setTunneling(enabled: Boolean) {
        trackSelector.parameters = trackSelector.buildUponParameters().setTunnelingEnabled(enabled).build()
    }

    override fun setVideoEnabled(enabled: Boolean) {
        updateParameters { it.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled) }
    }

    override fun release() {
        scope.cancel()
        releaseEffects()
        player.removeListener(playerListener)
        player.removeAnalyticsListener(analyticsListener)
        player.release()
    }

    // endregion

    private fun buildMediaItem(request: PlaybackRequest): MediaItem {
        val subtitles = request.externalSubtitles.mapIndexed { i, sub ->
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(sub.uri))
                .setMimeType(sub.mimeType)
                .setLanguage(sub.language)
                .setLabel(sub.label)
                .setId(externalId(i))
                .setSelectionFlags(if (sub.isForced) C.SELECTION_FLAG_FORCED else 0)
                .build()
        }
        return MediaItem.Builder()
            .setUri(request.uri)
            .setMediaId(request.mediaFileId.toString())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(request.title).build())
            .setSubtitleConfigurations(subtitles)
            .build()
    }

    private fun externalId(index: Int) = "$EXTERNAL_ID_PREFIX$index"

    private inline fun updateParameters(change: (androidx.media3.common.TrackSelectionParameters.Builder) -> Unit) {
        val builder = player.trackSelectionParameters.buildUpon()
        change(builder)
        player.trackSelectionParameters = builder.build()
    }

    private fun audioGroups(): List<Tracks.Group> = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }

    private fun subtitleGroups(): List<Tracks.Group> = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }

    /** First time tracks are known for the media (or a re-prepare with a new external subtitle). */
    private fun onTracksReady() {
        if (player.currentTracks.isEmpty) return

        pendingExternalSubtitleId?.let { id ->
            val index = subtitleGroups().indexOfFirst { it.getTrackFormat(0).id?.contains(id) == true }
            if (index >= 0) {
                pendingExternalSubtitleId = null
                selectSubtitle(index)
            }
            return
        }

        if (!initialSelectionPending) return
        initialSelectionPending = false
        val req = request ?: return

        val audio = buildAudioOptions()
        val subtitles = buildSubtitleOptions()
        val choice = TrackSelectionPolicy.choose(audio, subtitles, req.languagePreferences)

        (req.savedAudioIndex ?: choice.audioIndex)?.let { wanted ->
            if (audio.firstOrNull { it.isSelected }?.index != wanted) selectAudio(wanted)
        }
        selectSubtitle(req.savedSubtitleIndex ?: choice.subtitleIndex ?: SUBTITLE_OFF)
    }

    private fun buildAudioOptions(): List<AudioOption> = audioGroups().mapIndexed { index, group ->
        val format = group.getTrackFormat(0)
        AudioOption(
            index = index,
            language = format.language,
            codec = CodecNames.audio(format.sampleMimeType, format.codecs),
            channels = format.channelCount.takeIf { it > 0 } ?: 0,
            label = format.label,
            isDefault = format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
            isSelected = group.isTrackSelected(0),
            isSupported = group.isTrackSupported(0)
        )
    }

    private fun buildSubtitleOptions(): List<SubtitleOption> = subtitleGroups().mapIndexed { index, group ->
        val format = group.getTrackFormat(0)
        SubtitleOption(
            index = index,
            language = format.language,
            codec = CodecNames.subtitle(subtitleMimeType(format)),
            label = format.label,
            isForced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
            isDefault = format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
            isSelected = group.isTrackSelected(0),
            isExternal = format.id?.contains(EXTERNAL_ID_PREFIX) == true,
            isSupported = group.isTrackSupported(0)
        )
    }

    private fun publish() {
        val videoSize = player.videoSize
        val size = VideoSize(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
        _state.update {
            it.copy(
                status = when (player.playbackState) {
                    Player.STATE_IDLE -> if (it.status == PlaybackStatus.ERROR) PlaybackStatus.ERROR else PlaybackStatus.IDLE
                    Player.STATE_BUFFERING -> PlaybackStatus.BUFFERING
                    Player.STATE_READY -> PlaybackStatus.READY
                    else -> PlaybackStatus.ENDED
                },
                isPlaying = player.isPlaying,
                playWhenReady = player.playWhenReady,
                speed = player.playbackParameters.speed,
                videoSize = size,
                audioOptions = buildAudioOptions(),
                subtitleOptions = buildSubtitleOptions(),
                technical = buildTechnicalInfo(size)
            )
        }
    }

    private fun publishProgress() {
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        val next = Progress(player.currentPosition.coerceAtLeast(0), player.bufferedPosition.coerceAtLeast(0), duration)
        if (next != _progress.value) _progress.value = next
    }

    private fun buildTechnicalInfo(size: VideoSize): TechnicalInfo {
        val video: Format? = player.videoFormat
        val audio: Format? = player.audioFormat
        val decoder = videoDecoderName
        return TechnicalInfo(
            videoCodec = CodecNames.video(video?.sampleMimeType),
            videoDecoder = decoder,
            isHardwareVideoDecoder = decoder?.let { !isSoftwareDecoder(it) },
            videoBitrate = video?.bitrate?.takeIf { it > 0 },
            frameRate = video?.frameRate?.takeIf { it > 0f },
            videoSize = size,
            sourceHdr = hdrTypeOf(video),
            isDolbyVisionDecoder = decoder?.let(::isDolbyVisionDecoder) == true,
            audioCodec = CodecNames.audio(audio?.sampleMimeType, audio?.codecs),
            audioDecoder = audioDecoderName,
            audioChannels = audio?.channelCount?.takeIf { it > 0 },
            audioSampleRate = audio?.sampleRate?.takeIf { it > 0 },
            isAudioPassthrough = audioPassthrough,
            droppedFrames = droppedFrames
        )
    }

    // region Audio effects

    private fun rebuildEffects(sessionId: Int) {
        releaseEffects()
        if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId == 0) return
        effectsSessionId = sessionId
        applyEffects()
    }

    private fun releaseEffects() {
        runCatching { loudness?.release() }
        runCatching { dynamics?.release() }
        runCatching { equalizer?.release() }
        loudness = null
        dynamics = null
        equalizer = null
    }

    private fun applyEffects() {
        if (effectsSessionId == C.AUDIO_SESSION_ID_UNSET) return
        runCatching {
            if (loudness == null && boostMillibels > 0) loudness = LoudnessEnhancer(effectsSessionId)
            loudness?.apply {
                setTargetGain(boostMillibels)
                enabled = boostMillibels > 0
            }
        }.onFailure { Log.w(TAG, "Volume boost unavailable", it) }

        runCatching {
            if (equalizer == null && equalizerPreset != EqualizerPreset.FLAT) {
                equalizer = Equalizer(0, effectsSessionId)
            }
            equalizer?.let { eq ->
                val range = eq.bandLevelRange
                for (band in 0 until eq.numberOfBands) {
                    val centerHz = eq.getCenterFreq(band.toShort()) / 1000
                    val gain = EqualizerPresets.gainMillibels(equalizerPreset, centerHz)
                    eq.setBandLevel(band.toShort(), gain.coerceIn(range[0].toInt(), range[1].toInt()).toShort())
                }
                eq.enabled = equalizerPreset != EqualizerPreset.FLAT
            }
        }.onFailure { Log.w(TAG, "Equalizer unavailable", it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                if (dynamics == null && nightMode) dynamics = buildNightModeEffect(effectsSessionId)
                dynamics?.enabled = nightMode
            }.onFailure { Log.w(TAG, "Night mode unavailable", it) }
        }
    }

    // endregion

    companion object {
        private const val TAG = "Media3PlayerEngine"
        private const val EXTERNAL_ID_PREFIX = "ext:"
        private const val PROGRESS_TICK_PLAYING_MS = 250L
        private const val PROGRESS_TICK_IDLE_MS = 1_000L
        const val MAX_SUBTITLE_DELAY_MS = 30_000L

        /** Text subtitles are converted to cues at extraction time; the original format is then kept in `codecs`. */
        internal fun subtitleMimeType(format: Format): String? =
            if (format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) format.codecs else format.sampleMimeType

        internal fun isSoftwareDecoder(name: String): Boolean {
            val n = name.lowercase()
            return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains("ffmpeg") ||
                n.contains(".sw.") || n.endsWith(".sw") || n.contains("software")
        }

        /** Dolby Vision decoders are named after the codec; the HEVC fallback decoder is not. */
        internal fun isDolbyVisionDecoder(name: String): Boolean {
            val n = name.lowercase()
            return n.contains("dolby") || n.contains(".dv.") || n.contains("dvhe") || n.contains("dvav") ||
                n.contains("dolbyvision")
        }

        internal fun hdrTypeOf(format: Format?): HdrType = when {
            format == null -> HdrType.NONE
            format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> HdrType.DOLBY_VISION
            format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> HdrType.HDR10
            format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> HdrType.HLG
            else -> HdrType.NONE
        }

        @android.annotation.SuppressLint("NewApi")
        private fun buildNightModeEffect(sessionId: Int): DynamicsProcessing {
            // Compress the dynamic range: loud scenes come down, quiet dialogue comes up
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION,
                /* channelCount = */ 2,
                /* preEqInUse = */ false, 0,
                /* mbcInUse = */ false, 0,
                /* postEqInUse = */ false, 0,
                /* limiterInUse = */ true
            ).build()
            val effect = DynamicsProcessing(0, sessionId, config)
            val limiter = DynamicsProcessing.Limiter(
                /* inUse = */ true, /* enabled = */ true, /* linkGroup = */ 0,
                /* attackTimeMs = */ 5f, /* releaseTimeMs = */ 200f,
                /* ratio = */ 4f, /* thresholdDb = */ -20f, /* postGainDb = */ 8f
            )
            for (channel in 0 until 2) effect.setLimiterByChannelIndex(channel, limiter)
            return effect
        }
    }
}
