package com.lecteur.core.model

import kotlinx.serialization.Serializable

@Serializable
data class MediaFile(
    val id: Long = 0,
    val folderId: Long,
    val uri: String,
    val displayPath: String,
    val fileName: String,
    val sizeBytes: Long,
    val fingerprint: String,
    val lastModified: Long,
    val durationMs: Long? = null,
    val container: String? = null,
    val videoCodec: VideoCodec = VideoCodec.UNKNOWN,
    val width: Int? = null,
    val height: Int? = null,
    val hdrType: HdrType = HdrType.NONE,
    val movieId: Long? = null,
    val episodeId: Long? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val isAvailable: Boolean = true
)

@Serializable
data class AudioTrackInfo(
    val id: Long = 0,
    val mediaFileId: Long,
    val trackIndex: Int,
    val language: String? = null,
    val codec: AudioCodec = AudioCodec.UNKNOWN,
    val channels: Int = 2,
    val title: String? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false
)

@Serializable
data class SubtitleTrackInfo(
    val id: Long = 0,
    val mediaFileId: Long,
    val trackIndex: Int,
    val language: String? = null,
    val codec: String? = null,
    val isExternal: Boolean = false,
    val uri: String? = null,
    val title: String? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false
)

@Serializable
data class WatchState(
    val id: Long = 0,
    val mediaFileId: Long,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isCompleted: Boolean = false,
    val playCount: Int = 0,
    val lastWatchedAt: Long = System.currentTimeMillis(),
    val selectedAudioTrackIndex: Int? = null,
    val selectedSubtitleTrackIndex: Int? = null,
    val audioDelayMs: Long = 0,
    val subtitleDelayMs: Long = 0,
    val displayMode: String? = null
) {
    val progressPercentage: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
}
