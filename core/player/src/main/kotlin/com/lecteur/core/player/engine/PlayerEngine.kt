package com.lecteur.core.player.engine

import com.lecteur.core.model.HdrType
import com.lecteur.core.player.display.VideoSize
import com.lecteur.core.player.settings.EqualizerPreset
import com.lecteur.core.player.tracks.AudioOption
import com.lecteur.core.player.tracks.LanguagePreferences
import com.lecteur.core.player.tracks.SubtitleOption
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** A subtitle file to attach to the media being opened (external .srt/.ass/.vtt/.sup). */
data class ExternalSubtitleSource(
    val uri: String,
    val mimeType: String,
    val language: String?,
    val label: String?,
    val isForced: Boolean = false
)

data class PlaybackRequest(
    val uri: String,
    val title: String?,
    /** Row of media_files this playback belongs to; progress is saved against it. */
    val mediaFileId: Long,
    val startPositionMs: Long = 0,
    val playWhenReady: Boolean = true,
    val externalSubtitles: List<ExternalSubtitleSource> = emptyList(),
    val languagePreferences: LanguagePreferences = LanguagePreferences(),
    /** Previously chosen tracks (see [com.lecteur.core.model.WatchState]); null lets the language policy decide. */
    val savedAudioIndex: Int? = null,
    val savedSubtitleIndex: Int? = null,
    val audioDelayMs: Long = 0,
    val subtitleDelayMs: Long = 0
)

enum class PlaybackStatus { IDLE, BUFFERING, READY, ENDED, ERROR }

enum class PlaybackErrorKind { FILE_MISSING, NO_PERMISSION, UNSUPPORTED_FORMAT, CORRUPT_FILE, NETWORK, UNKNOWN }

/** An error with a message the user can understand and a way out. */
data class PlaybackError(
    val kind: PlaybackErrorKind,
    val message: String,
    val suggestion: String?,
    val technicalDetail: String? = null
)

/** What is really playing; feeds the technical information panel. */
data class TechnicalInfo(
    val videoCodec: String? = null,
    val videoDecoder: String? = null,
    /** True for MediaCodec hardware decoders, false for software (including the FFmpeg extension), null when unknown. */
    val isHardwareVideoDecoder: Boolean? = null,
    val videoBitrate: Int? = null,
    val frameRate: Float? = null,
    val videoSize: VideoSize = VideoSize(0, 0),
    val sourceHdr: HdrType = HdrType.NONE,
    val isDolbyVisionDecoder: Boolean = false,
    val audioCodec: String? = null,
    val audioDecoder: String? = null,
    val audioChannels: Int? = null,
    val audioSampleRate: Int? = null,
    /** Bitstream sent as is to the output (AC3/DTS... to an amplifier): audio delay cannot apply. */
    val isAudioPassthrough: Boolean = false,
    val droppedFrames: Long = 0
)

/** Structural state: changes on events, not on every tick. Position lives in [Progress]. */
data class PlayerState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val speed: Float = 1f,
    val videoSize: VideoSize = VideoSize(0, 0),
    val audioOptions: List<AudioOption> = emptyList(),
    val subtitleOptions: List<SubtitleOption> = emptyList(),
    val audioDelayMs: Long = 0,
    val subtitleDelayMs: Long = 0,
    val error: PlaybackError? = null,
    val technical: TechnicalInfo = TechnicalInfo()
) {
    val selectedAudioIndex: Int? get() = audioOptions.firstOrNull { it.isSelected }?.index
    val selectedSubtitleIndex: Int? get() = subtitleOptions.firstOrNull { it.isSelected }?.index
}

data class Progress(val positionMs: Long = 0, val bufferedMs: Long = 0, val durationMs: Long = 0)

/**
 * Playback engine abstraction. [Media3PlayerEngine] is the only implementation for now; libmpv can be added
 * behind this interface later for files Media3 cannot play.
 */
interface PlayerEngine {
    val state: StateFlow<PlayerState>
    val progress: StateFlow<Progress>

    /** One-shot notifications: the media ended, or playback failed. */
    val events: Flow<PlayerEvent>

    fun open(request: PlaybackRequest)
    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionMs: Long)
    fun seekBy(deltaMs: Long)

    /** 0.25x to 4x, pitch is corrected. */
    fun setSpeed(speed: Float)

    fun selectAudio(index: Int)

    /** [com.lecteur.core.player.tracks.SUBTITLE_OFF] switches subtitles off. */
    fun selectSubtitle(index: Int)

    /** Attaches an external subtitle to the current media and selects it (briefly re-prepares the media). */
    fun addExternalSubtitle(source: ExternalSubtitleSource)

    fun setAudioDelayMs(delayMs: Long)
    fun setSubtitleDelayMs(delayMs: Long)

    /** 0 = no amplification, 1 = maximum software boost. */
    fun setVolumeBoost(fraction: Float)

    fun setNightMode(enabled: Boolean)
    fun setEqualizer(preset: EqualizerPreset)
    fun setTunneling(enabled: Boolean)

    /** Audio-only playback (video track disabled), used when the screen goes away. */
    fun setVideoEnabled(enabled: Boolean)

    fun release()

    companion object {
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4f
        const val DELAY_STEP_MS = 50L
    }
}

sealed interface PlayerEvent {
    data object Ended : PlayerEvent
    data class Failed(val error: PlaybackError) : PlayerEvent
}
