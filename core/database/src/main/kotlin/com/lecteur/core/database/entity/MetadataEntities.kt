package com.lecteur.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "persons",
    indices = [
        Index(value = ["tmdbId"], unique = true),
        Index(value = ["name"])
    ]
)
data class PersonEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val tmdbId: Long? = null,
    val name: String,
    val profilePath: String? = null
)

@Entity(
    tableName = "cast_members",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = MovieEntity::class,
            parentColumns = ["id"],
            childColumns = ["movieId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SeriesEntity::class,
            parentColumns = ["id"],
            childColumns = ["seriesId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["personId"]),
        Index(value = ["movieId"]),
        Index(value = ["seriesId"]),
        Index(value = ["isDirector"])
    ]
)
data class CastMemberEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val personId: Long,
    val personName: String,
    val profilePath: String? = null,
    val movieId: Long? = null,
    val seriesId: Long? = null,
    val character: String? = null,
    val order: Int = 0,
    val isDirector: Boolean = false
)

@Entity(
    tableName = "genres",
    indices = [
        Index(value = ["tmdbId"], unique = true),
        Index(value = ["name"], unique = true)
    ]
)
data class GenreEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val tmdbId: Long? = null,
    val name: String
)

@Entity(
    tableName = "movie_genres",
    primaryKeys = ["movieId", "genreId"],
    foreignKeys = [
        ForeignKey(
            entity = MovieEntity::class,
            parentColumns = ["id"],
            childColumns = ["movieId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = GenreEntity::class,
            parentColumns = ["id"],
            childColumns = ["genreId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["movieId"]),
        Index(value = ["genreId"])
    ]
)
data class MovieGenreCrossRef(
    val movieId: Long,
    val genreId: Long
)

@Entity(
    tableName = "series_genres",
    primaryKeys = ["seriesId", "genreId"],
    foreignKeys = [
        ForeignKey(
            entity = SeriesEntity::class,
            parentColumns = ["id"],
            childColumns = ["seriesId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = GenreEntity::class,
            parentColumns = ["id"],
            childColumns = ["genreId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["seriesId"]),
        Index(value = ["genreId"])
    ]
)
data class SeriesGenreCrossRef(
    val seriesId: Long,
    val genreId: Long
)

@Entity(
    tableName = "collections",
    indices = [
        Index(value = ["tmdbId"], unique = true),
        Index(value = ["name"])
    ]
)
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val tmdbId: Long? = null,
    val name: String,
    val overview: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null
)

@Entity(
    tableName = "user_lists",
    indices = [
        Index(value = ["name"], unique = true)
    ]
)
data class UserListEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "user_list_items",
    foreignKeys = [
        ForeignKey(
            entity = UserListEntity::class,
            parentColumns = ["id"],
            childColumns = ["listId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = MovieEntity::class,
            parentColumns = ["id"],
            childColumns = ["movieId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SeriesEntity::class,
            parentColumns = ["id"],
            childColumns = ["seriesId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["listId"]),
        Index(value = ["movieId"]),
        Index(value = ["seriesId"])
    ]
)
data class UserListItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val listId: Long,
    val movieId: Long? = null,
    val seriesId: Long? = null,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "series_preferences",
    primaryKeys = ["seriesId"],
    foreignKeys = [
        ForeignKey(
            entity = SeriesEntity::class,
            parentColumns = ["id"],
            childColumns = ["seriesId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class SeriesPreferenceEntity(
    val seriesId: Long,
    val preferredAudioLanguage: String? = null,
    val preferredSubtitleLanguage: String? = null
)
