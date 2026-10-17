package com.lecteur.desktop.cast

import com.lecteur.core.common.cast.protocol.ControllerListener
import com.lecteur.core.common.cast.protocol.Ended
import com.lecteur.core.common.cast.protocol.HelloRefusal
import com.lecteur.core.common.cast.protocol.Open
import com.lecteur.core.common.cast.protocol.Pause
import com.lecteur.core.common.cast.protocol.Play
import com.lecteur.core.common.cast.protocol.RemoteControllerClient
import com.lecteur.core.common.cast.protocol.RemoteMessage
import com.lecteur.core.common.cast.protocol.RemoteProgress
import com.lecteur.core.common.cast.protocol.RemoteState
import com.lecteur.core.common.cast.protocol.RemoteStatus
import com.lecteur.core.common.cast.protocol.RemoteSubtitle
import com.lecteur.core.common.cast.protocol.RequestState
import com.lecteur.core.common.cast.protocol.SeekBy
import com.lecteur.core.common.cast.protocol.SeekTo
import com.lecteur.core.common.cast.protocol.SelectAudio
import com.lecteur.core.common.cast.protocol.SelectSubtitle
import com.lecteur.core.common.cast.protocol.SetMuted
import com.lecteur.core.common.cast.protocol.SetSpeed
import com.lecteur.core.common.cast.protocol.SetVolume
import com.lecteur.core.common.cast.protocol.Stop
import com.lecteur.core.model.WatchState
import com.lecteur.core.player.resume.PlaybackSnapshot
import com.lecteur.core.player.resume.ResumePolicy
import com.lecteur.core.player.resume.ResumeTracker
import com.lecteur.core.player.resume.WatchStateStore
import com.lecteur.core.player.tracks.SUBTITLE_OFF
import com.lecteur.desktop.data.LibraryStore
import com.lecteur.desktop.data.SettingsStore
import com.lecteur.desktop.player.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val receiverName: String) : ConnectionState
    data class Connected(val receiverName: String) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

data class RemoteUi(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val playback: RemoteState = RemoteState(),
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val notice: String? = null
)

/**
 * The PC driving a TV (or another PC) in companion mode, as the phone does: one connection at a time, files of the
 * library streamed from here, the watch state kept in step with what the TV reports.
 */
class RemoteSession(
    private val store: LibraryStore,
    private val settings: SettingsStore,
    private val streams: StreamHost
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: RemoteControllerClient? = null
    private var receiverHost: String? = null
    private var tracker: ResumeTracker? = null
    private var delays = 0L to 0L
    private val tokens = mutableListOf<String>()

    private val _ui = MutableStateFlow(RemoteUi())
    val ui: StateFlow<RemoteUi> = _ui.asStateFlow()

    val isConnected: Boolean get() = _ui.value.connection is ConnectionState.Connected

    private val watchStore = object : WatchStateStore {
        override suspend fun get(mediaFileId: Long): WatchState? = store.watchState(mediaFileId)
        override suspend fun save(state: WatchState) = store.saveWatch(state)
    }

    fun savedCode(receiver: DiscoveredReceiver): String? = settings.value.remotePairings[receiver.deviceName]

    suspend fun connect(receiver: DiscoveredReceiver, code: String): Boolean = withContext(Dispatchers.IO) {
        disconnect()
        _ui.value = RemoteUi(connection = ConnectionState.Connecting(receiver.deviceName))
        val created = RemoteControllerClient(settings.value.receiverName, listener)
        try {
            val welcome = created.connect(receiver.host, receiver.port, code)
            if (!welcome.accepted) {
                if (welcome.refusal == HelloRefusal.BAD_PAIRING_CODE) settings.update { it.copy(remotePairings = it.remotePairings - receiver.deviceName) }
                _ui.value = RemoteUi(connection = ConnectionState.Failed(refusal(welcome.refusal)))
                return@withContext false
            }
            client = created
            receiverHost = receiver.host
            settings.update { it.copy(remotePairings = it.remotePairings + (receiver.deviceName to code)) }
            _ui.value = RemoteUi(connection = ConnectionState.Connected(welcome.deviceName.ifBlank { receiver.deviceName }))
            created.send(RequestState)
            true
        } catch (_: IOException) {
            created.close()
            _ui.value = RemoteUi(connection = ConnectionState.Failed("Impossible de joindre l'écran. Vérifiez qu'il est allumé, sur le même réseau, et que l'application y est ouverte."))
            false
        }
    }

    /** Sends a library file to the connected screen. */
    fun cast(mediaFileId: Long, fromStart: Boolean = false) {
        scope.launch {
            val file = store.mediaFile(mediaFileId) ?: return@launch notice("Ce fichier n'est plus dans la bibliothèque.")
            val shared = try {
                streams.share(File(file.uri), StreamHost.mimeFor(file.fileName), receiverHost)
            } catch (e: ShareFailure) {
                return@launch notice(e.message ?: "Partage impossible.")
            }
            revoke()
            tokens += shared.token
            val subtitles = store.subtitleTracks(mediaFileId).filter { it.externalPath != null && File(it.externalPath!!).isFile }.mapNotNull { sub ->
                runCatching { streams.share(File(sub.externalPath!!), StreamHost.mimeFor(sub.externalPath!!), receiverHost) }.getOrNull()?.let {
                    tokens += it.token
                    RemoteSubtitle(it.url, StreamHost.mimeFor(sub.externalPath!!), sub.language, sub.title, sub.isForced)
                }
            }
            val watch = store.watchState(mediaFileId)
            delays = (watch?.audioDelayMs ?: 0L) to (watch?.subtitleDelayMs ?: 0L)
            tracker = ResumeTracker(mediaFileId, watchStore, watch)
            val start = if (fromStart || watch == null) 0L else ResumePolicy.resumePosition(watch.positionMs, watch.durationMs, watch.isCompleted) ?: 0L
            val (title, subtitle) = PlaybackController.titleOf(store.snapshot.value, mediaFileId)
            val open = Open(
                streamUrl = shared.url,
                title = listOfNotNull(title, subtitle).joinToString(" · "),
                mediaFileId = mediaFileId,
                startPositionMs = start,
                audioIndex = watch?.selectedAudioTrackIndex,
                subtitleIndex = watch?.selectedSubtitleTrackIndex,
                audioDelayMs = delays.first,
                subtitleDelayMs = delays.second,
                subtitles = subtitles
            )
            if (client?.send(open) != true) notice("La connexion avec l'écran est perdue.")
        }
    }

    fun play() = send(Play)
    fun pause() = send(Pause)
    fun stopPlayback() = send(Stop)
    fun seekTo(ms: Long) = send(SeekTo(ms))
    fun seekBy(ms: Long) = send(SeekBy(ms))
    fun selectAudio(i: Int) = send(SelectAudio(i))
    fun selectSubtitle(i: Int) = send(SelectSubtitle(i))
    fun setSpeed(s: Float) = send(SetSpeed(s))
    fun setVolume(v: Float) = send(SetVolume(v))
    fun setMuted(m: Boolean) = send(SetMuted(m))
    fun consumeNotice() = _ui.update { it.copy(notice = null) }

    fun disconnect() {
        flushNow()
        client?.close()
        client = null
        revoke()
        _ui.value = RemoteUi()
    }

    private val listener = object : ControllerListener {
        override fun onEvent(event: RemoteMessage) {
            when (event) {
                is RemoteState -> {
                    _ui.update { it.copy(playback = event, positionMs = event.positionMs, durationMs = event.durationMs) }
                    val snap = snapshotOf(_ui.value)
                    scope.launch { if (event.isPlaying) tracker?.onProgress(snap) else if (event.status != RemoteStatus.IDLE) tracker?.flush(snap) }
                }
                is RemoteProgress -> {
                    _ui.update { it.copy(positionMs = event.positionMs, durationMs = event.durationMs) }
                    val snap = snapshotOf(_ui.value)
                    scope.launch { tracker?.onProgress(snap) }
                }
                is Ended -> {
                    val snap = snapshotOf(_ui.value).copy(positionMs = event.positionMs, durationMs = event.durationMs, isPlaying = false)
                    scope.launch { tracker?.flush(snap) }
                }
                else -> Unit
            }
        }

        override fun onDisconnected(cause: IOException?) {
            flushNow()
            if (client != null) {
                client = null
                _ui.value = RemoteUi(connection = if (cause != null) ConnectionState.Failed("La connexion avec l'écran a été perdue.") else ConnectionState.Disconnected)
            }
        }
    }

    private fun flushNow() {
        val ui = _ui.value
        if (ui.playback.mediaFileId == null || ui.durationMs <= 0) return
        val snap = snapshotOf(ui).copy(isPlaying = false)
        val t = tracker
        scope.launch { t?.flush(snap) }
    }

    private fun snapshotOf(ui: RemoteUi) = PlaybackSnapshot(
        positionMs = ui.positionMs,
        durationMs = ui.durationMs,
        isPlaying = ui.playback.isPlaying,
        selectedAudioTrackIndex = ui.playback.audio.firstOrNull { it.isSelected }?.index,
        selectedSubtitleTrackIndex = ui.playback.subtitles.firstOrNull { it.isSelected }?.index ?: if (ui.playback.subtitles.isNotEmpty()) SUBTITLE_OFF else null,
        audioDelayMs = delays.first,
        subtitleDelayMs = delays.second,
        displayMode = null
    )

    private fun send(command: RemoteMessage) {
        scope.launch { if (client?.send(command) != true) notice("La connexion avec l'écran est perdue.") }
    }

    private fun notice(text: String) = _ui.update { it.copy(notice = text) }

    private fun revoke() {
        tokens.forEach(streams::revoke)
        tokens.clear()
    }

    private fun refusal(r: HelloRefusal?) = when (r) {
        HelloRefusal.BAD_PAIRING_CODE -> "Code incorrect. Saisissez le code affiché sur l'écran."
        HelloRefusal.INCOMPATIBLE_VERSION -> "Versions incompatibles : mettez les deux applications à jour."
        HelloRefusal.WRONG_ROLE, null -> "L'écran a refusé la connexion."
    }
}
