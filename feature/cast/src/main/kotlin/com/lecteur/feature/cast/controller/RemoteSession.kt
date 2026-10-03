package com.lecteur.feature.cast.controller

import android.os.Build
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
import com.lecteur.core.data.playback.PlaybackRequestFactory
import com.lecteur.core.player.resume.PlaybackSnapshot
import com.lecteur.core.player.resume.ResumeTracker
import com.lecteur.core.player.resume.WatchStateStore
import com.lecteur.core.player.tracks.SUBTITLE_OFF
import com.lecteur.feature.cast.discovery.DiscoveredReceiver
import com.lecteur.feature.cast.stream.ShareFailure
import com.lecteur.feature.cast.stream.StreamHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val receiverName: String) : ConnectionState
    data class Connected(val receiverName: String) : ConnectionState

    /** The connection failed or dropped; [message] is ready to show. */
    data class Failed(val message: String) : ConnectionState
}

/** What the remote screen shows. */
data class RemoteUi(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val playback: RemoteState = RemoteState(),
    /** Seek bar position, updated between full states by the lightweight progress ticks. */
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    /** A message about the last action ("fichier introuvable"...) for a one-shot snackbar. */
    val notice: String? = null
)

/**
 * Phone side of companion mode: one connection to one TV at a time. Hands the TV a stream URL for a library file,
 * relays the remote's commands, and keeps the phone's watch state in step with what the TV reports, so the position
 * is already saved when the user comes back to the phone ("reprise de la progression au retour").
 */
@Singleton
class RemoteSession @Inject constructor(
    private val streamHost: StreamHost,
    private val requestFactory: PlaybackRequestFactory,
    private val watchStateStore: WatchStateStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var client: RemoteControllerClient? = null
    private var tracker: ResumeTracker? = null
    private var delays: Pair<Long, Long> = 0L to 0L
    private var mediaTokens = mutableListOf<String>()

    private val _ui = MutableStateFlow(RemoteUi())
    val ui: StateFlow<RemoteUi> = _ui.asStateFlow()

    val isConnected: Boolean get() = _ui.value.connection is ConnectionState.Connected

    /** Connects and performs the handshake. Returns true on success; on failure [ui] carries the reason. */
    suspend fun connect(receiver: DiscoveredReceiver, pairingCode: String): Boolean = withContext(Dispatchers.IO) {
        disconnect()
        _ui.value = RemoteUi(connection = ConnectionState.Connecting(receiver.deviceName))
        val created = RemoteControllerClient(Build.MODEL ?: "Téléphone", listener)
        try {
            val welcome = created.connect(receiver.host, receiver.port, pairingCode)
            if (!welcome.accepted) {
                _ui.value = RemoteUi(connection = ConnectionState.Failed(refusalMessage(welcome.refusal)))
                return@withContext false
            }
            client = created
            _ui.value = RemoteUi(connection = ConnectionState.Connected(welcome.deviceName.ifBlank { receiver.deviceName }))
            created.send(RequestState)
            true
        } catch (e: IOException) {
            created.close()
            _ui.value = RemoteUi(connection = ConnectionState.Failed("Impossible de joindre la TV. Vérifiez qu'elle est allumée, sur le même Wi-Fi, et que l'application TV est ouverte."))
            false
        }
    }

    /** Sends a library file to the TV. [fromStart] ignores the saved position. */
    suspend fun cast(mediaFileId: Long, fromStart: Boolean = false) {
        val prepared = requestFactory.forMediaFile(mediaFileId)
        if (prepared == null) return notice("Ce fichier n'est plus dans la bibliothèque.")
        val shared = try {
            streamHost.share(mediaFileId)
        } catch (e: ShareFailure) {
            return notice(e.message ?: "Impossible de partager ce fichier.")
        }
        revokeTokens()
        mediaTokens += shared.token

        val subtitles = prepared.request.externalSubtitles.mapNotNull { sub ->
            runCatching { streamHost.shareSideFile(sub.uri, sub.mimeType) }.getOrNull()?.let {
                mediaTokens += it.token
                RemoteSubtitle(it.url, sub.mimeType, sub.language, sub.label, sub.isForced)
            }
        }

        val request = prepared.request
        delays = request.audioDelayMs to request.subtitleDelayMs
        tracker = ResumeTracker(mediaFileId, watchStateStore, prepared.watchState)
        val start = if (fromStart) 0L else prepared.resumePromptMs ?: 0L

        val sent = client?.send(
            Open(
                streamUrl = shared.url,
                title = request.title,
                mediaFileId = mediaFileId,
                startPositionMs = start,
                playWhenReady = true,
                audioIndex = request.savedAudioIndex,
                subtitleIndex = request.savedSubtitleIndex,
                audioDelayMs = request.audioDelayMs,
                subtitleDelayMs = request.subtitleDelayMs,
                subtitles = subtitles
            )
        ) == true
        if (!sent) notice("La connexion avec la TV est perdue.")
    }

    fun play() = send(Play)
    fun pause() = send(Pause)
    fun stopPlayback() = send(Stop)
    fun seekTo(positionMs: Long) = send(SeekTo(positionMs))
    fun seekBy(deltaMs: Long) = send(SeekBy(deltaMs))
    fun selectAudio(index: Int) = send(SelectAudio(index))
    fun selectSubtitle(index: Int) = send(SelectSubtitle(index))
    fun setSpeed(speed: Float) = send(SetSpeed(speed))
    fun setVolume(level: Float) = send(SetVolume(level))
    fun setMuted(muted: Boolean) = send(SetMuted(muted))

    fun consumeNotice() = _ui.update { it.copy(notice = null) }

    /** Ends the session: the position is saved first, the stream stops being served. */
    fun disconnect() {
        flushNow()
        client?.close()
        client = null
        revokeTokens()
        _ui.value = RemoteUi()
    }

    // region events

    private val listener = object : ControllerListener {
        override fun onEvent(event: RemoteMessage) {
            when (event) {
                is RemoteState -> onState(event)
                is RemoteProgress -> onProgress(event)
                is Ended -> onEnded(event)
                else -> Unit
            }
        }

        override fun onDisconnected(cause: IOException?) {
            // Save where the TV had got to, whatever the reason the link ended.
            flushNow()
            if (client != null) {
                client = null
                _ui.value = RemoteUi(
                    connection = if (cause != null) {
                        ConnectionState.Failed("La connexion avec la TV a été perdue.")
                    } else {
                        ConnectionState.Disconnected
                    }
                )
            }
        }
    }

    private fun onState(state: RemoteState) {
        _ui.update {
            it.copy(
                playback = state,
                positionMs = state.positionMs,
                durationMs = state.durationMs
            )
        }
        val snapshot = snapshotOf(_ui.value)
        scope.launch {
            if (state.isPlaying) tracker?.onProgress(snapshot) else if (state.status != RemoteStatus.IDLE) tracker?.flush(snapshot)
        }
    }

    private fun onProgress(progress: RemoteProgress) {
        _ui.update { it.copy(positionMs = progress.positionMs, durationMs = progress.durationMs, bufferedMs = progress.bufferedMs) }
        val snapshot = snapshotOf(_ui.value)
        scope.launch { tracker?.onProgress(snapshot) }
    }

    private fun onEnded(ended: Ended) {
        val snapshot = snapshotOf(_ui.value).copy(positionMs = ended.positionMs, durationMs = ended.durationMs, isPlaying = false)
        scope.launch { tracker?.flush(snapshot) }
    }

    private fun flushNow() {
        val ui = _ui.value
        if (ui.playback.mediaFileId == null || ui.durationMs <= 0) return
        val snapshot = snapshotOf(ui).copy(isPlaying = false)
        val t = tracker
        // The scope is process-wide: the write finishes even if the screen that triggered the disconnect is gone.
        scope.launch { t?.flush(snapshot) }
    }

    private fun snapshotOf(ui: RemoteUi): PlaybackSnapshot {
        val playback = ui.playback
        return PlaybackSnapshot(
            positionMs = ui.positionMs,
            durationMs = ui.durationMs,
            isPlaying = playback.isPlaying,
            selectedAudioTrackIndex = playback.audio.firstOrNull { it.isSelected }?.index,
            selectedSubtitleTrackIndex = playback.subtitles.firstOrNull { it.isSelected }?.index
                ?: if (playback.subtitles.isNotEmpty()) SUBTITLE_OFF else null,
            audioDelayMs = delays.first,
            subtitleDelayMs = delays.second,
            displayMode = null
        )
    }

    // endregion

    private fun send(command: RemoteMessage) {
        if (client?.send(command) != true) notice("La connexion avec la TV est perdue.")
    }

    private fun notice(text: String) = _ui.update { it.copy(notice = text) }

    private fun revokeTokens() {
        mediaTokens.forEach(streamHost::revoke)
        mediaTokens.clear()
    }

    private fun refusalMessage(refusal: HelloRefusal?): String = when (refusal) {
        HelloRefusal.BAD_PAIRING_CODE -> "Code incorrect. Saisissez le code affiché sur la TV."
        HelloRefusal.INCOMPATIBLE_VERSION -> "Les versions de l'application sur le téléphone et la TV ne sont pas compatibles. Mettez les deux à jour."
        HelloRefusal.WRONG_ROLE, null -> "La TV a refusé la connexion."
    }
}
