package com.lecteur.desktop.player

import com.lecteur.core.player.display.DisplayMode
import com.lecteur.core.player.tracks.AudioOption
import com.lecteur.core.player.tracks.LanguageCodes
import com.lecteur.core.player.tracks.LanguagePreferences
import com.lecteur.core.player.tracks.SUBTITLE_OFF
import com.lecteur.core.player.tracks.SubtitleOption
import com.lecteur.core.player.tracks.TrackSelectionPolicy
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import uk.co.caprica.vlcj.media.MediaRef
import uk.co.caprica.vlcj.media.MediaSlaveType
import uk.co.caprica.vlcj.media.TrackType
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import java.awt.Canvas
import java.awt.Color
import java.io.File

enum class EngineStatus { IDLE, OPENING, BUFFERING, READY, ENDED, ERROR }

data class ExternalSubtitle(val location: String, val language: String?, val label: String?, val isForced: Boolean = false)

/** What to open: a local file or an HTTP stream (from the phone), with where to start and the remembered choices. */
data class OpenRequest(
    val location: String,
    val title: String?,
    val subtitle: String? = null,
    val mediaFileId: Long? = null,
    val startPositionMs: Long = 0,
    val playWhenReady: Boolean = true,
    val audioIndex: Int? = null,
    /** [SUBTITLE_OFF] switches subtitles off; null lets the language policy decide. */
    val subtitleIndex: Int? = null,
    val audioDelayMs: Long = 0,
    val subtitleDelayMs: Long = 0,
    val externalSubtitles: List<ExternalSubtitle> = emptyList(),
    val languages: LanguagePreferences = LanguagePreferences(),
    val hardwareDecoding: Boolean = true,
    val nightMode: Boolean = false
)

/** What the "statistics" panel shows. */
data class PlaybackStats(
    val displayedFrames: Int,
    val lostFrames: Int,
    val inputKbps: Int,
    val demuxKbps: Int,
    val decodedVideo: Int
)

data class EngineState(
    val status: EngineStatus = EngineStatus.IDLE,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val buffering: Float = 100f,
    val speed: Float = 1f,
    /** 0..200 (VLC amplifies above 100). */
    val volume: Int = 100,
    val muted: Boolean = false,
    val audio: List<AudioOption> = emptyList(),
    val subtitles: List<SubtitleOption> = emptyList(),
    val audioDelayMs: Long = 0,
    val subtitleDelayMs: Long = 0,
    val displayMode: DisplayMode = DisplayMode.FIT,
    val zoom: Float = 1f,
    val chapters: List<Pair<String, Long>> = emptyList(),
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val error: String? = null
) {
    val selectedAudioIndex: Int? get() = audio.firstOrNull { it.isSelected }?.index
    val selectedSubtitleIndex: Int? get() = subtitles.firstOrNull { it.isSelected }?.index
}

sealed interface EngineEvent {
    data object Ended : EngineEvent
    data class Error(val message: String) : EngineEvent
}

/**
 * One libVLC player drawing straight into a heavyweight AWT canvas (Direct3D 11 output, GPU decoding): no frame copy
 * through the JVM, so 4K HDR plays as smoothly as in VLC itself. Track indexes exposed here are positions in the
 * media's list (0, 1, 2...), stable for a file and the same numbering as the phone's, so they can be saved and sent
 * over the companion protocol; VLC's own ES ids stay inside this class.
 */
class VlcEngine {
    val canvas: Canvas = Canvas().apply { background = Color.BLACK }
    private val player: EmbeddedMediaPlayer? = Vlc.factory()?.mediaPlayers()?.newEmbeddedMediaPlayer()

    val isAvailable: Boolean get() = player != null

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    private var request: OpenRequest? = null
    private var tracksApplied = false
    private var surfaceAttached = false

    // region commands

    /** libVLC needs the canvas on screen (a native window handle) before it opens anything, or it opens its own window. */
    suspend fun awaitSurface(timeoutMs: Long = 4_000) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!(canvas.isDisplayable && canvas.isShowing) && System.currentTimeMillis() < until) kotlinx.coroutines.delay(20)
    }

    fun open(r: OpenRequest) {
        val p = player ?: return run { _state.value = EngineState(status = EngineStatus.ERROR, error = "Moteur vidéo indisponible : ${Vlc.loadError ?: "libVLC introuvable"}") }
        ensureSurface()
        request = r
        tracksApplied = false
        val keepVolume = _state.value.volume
        val keepMuted = _state.value.muted
        _state.value = EngineState(status = EngineStatus.OPENING, volume = keepVolume, muted = keepMuted, audioDelayMs = r.audioDelayMs, subtitleDelayMs = r.subtitleDelayMs)

        val options = buildList {
            if (r.startPositionMs > 0) add(":start-time=${r.startPositionMs / 1000.0}")
            if (!r.hardwareDecoding) add(":avcodec-hw=none")
            if (r.nightMode) add(":audio-filter=compressor")
            r.languages.audio.takeIf { it.isNotEmpty() }?.let { add(":audio-language=${it.joinToString(",")}") }
        }.toTypedArray()

        // A plain path goes to libVLC as a path (it builds the file:/// MRL itself, spaces and accents included)
        val location = if (isUrl(r.location)) r.location else File(r.location).absolutePath
        p.submit {
            p.media().prepare(location, *options)
            r.externalSubtitles.forEach { sub ->
                val uri = if (isUrl(sub.location)) sub.location else File(sub.location).toPath().toUri().toASCIIString()
                p.media().addSlave(MediaSlaveType.SUBTITLE, uri, false)
            }
            p.controls().play()
            if (!r.playWhenReady) p.controls().setPause(true)
        }
    }

    fun play() = player?.submit { player.controls().setPause(false) }
    fun pause() = player?.submit { player.controls().setPause(true) }
    fun togglePause() = if (_state.value.isPlaying) pause() else play()

    fun stop() {
        player?.submit { player.controls().stop() }
        request = null
        _state.update { EngineState(volume = it.volume, muted = it.muted) }
    }

    fun seekTo(positionMs: Long) {
        val p = player ?: return
        val duration = _state.value.durationMs
        val target = if (duration > 0) positionMs.coerceIn(0, duration - 500) else positionMs.coerceAtLeast(0)
        _state.update { it.copy(positionMs = target) }
        if (_state.value.status == EngineStatus.ENDED) {
            p.submit { p.controls().play(); p.controls().setTime(target) }
        } else {
            p.submit { p.controls().setTime(target) }
        }
    }

    fun seekBy(deltaMs: Long) = seekTo(_state.value.positionMs + deltaMs)

    fun setSpeed(speed: Float) {
        player?.submit { player.controls().setRate(speed) }
        _state.update { it.copy(speed = speed) }
    }

    fun setVolume(volume: Int) {
        val v = volume.coerceIn(0, 200)
        player?.submit { player.audio().setVolume(v) }
        _state.update { it.copy(volume = v, muted = if (v > 0) false else it.muted) }
    }

    fun setMuted(muted: Boolean) {
        player?.submit { player.audio().setMute(muted) }
        _state.update { it.copy(muted = muted) }
    }

    fun selectAudio(index: Int) {
        val p = player ?: return
        p.submit {
            val ids = audioIds(p)
            ids.getOrNull(index)?.let { p.audio().setTrack(it) }
            refreshTracks(p)
        }
    }

    /** [SUBTITLE_OFF] or a negative index switches subtitles off. */
    fun selectSubtitle(index: Int) {
        val p = player ?: return
        p.submit {
            if (index < 0) p.subpictures().setTrack(-1) else subtitleIds(p).getOrNull(index)?.let { p.subpictures().setTrack(it) }
            refreshTracks(p)
        }
    }

    fun addSubtitleFile(file: File) {
        val p = player ?: return
        p.submit {
            p.media().addSlave(MediaSlaveType.SUBTITLE, file.toPath().toUri().toASCIIString(), true)
        }
    }

    fun setAudioDelay(ms: Long) {
        player?.submit { player.audio().setDelay(ms * 1000) }
        _state.update { it.copy(audioDelayMs = ms) }
    }

    fun setSubtitleDelay(ms: Long) {
        player?.submit { player.subpictures().setDelay(ms * 1000) }
        _state.update { it.copy(subtitleDelayMs = ms) }
    }

    fun setDisplayMode(mode: DisplayMode) {
        _state.update { it.copy(displayMode = mode) }
        applyDisplay()
    }

    fun setZoom(zoom: Float) {
        _state.update { it.copy(zoom = zoom.coerceIn(1f, 4f)) }
        applyDisplay()
    }

    /** Re-applies the display mode (the window was resized: "fill" depends on the window's shape). */
    fun applyDisplay() {
        val p = player ?: return
        val s = _state.value
        val w = canvas.width.coerceAtLeast(1)
        val h = canvas.height.coerceAtLeast(1)
        p.submit {
            when (s.displayMode) {
                DisplayMode.FIT -> { p.video().setAspectRatio(null); p.video().setCropGeometry(null) }
                DisplayMode.FILL -> { p.video().setAspectRatio(null); p.video().setCropGeometry("$w:$h") }
                DisplayMode.STRETCH -> { p.video().setCropGeometry(null); p.video().setAspectRatio("$w:$h") }
                DisplayMode.ORIGINAL -> { p.video().setAspectRatio(null); p.video().setCropGeometry(null) }
                DisplayMode.RATIO_16_9 -> { p.video().setCropGeometry(null); p.video().setAspectRatio("16:9") }
                DisplayMode.RATIO_4_3 -> { p.video().setCropGeometry(null); p.video().setAspectRatio("4:3") }
                DisplayMode.RATIO_21_9 -> { p.video().setCropGeometry(null); p.video().setAspectRatio("21:9") }
            }
            val scale = when {
                s.displayMode == DisplayMode.ORIGINAL -> 1f
                s.zoom != 1f -> fitScale(p, w, h) * s.zoom
                else -> 0f
            }
            p.video().setScale(scale)
        }
    }

    /** Reopens the current media where it is (an option that libVLC only reads at opening changed). */
    fun reopen(change: (OpenRequest) -> OpenRequest) {
        val r = request ?: return
        val s = _state.value
        open(change(r).copy(
            startPositionMs = s.positionMs, playWhenReady = s.isPlaying,
            audioIndex = s.selectedAudioIndex ?: r.audioIndex,
            subtitleIndex = s.selectedSubtitleIndex ?: if (s.subtitles.isNotEmpty()) SUBTITLE_OFF else r.subtitleIndex,
            audioDelayMs = s.audioDelayMs, subtitleDelayMs = s.subtitleDelayMs
        ))
    }

    val currentRequest: OpenRequest? get() = request

    fun stats(): PlaybackStats? {
        val p = player ?: return null
        val st = runCatching { p.media().info()?.statistics() }.getOrNull() ?: return null
        return PlaybackStats(
            displayedFrames = st.picturesDisplayed(),
            lostFrames = st.picturesLost(),
            inputKbps = (st.inputBitrate() * 8000).toInt(),
            demuxKbps = (st.demuxBitrate() * 8000).toInt(),
            decodedVideo = st.decodedVideo()
        )
    }

    fun setChapter(index: Int) = player?.submit { player.chapters().setChapter(index) }

    fun nextChapter() = player?.submit { player.chapters().next() }
    fun previousChapter() = player?.submit { player.chapters().previous() }

    fun snapshot(file: File): Boolean = player?.snapshots()?.save(file) ?: false

    fun release() {
        player?.events()?.removeMediaPlayerEventListener(listener)
        player?.release()
    }

    // endregion

    private fun isUrl(location: String) = location.startsWith("http://") || location.startsWith("https://") || location.startsWith("file:")

    private fun ensureSurface() {
        val p = player ?: return
        if (surfaceAttached) return
        p.videoSurface().set(Vlc.factory()!!.videoSurfaces().newVideoSurface(canvas))
        surfaceAttached = true
    }

    private fun fitScale(p: MediaPlayer, w: Int, h: Int): Float {
        val d = p.video().videoDimension() ?: return 1f
        if (d.width <= 0 || d.height <= 0) return 1f
        return minOf(w.toFloat() / d.width, h.toFloat() / d.height)
    }

    private fun audioIds(p: MediaPlayer): List<Int> = p.audio().trackDescriptions().map { it.id() }.filter { it >= 0 }
    private fun subtitleIds(p: MediaPlayer): List<Int> = p.subpictures().trackDescriptions().map { it.id() }.filter { it >= 0 }

    /** Must run on a libVLC thread ([MediaPlayer.submit]). */
    private fun refreshTracks(p: MediaPlayer) {
        val selectedAudio = p.audio().track()
        val selectedSub = p.subpictures().track()
        val infos = p.media().info()?.audioTracks().orEmpty()
        val audio = p.audio().trackDescriptions().filter { it.id() >= 0 }.mapIndexed { i, d ->
            val info = infos.getOrNull(i)
            AudioOption(
                index = i,
                language = info?.language()?.takeIf { it.isNotBlank() } ?: languageFrom(d.description()),
                codec = info?.codecDescription() ?: info?.codecName()?.trim(),
                channels = info?.channels() ?: 2,
                label = d.description(),
                isDefault = i == 0,
                isSelected = d.id() == selectedAudio,
                isSupported = true
            )
        }
        val textInfos = p.media().info()?.textTracks().orEmpty()
        val subs = p.subpictures().trackDescriptions().filter { it.id() >= 0 }.mapIndexed { i, d ->
            val info = textInfos.getOrNull(i)
            val label = d.description()
            SubtitleOption(
                index = i,
                language = info?.language()?.takeIf { it.isNotBlank() } ?: languageFrom(label),
                codec = info?.codecName()?.trim(),
                label = label,
                isForced = label?.contains("forc", ignoreCase = true) == true,
                isDefault = false,
                isSelected = d.id() == selectedSub,
                isExternal = info == null,
                isSupported = true
            )
        }
        val chapters = runCatching { p.chapters().descriptions().map { (it.name() ?: "") to it.offset() } }.getOrDefault(emptyList())
        val dim = p.video().videoDimension()
        _state.update { it.copy(audio = audio, subtitles = subs, chapters = chapters, videoWidth = dim?.width ?: 0, videoHeight = dim?.height ?: 0) }
    }

    /** First play of a media: saved choices first, then the language policy. Runs on a libVLC thread. */
    private fun applyInitialTracks(p: MediaPlayer) {
        val r = request ?: return
        refreshTracks(p)
        val s = _state.value
        val policy = TrackSelectionPolicy.choose(s.audio, s.subtitles, r.languages)
        val audio = r.audioIndex?.takeIf { it in s.audio.indices } ?: policy.audioIndex
        val subtitle = when {
            r.subtitleIndex == SUBTITLE_OFF -> SUBTITLE_OFF
            r.subtitleIndex != null && r.subtitleIndex in s.subtitles.indices -> r.subtitleIndex
            else -> policy.subtitleIndex ?: SUBTITLE_OFF
        }
        audio?.let { idx -> audioIds(p).getOrNull(idx)?.let { p.audio().setTrack(it) } }
        if (subtitle < 0) p.subpictures().setTrack(-1) else subtitleIds(p).getOrNull(subtitle)?.let { p.subpictures().setTrack(it) }
        if (r.audioDelayMs != 0L) p.audio().setDelay(r.audioDelayMs * 1000)
        if (r.subtitleDelayMs != 0L) p.subpictures().setDelay(r.subtitleDelayMs * 1000)
        p.audio().setVolume(s.volume)
        p.audio().setMute(s.muted)
        refreshTracks(p)
        tracksApplied = true
        applyDisplay()
    }

    private fun languageFrom(description: String?): String? {
        val d = description ?: return null
        val bracket = Regex("\\[([^\\]]+)]").findAll(d).map { it.groupValues[1] }.lastOrNull()
        val candidates = listOfNotNull(bracket) + d.split(' ', '-', '(', ')', '[', ']').filter { it.isNotBlank() }
        for (c in candidates) {
            val norm = LanguageCodes.normalize(c) ?: continue
            if (LanguageCodes.isKnownCode(norm) || norm.length == 2) return norm
            NAMES[c.lowercase()]?.let { return it }
        }
        return candidates.firstNotNullOfOrNull { NAMES[it.lowercase()] }
    }

    private val listener = object : MediaPlayerEventAdapter() {
        override fun mediaChanged(mediaPlayer: MediaPlayer, media: MediaRef) = Unit

        override fun opening(mediaPlayer: MediaPlayer) = _state.update { it.copy(status = EngineStatus.OPENING) }

        override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) = _state.update {
            it.copy(buffering = newCache, status = if (newCache < 100f && it.status != EngineStatus.READY) EngineStatus.BUFFERING else if (it.status == EngineStatus.BUFFERING) EngineStatus.READY else it.status)
        }

        override fun playing(mediaPlayer: MediaPlayer) {
            _state.update { it.copy(status = EngineStatus.READY, isPlaying = true) }
            if (!tracksApplied) mediaPlayer.submit { Thread.sleep(150); applyInitialTracks(mediaPlayer) }
        }

        override fun paused(mediaPlayer: MediaPlayer) = _state.update { it.copy(isPlaying = false) }

        override fun stopped(mediaPlayer: MediaPlayer) = _state.update { it.copy(isPlaying = false) }

        override fun finished(mediaPlayer: MediaPlayer) {
            _state.update { it.copy(status = EngineStatus.ENDED, isPlaying = false, positionMs = it.durationMs) }
            _events.tryEmit(EngineEvent.Ended)
        }

        override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) = _state.update { it.copy(positionMs = newTime) }

        override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) = _state.update { it.copy(durationMs = newLength) }

        override fun elementaryStreamAdded(mediaPlayer: MediaPlayer, type: TrackType, id: Int) {
            if (tracksApplied) mediaPlayer.submit { refreshTracks(mediaPlayer) }
        }

        override fun elementaryStreamSelected(mediaPlayer: MediaPlayer, type: TrackType, id: Int) {
            if (tracksApplied) mediaPlayer.submit { refreshTracks(mediaPlayer) }
        }

        override fun videoOutput(mediaPlayer: MediaPlayer, newCount: Int) {
            mediaPlayer.submit { refreshTracks(mediaPlayer) }
        }

        override fun volumeChanged(mediaPlayer: MediaPlayer, volume: Float) = Unit

        override fun error(mediaPlayer: MediaPlayer) {
            val message = "Lecture impossible : le fichier est introuvable, illisible ou le flux a été coupé."
            _state.update { it.copy(status = EngineStatus.ERROR, isPlaying = false, error = message) }
            _events.tryEmit(EngineEvent.Error(message))
        }
    }

    init {
        player?.let { p ->
            p.input().enableMouseInputHandling(false)
            p.input().enableKeyInputHandling(false)
            p.events().addMediaPlayerEventListener(listener)
        }
    }

    private companion object {
        val NAMES = mapOf(
            "french" to "fr", "français" to "fr", "francais" to "fr", "english" to "en", "anglais" to "en", "german" to "de",
            "deutsch" to "de", "spanish" to "es", "español" to "es", "italian" to "it", "japanese" to "ja", "japonais" to "ja",
            "portuguese" to "pt", "russian" to "ru", "korean" to "ko", "chinese" to "zh", "arabic" to "ar", "arabe" to "ar",
            "dutch" to "nl", "polish" to "pl", "turkish" to "tr"
        )
    }
}
