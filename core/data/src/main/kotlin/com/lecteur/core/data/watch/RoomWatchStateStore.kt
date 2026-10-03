package com.lecteur.core.data.watch

import com.lecteur.core.database.dao.WatchStateDao
import com.lecteur.core.database.entity.WatchStateEntity
import com.lecteur.core.model.WatchState
import com.lecteur.core.player.resume.WatchStateStore
import javax.inject.Inject

class RoomWatchStateStore @Inject constructor(
    private val dao: WatchStateDao
) : WatchStateStore {

    override suspend fun get(mediaFileId: Long): WatchState? = dao.getWatchState(mediaFileId)?.toModel()

    override suspend fun save(state: WatchState) {
        dao.upsertWatchState(state.toEntity())
    }
}

internal fun WatchStateEntity.toModel() = WatchState(
    id = id,
    mediaFileId = mediaFileId,
    positionMs = positionMs,
    durationMs = durationMs,
    isCompleted = isCompleted,
    playCount = playCount,
    lastWatchedAt = lastWatchedAt,
    selectedAudioTrackIndex = selectedAudioTrack,
    selectedSubtitleTrackIndex = selectedSubtitleTrack,
    audioDelayMs = audioDelayMs,
    subtitleDelayMs = subtitleDelayMs,
    displayMode = displayMode
)

internal fun WatchState.toEntity() = WatchStateEntity(
    id = id,
    mediaFileId = mediaFileId,
    positionMs = positionMs,
    durationMs = durationMs,
    isCompleted = isCompleted,
    playCount = playCount,
    lastWatchedAt = lastWatchedAt,
    selectedAudioTrack = selectedAudioTrackIndex,
    selectedSubtitleTrack = selectedSubtitleTrackIndex,
    audioDelayMs = audioDelayMs,
    subtitleDelayMs = subtitleDelayMs,
    displayMode = displayMode
)
