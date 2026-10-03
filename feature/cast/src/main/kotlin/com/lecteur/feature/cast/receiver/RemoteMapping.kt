package com.lecteur.feature.cast.receiver

import com.lecteur.core.common.cast.protocol.Open
import com.lecteur.core.common.cast.protocol.RemoteState
import com.lecteur.core.common.cast.protocol.RemoteStatus
import com.lecteur.core.common.cast.protocol.RemoteTrack
import com.lecteur.core.player.engine.ExternalSubtitleSource
import com.lecteur.core.player.engine.PlaybackRequest
import com.lecteur.core.player.engine.PlaybackStatus
import com.lecteur.core.player.engine.PlayerState
import com.lecteur.core.player.engine.Progress
import com.lecteur.core.player.tracks.LanguagePreferences

/** Translation between the wire protocol and the player's own types. */
internal object RemoteMapping {

    fun toRequest(open: Open, languages: LanguagePreferences): PlaybackRequest = PlaybackRequest(
        uri = open.streamUrl,
        title = open.title,
        mediaFileId = open.mediaFileId,
        startPositionMs = open.startPositionMs,
        playWhenReady = open.playWhenReady,
        externalSubtitles = open.subtitles.map {
            ExternalSubtitleSource(it.url, it.mimeType, it.language, it.label, it.isForced)
        },
        languagePreferences = languages,
        savedAudioIndex = open.audioIndex,
        savedSubtitleIndex = open.subtitleIndex,
        audioDelayMs = open.audioDelayMs,
        subtitleDelayMs = open.subtitleDelayMs
    )

    fun toRemote(
        state: PlayerState,
        progress: Progress,
        mediaFileId: Long?,
        title: String?,
        volume: Float,
        muted: Boolean
    ): RemoteState = RemoteState(
        status = when (state.status) {
            PlaybackStatus.IDLE -> RemoteStatus.IDLE
            PlaybackStatus.BUFFERING -> RemoteStatus.BUFFERING
            PlaybackStatus.READY -> RemoteStatus.READY
            PlaybackStatus.ENDED -> RemoteStatus.ENDED
            PlaybackStatus.ERROR -> RemoteStatus.ERROR
        },
        mediaFileId = mediaFileId,
        title = title,
        isPlaying = state.isPlaying,
        speed = state.speed,
        positionMs = progress.positionMs,
        durationMs = progress.durationMs,
        volume = volume,
        muted = muted,
        audio = state.audioOptions.map {
            RemoteTrack(it.index, it.language, it.label, it.codec, it.isSelected, it.isSupported)
        },
        subtitles = state.subtitleOptions.map {
            RemoteTrack(it.index, it.language, it.label, it.codec, it.isSelected, it.isSupported)
        },
        errorMessage = state.error?.message,
        errorSuggestion = state.error?.suggestion
    )
}
