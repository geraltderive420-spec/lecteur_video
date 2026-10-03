package com.lecteur.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lecteur.core.model.AudioCodec
import com.lecteur.core.model.HdrType
import com.lecteur.core.model.MatchState
import com.lecteur.core.model.VideoCodec

@Entity(
    tableName = "movies",
    indices = [
        Index(value = ["tmdbId"], unique = true),
        Index(value = ["imdbId"]),
        Index(value = ["title"]),
        Index(value = ["sortTitle"]),
        Index(value = ["year"]),
        Index(value = ["rating"]),
        Index(value = ["addedAt"]),
        Index(value = ["collectionId"]),
        Index(value = ["matchState"])
    ]
)
data class MovieEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val tmdbId: Long? = null,
    val imdbId: String? = null,
    val title: String,
    val originalTitle: String? = null,
    val sortTitle: String = title,
    val year: Int? = null,
    val releaseDate: String? = null,
    val overview: String? = null,
    val runtime: Int? = null,
    val rating: Float? = null,
    val certification: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val logoPath: String? = null,
    val trailerKey: String? = null,
    val collectionId: Long? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val matchState: MatchState = MatchState.UNIDENTIFIED,
    val matchLocked: Boolean = false,
    // Last online lookup (schema v3): stops a title TMDB does not know from being searched again at every scan
    val matchAttemptedAt: Long? = null
)

@Entity(
    tableName = "series",
    indices = [
        Index(value = ["tmdbId"], unique = true),
        Index(value = ["imdbId"]),
        Index(value = ["title"]),
        Index(value = ["sortTitle"]),
        Index(value = ["firstAirDate"]),
        Index(value = ["rating"]),
        Index(value = ["addedAt"]),
        Index(value = ["matchState"])
    ]
)
data class SeriesEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val tmdbId: Long? = null,
    val imdbId: String? = null,
    val title: String,
    val originalTitle: String? = null,
    val sortTitle: String = title,
    val firstAirDate: String? = null,
    val status: String? = null,
    val overview: String? = null,
    val rating: Float? = null,
    val certification: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val logoPath: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val matchState: MatchState = MatchState.UNIDENTIFIED,
    val matchLocked: Boolean = false,
    val matchAttemptedAt: Long? = null
)

/**
 * Names a series has been filed under (normalised file-name title, see SeriesKey), so a new episode of an
 * already identified series, or of a series the user corrected by hand, lands on the same row (schema v3).
 */
@Entity(
    tableName = "series_aliases",
    foreignKeys = [
        ForeignKey(
            entity = SeriesEntity::class,
            parentColumns = ["id"],
            childColumns = ["seriesId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["seriesId"])]
)
data class SeriesAliasEntity(
    @PrimaryKey
    val alias: String,
    val seriesId: Long
)

@Entity(
    tableName = "seasons",
    foreignKeys = [
        ForeignKey(
            entity = SeriesEntity::class,
            parentColumns = ["id"],
            childColumns = ["seriesId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["seriesId", "seasonNumber"], unique = true),
        Index(value = ["seriesId"])
    ]
)
data class SeasonEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val seriesId: Long,
    val seasonNumber: Int,
    val name: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val airDate: String? = null
)

@Entity(
    tableName = "episodes",
    foreignKeys = [
        ForeignKey(
            entity = SeriesEntity::class,
            parentColumns = ["id"],
            childColumns = ["seriesId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SeasonEntity::class,
            parentColumns = ["id"],
            childColumns = ["seasonId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["seriesId", "seasonId", "episodeNumber"]),
        Index(value = ["seasonId"]),
        Index(value = ["seriesId"])
    ]
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val seriesId: Long,
    val seasonId: Long,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val absoluteNumber: Int? = null,
    val title: String? = null,
    val overview: String? = null,
    val stillPath: String? = null,
    val airDate: String? = null,
    val runtime: Int? = null
)

@Entity(
    tableName = "media_files",
    foreignKeys = [
        ForeignKey(
            entity = LibraryFolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = MovieEntity::class,
            parentColumns = ["id"],
            childColumns = ["movieId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = EpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episodeId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["uri"], unique = true),
        Index(value = ["fingerprint"]),
        Index(value = ["folderId"]),
        Index(value = ["movieId"]),
        Index(value = ["episodeId"]),
        Index(value = ["isAvailable"]),
        Index(value = ["addedAt"])
    ]
)
data class MediaFileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val folderId: Long,
    val uri: String,
    val displayPath: String,
    val fileName: String,
    val size: Long,
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

@Entity(
    tableName = "audio_tracks",
    foreignKeys = [
        ForeignKey(
            entity = MediaFileEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaFileId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["mediaFileId", "trackIndex"], unique = true),
        Index(value = ["mediaFileId"])
    ]
)
data class AudioTrackInfoEntity(
    @PrimaryKey(autoGenerate = true)
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

@Entity(
    tableName = "subtitle_tracks",
    foreignKeys = [
        ForeignKey(
            entity = MediaFileEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaFileId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["mediaFileId", "trackIndex"], unique = true),
        Index(value = ["mediaFileId"])
    ]
)
data class SubtitleTrackInfoEntity(
    @PrimaryKey(autoGenerate = true)
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

@Entity(
    tableName = "watch_states",
    foreignKeys = [
        ForeignKey(
            entity = MediaFileEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaFileId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["mediaFileId"], unique = true),
        Index(value = ["lastWatchedAt"]),
        Index(value = ["isCompleted"])
    ]
)
data class WatchStateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val mediaFileId: Long,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isCompleted: Boolean = false,
    val playCount: Int = 0,
    val lastWatchedAt: Long = System.currentTimeMillis(),
    val selectedAudioTrack: Int? = null,
    val selectedSubtitleTrack: Int? = null,
    val audioDelayMs: Long = 0,
    val subtitleDelayMs: Long = 0,
    // Name of the player's DisplayMode, remembered per media (added in schema v2)
    val displayMode: String? = null
)
