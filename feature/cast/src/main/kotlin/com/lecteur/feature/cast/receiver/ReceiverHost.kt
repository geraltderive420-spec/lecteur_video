package com.lecteur.feature.cast.receiver

import android.content.Context
import android.media.AudioManager
import androidx.media3.common.util.UnstableApi
import com.lecteur.core.common.cast.protocol.Ended
import com.lecteur.core.common.cast.protocol.Open
import com.lecteur.core.common.cast.protocol.PairingCode
import com.lecteur.core.common.cast.protocol.Pause
import com.lecteur.core.common.cast.protocol.Play
import com.lecteur.core.common.cast.protocol.ReceiverAdvert
import com.lecteur.core.common.cast.protocol.ReceiverListener
import com.lecteur.core.common.cast.protocol.RemoteMessage
import com.lecteur.core.common.cast.protocol.RemoteProgress
import com.lecteur.core.common.cast.protocol.RemoteReceiverServer
import com.lecteur.core.common.cast.protocol.RequestState
import com.lecteur.core.common.cast.protocol.SeekBy
import com.lecteur.core.common.cast.protocol.SeekTo
import com.lecteur.core.common.cast.protocol.SelectAudio
import com.lecteur.core.common.cast.protocol.SelectSubtitle
import com.lecteur.core.common.cast.protocol.SetMuted
import com.lecteur.core.common.cast.protocol.SetSpeed
import com.lecteur.core.common.cast.protocol.SetVolume
import com.lecteur.core.common.cast.protocol.Stop
import com.lecteur.core.player.engine.EngineConfig
import com.lecteur.core.player.engine.Media3PlayerEngine
import com.lecteur.core.player.engine.PlaybackRequest
import com.lecteur.core.player.engine.PlayerEvent
import com.lecteur.core.player.resume.PlaybackSnapshot
import com.lecteur.core.player.resume.ResumeTracker
import com.lecteur.core.player.resume.WatchStateStore
import com.lecteur.core.player.session.PlayerHolder
import com.lecteur.core.player.settings.PlayerSettingsRepository
import com.lecteur.core.player.tracks.SUBTITLE_OFF
import com.lecteur.feature.cast.discovery.NsdAdvertiser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** What the TV screen shows around the player: the code to type on the phone, who is connected, what plays. */
data class ReceiverUi(
    val running: Boolean = false,
    val deviceName: String = "",
    val pairingCode: String = "",
    val controllerName: String? = null,
    /** True while a media is opened: the TV shows the player instead of the library. */
    val playbackActive: Boolean = false,
    val title: String? = null
)

/**
 * TV side of the companion mode. Runs the receiver server and the network advert, and drives the one shared engine
 * from the controller's commands; local playback from the TV's own library goes through the same [open], so there
 * is one player and one screen for both. Everything touching the engine runs on the main thread.
 */
@UnstableApi
@Singleton
class ReceiverHost @Inject constructor(
    @ApplicationContext private val context: Context,
    private val holder: PlayerHolder,
    private val settingsRepository: PlayerSettingsRepository,
    private val watchStateStore: WatchStateStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val advertiser = NsdAdvertiser(context)

    private var server: RemoteReceiverServer? = null
    private var engineJobs = mutableListOf<Job>()
    private var tracker: ResumeTracker? = null

    /** Position the phone must save itself ([trackLocally] false); the TV saves only what it plays from its own library. */
    private var trackLocally = false
    private var current: PlaybackRequest? = null
    private var muted = false
    private var lastProgressSentAt = 0L

    private val _ui = MutableStateFlow(ReceiverUi())
    val ui: StateFlow<ReceiverUi> = _ui.asStateFlow()

    val engine: StateFlow<Media3PlayerEngine?> get() = holder.engine

    // region lifecycle

    /** Starts listening and advertising. Idempotent. */
    fun start(deviceName: String) {
        if (server != null) return
        val code = PairingCode.generate()
        val receiver = RemoteReceiverServer(deviceName, pairingCode = { code }, listener = listener)
        val port = receiver.start()
        server = receiver
        advertiser.register(ReceiverAdvert(deviceName, port))
        _ui.update { it.copy(running = true, deviceName = deviceName, pairingCode = code) }
    }

    fun stop() {
        advertiser.unregister()
        server?.stop()
        server = null
        closePlayback()
        _ui.value = ReceiverUi()
    }

    private val listener = object : ReceiverListener {
        override fun onControllerConnected(deviceName: String) {
            scope.launch {
                _ui.update { it.copy(controllerName = deviceName) }
                pushState()
            }
        }

        override fun onControllerDisconnected() {
            scope.launch { _ui.update { it.copy(controllerName = null) } }
        }

        override fun onCommand(command: RemoteMessage) {
            scope.launch { handle(command) }
        }
    }

    // endregion

    // region playback

    /** Opens [request] on the shared engine. [trackLocally] saves the resume position here (media of the TV's own library). */
    fun open(request: PlaybackRequest, trackLocally: Boolean = false) {
        scope.launch {
            val settings = settingsRepository.settings.first()
            val engine = holder.acquire(EngineConfig(settings.decoderMode, settings.passthrough, settings.tunneling))
            this@ReceiverHost.trackLocally = trackLocally
            current = request
            tracker = if (trackLocally) {
                ResumeTracker(request.mediaFileId, watchStateStore, watchStateStore.get(request.mediaFileId))
            } else {
                null
            }
            bind(engine)
            engine.open(request)
            _ui.update { it.copy(playbackActive = true, title = request.title) }
            pushState()
        }
    }

    /** Back to the library: stops the player, tells the controller. */
    fun closePlayback() {
        val engine = holder.engine.value
        if (engine != null) {
            val snapshot = snapshot(engine)
            scope.launch { tracker?.flush(snapshot) }
            engine.stop()
        }
        engineJobs.forEach { it.cancel() }
        engineJobs.clear()
        current = null
        tracker = null
        _ui.update { it.copy(playbackActive = false, title = null) }
        pushState()
    }

    private fun bind(engine: Media3PlayerEngine) {
        engineJobs.forEach { it.cancel() }
        engineJobs.clear()

        engineJobs += scope.launch {
            engine.state.collect { state ->
                pushState()
                if (!state.isPlaying) flushLocal(engine)
            }
        }
        engineJobs += scope.launch {
            engine.progress.collect { progress ->
                if (trackLocally) tracker?.onProgress(snapshot(engine))
                val now = System.currentTimeMillis()
                if (now - lastProgressSentAt >= PROGRESS_INTERVAL_MS) {
                    lastProgressSentAt = now
                    server?.send(RemoteProgress(current?.mediaFileId, progress.positionMs, progress.durationMs, progress.bufferedMs))
                }
            }
        }
        engineJobs += scope.launch {
            engine.events.collect { event ->
                if (event is PlayerEvent.Ended) {
                    val progress = engine.progress.value
                    server?.send(Ended(current?.mediaFileId, progress.positionMs, progress.durationMs))
                    flushLocal(engine)
                    // A remote session stays on the "ended" screen until the phone picks something else; local playback returns.
                    if (trackLocally) closePlayback()
                }
            }
        }
    }

    private fun flushLocal(engine: Media3PlayerEngine) {
        if (!trackLocally) return
        val snapshot = snapshot(engine)
        scope.launch { tracker?.flush(snapshot) }
    }

    private fun handle(command: RemoteMessage) {
        val engine = holder.engine.value
        when (command) {
            is Open -> scope.launch {
                val settings = settingsRepository.settings.first()
                open(RemoteMapping.toRequest(command, settings.languagePreferences), trackLocally = false)
            }
            RequestState -> pushState()
            Play -> engine?.play()
            Pause -> engine?.pause()
            Stop -> closePlayback()
            is SeekTo -> engine?.seekTo(command.positionMs)
            is SeekBy -> engine?.seekBy(command.deltaMs)
            is SelectAudio -> engine?.selectAudio(command.index)
            is SelectSubtitle -> engine?.selectSubtitle(if (command.index < 0) SUBTITLE_OFF else command.index)
            is SetSpeed -> engine?.setSpeed(command.speed)
            is SetVolume -> {
                setVolume(command.level)
                pushState()
            }
            is SetMuted -> {
                muted = command.muted
                audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                    0
                )
                pushState()
            }
            else -> Unit
        }
    }

    // endregion

    // region state

    private fun pushState() {
        val remote = server ?: return
        val engine = holder.engine.value
        val state = if (engine != null && current != null) {
            RemoteMapping.toRemote(engine.state.value, engine.progress.value, current?.mediaFileId, current?.title, volume(), muted)
        } else {
            com.lecteur.core.common.cast.protocol.RemoteState(volume = volume(), muted = muted)
        }
        remote.send(state)
    }

    private fun snapshot(engine: Media3PlayerEngine): PlaybackSnapshot {
        val state = engine.state.value
        val progress = engine.progress.value
        return PlaybackSnapshot(
            positionMs = progress.positionMs,
            durationMs = progress.durationMs,
            isPlaying = state.isPlaying,
            selectedAudioTrackIndex = state.selectedAudioIndex,
            selectedSubtitleTrackIndex = state.selectedSubtitleIndex ?: if (state.subtitleOptions.isNotEmpty()) SUBTITLE_OFF else null,
            audioDelayMs = state.audioDelayMs,
            subtitleDelayMs = state.subtitleDelayMs,
            displayMode = null
        )
    }

    private fun volume(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
    }

    private fun setVolume(level: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level.coerceIn(0f, 1f) * max).roundToInt(), 0)
    }

    // endregion

    private companion object {
        const val PROGRESS_INTERVAL_MS = 1_000L
    }
}
