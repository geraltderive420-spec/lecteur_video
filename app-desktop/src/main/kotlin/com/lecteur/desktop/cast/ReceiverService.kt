package com.lecteur.desktop.cast

import com.lecteur.core.common.cast.protocol.Ended
import com.lecteur.core.common.cast.protocol.Open
import com.lecteur.core.common.cast.protocol.Pause
import com.lecteur.core.common.cast.protocol.Play
import com.lecteur.core.common.cast.protocol.ReceiverAdvert
import com.lecteur.core.common.cast.protocol.ReceiverListener
import com.lecteur.core.common.cast.protocol.RemoteMessage
import com.lecteur.core.common.cast.protocol.RemoteProgress
import com.lecteur.core.common.cast.protocol.RemoteReceiverServer
import com.lecteur.core.common.cast.protocol.RemoteState
import com.lecteur.core.common.cast.protocol.RemoteStatus
import com.lecteur.core.common.cast.protocol.RemoteTrack
import com.lecteur.core.common.cast.protocol.RequestState
import com.lecteur.core.common.cast.protocol.SeekBy
import com.lecteur.core.common.cast.protocol.SeekTo
import com.lecteur.core.common.cast.protocol.SelectAudio
import com.lecteur.core.common.cast.protocol.SelectSubtitle
import com.lecteur.core.common.cast.protocol.SetMuted
import com.lecteur.core.common.cast.protocol.SetSpeed
import com.lecteur.core.common.cast.protocol.SetVolume
import com.lecteur.core.common.cast.protocol.Stop
import com.lecteur.core.player.tracks.LanguagePreferences
import com.lecteur.core.player.tracks.SUBTITLE_OFF
import com.lecteur.desktop.data.SettingsStore
import com.lecteur.desktop.player.EngineEvent
import com.lecteur.desktop.player.EngineState
import com.lecteur.desktop.player.EngineStatus
import com.lecteur.desktop.player.ExternalSubtitle
import com.lecteur.desktop.player.OpenRequest
import com.lecteur.desktop.player.PlaybackController
import com.lecteur.desktop.player.PlaybackOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

data class ReceiverUi(
    val running: Boolean = false,
    val deviceName: String = "",
    val pairingCode: String = "",
    val port: Int = 0,
    val controllerName: String? = null,
    val error: String? = null
)

/**
 * The PC as a companion-mode receiver, exactly like the Android TV app: advertised on the network, a 6-digit code to
 * type on the phone, then the phone's commands drive the one player of the app. The phone keeps the watch state of its
 * own library; this side only reports position and tracks.
 */
class ReceiverService(
    private val playback: PlaybackController,
    private val settings: SettingsStore,
    private val mdns: Mdns
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sender = Executors.newSingleThreadExecutor { Thread(it, "receiver-send").apply { isDaemon = true } }
    private var server: RemoteReceiverServer? = null
    private var jobs = mutableListOf<Job>()
    private var current: Open? = null
    private var lastProgressAt = 0L

    private val engine get() = playback.engine

    private val _ui = MutableStateFlow(ReceiverUi())
    val ui: StateFlow<ReceiverUi> = _ui.asStateFlow()

    init {
        // Local playback started on the PC while a phone was casting: the phone sees the session end
        playback.onLocalTakeover = {
            if (current != null) {
                current = null
                send(RemoteState(volume = volume(), muted = engine.state.value.muted))
            }
        }
    }

    @Synchronized
    fun start() {
        if (server != null) return
        val s = settings.value
        val receiver = RemoteReceiverServer(s.receiverName, pairingCode = { settings.value.pairingCode }, listener = listener)
        try {
            val port = receiver.start()
            server = receiver
            mdns.advertise(ReceiverAdvert(s.receiverName, port))
            _ui.value = ReceiverUi(running = true, deviceName = s.receiverName, pairingCode = s.pairingCode, port = port)
            bind()
        } catch (e: Exception) {
            _ui.value = ReceiverUi(error = "Réception impossible : ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        mdns.unadvertise()
        server?.stop()
        server = null
        jobs.forEach { it.cancel() }
        jobs.clear()
        if (current != null) playback.endRemote()
        current = null
        playback.setRemoteController(null)
        _ui.value = ReceiverUi()
    }

    fun restart() {
        stop()
        start()
    }

    fun newPairingCode() {
        settings.update { it.copy(pairingCode = com.lecteur.core.common.cast.protocol.PairingCode.generate()) }
        _ui.update { it.copy(pairingCode = settings.value.pairingCode) }
    }

    private val listener = object : ReceiverListener {
        override fun onControllerConnected(deviceName: String) {
            _ui.update { it.copy(controllerName = deviceName) }
            playback.setRemoteController(deviceName)
            pushState()
        }

        override fun onControllerDisconnected() {
            _ui.update { it.copy(controllerName = null) }
            playback.setRemoteController(null)
        }

        override fun onCommand(command: RemoteMessage) {
            scope.launch { handle(command) }
        }
    }

    private suspend fun handle(command: RemoteMessage) {
        val remote = current != null && playback.ui.value.owner == PlaybackOwner.REMOTE
        when (command) {
            is Open -> open(command)
            RequestState -> pushState()
            Play -> if (remote) engine.play()
            Pause -> if (remote) engine.pause()
            Stop -> if (remote) {
                current = null
                playback.endRemote()
                pushState()
            }
            is SeekTo -> if (remote) engine.seekTo(command.positionMs)
            is SeekBy -> if (remote) engine.seekBy(command.deltaMs)
            is SelectAudio -> if (remote) engine.selectAudio(command.index)
            is SelectSubtitle -> if (remote) engine.selectSubtitle(if (command.index < 0) SUBTITLE_OFF else command.index)
            is SetSpeed -> if (remote) engine.setSpeed(command.speed)
            is SetVolume -> {
                engine.setVolume((command.level.coerceIn(0f, 1f) * 100).toInt())
                pushState()
            }
            is SetMuted -> {
                engine.setMuted(command.muted)
                pushState()
            }
            else -> Unit
        }
    }

    private suspend fun open(open: Open) {
        val s = settings.value
        current = open
        playback.handOverToRemote(open.title, _ui.value.controllerName)
        engine.awaitSurface()
        engine.open(
            OpenRequest(
                location = open.streamUrl,
                title = open.title,
                mediaFileId = open.mediaFileId,
                startPositionMs = open.startPositionMs,
                playWhenReady = open.playWhenReady,
                audioIndex = open.audioIndex,
                subtitleIndex = open.subtitleIndex,
                audioDelayMs = open.audioDelayMs,
                subtitleDelayMs = open.subtitleDelayMs,
                externalSubtitles = open.subtitles.map { ExternalSubtitle(it.url, it.language, it.label, it.isForced) },
                languages = LanguagePreferences(s.audioLanguages, s.subtitleLanguages, s.subtitleMode),
                hardwareDecoding = s.hardwareDecoding
            )
        )
        pushState()
    }

    private fun bind() {
        jobs += scope.launch {
            var previous: EngineState? = null
            engine.state.collect { state ->
                if (current == null) return@collect
                val structural = previous == null || previous!!.status != state.status || previous!!.isPlaying != state.isPlaying ||
                    previous!!.audio != state.audio || previous!!.subtitles != state.subtitles || previous!!.speed != state.speed ||
                    previous!!.volume != state.volume || previous!!.muted != state.muted || previous!!.durationMs != state.durationMs
                previous = state
                if (structural) pushState()
                val now = System.currentTimeMillis()
                if (state.isPlaying && now - lastProgressAt >= 1_000) {
                    lastProgressAt = now
                    send(RemoteProgress(current?.mediaFileId, state.positionMs, state.durationMs))
                }
            }
        }
        jobs += scope.launch {
            engine.events.collect { event ->
                if (event is EngineEvent.Ended && current != null) {
                    val s = engine.state.value
                    send(Ended(current?.mediaFileId, s.positionMs, s.durationMs))
                }
            }
        }
        // The user closed the player window on the PC during a cast: tell the phone
        jobs += scope.launch {
            playback.ui.collect { ui ->
                if (!ui.active && current != null) {
                    current = null
                    pushState()
                }
            }
        }
    }

    private fun pushState() {
        if (server == null) return
        val open = current
        val s = engine.state.value
        val state = if (open == null) {
            RemoteState(volume = volume(), muted = s.muted)
        } else {
            RemoteState(
                status = when (s.status) {
                    EngineStatus.IDLE -> RemoteStatus.IDLE
                    EngineStatus.OPENING, EngineStatus.BUFFERING -> RemoteStatus.BUFFERING
                    EngineStatus.READY -> RemoteStatus.READY
                    EngineStatus.ENDED -> RemoteStatus.ENDED
                    EngineStatus.ERROR -> RemoteStatus.ERROR
                },
                mediaFileId = open.mediaFileId,
                title = open.title,
                isPlaying = s.isPlaying,
                speed = s.speed,
                positionMs = s.positionMs,
                durationMs = s.durationMs,
                volume = volume(),
                muted = s.muted,
                audio = s.audio.map { RemoteTrack(it.index, it.language, it.label, it.codec, it.isSelected, it.isSupported) },
                subtitles = s.subtitles.map { RemoteTrack(it.index, it.language, it.label, it.codec, it.isSelected, it.isSupported) },
                errorMessage = s.error,
                errorSuggestion = if (s.error != null) "Vérifiez que le téléphone et le PC sont sur le même réseau et que le pare-feu Windows autorise Lecteur Média." else null
            )
        }
        send(state)
    }

    private fun volume(): Float = (engine.state.value.volume / 100f).coerceIn(0f, 1f)

    private fun send(message: RemoteMessage) {
        sender.execute { server?.send(message) }
    }
}
