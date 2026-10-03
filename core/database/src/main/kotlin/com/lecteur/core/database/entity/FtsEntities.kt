package com.lecteur.core.database.entity

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

@Entity(tableName = "movies_fts")
@Fts4(contentEntity = MovieEntity::class)
data class MovieFtsEntity(
    @PrimaryKey
    val rowid: Long,
    val title: String,
    val originalTitle: String?,
    val overview: String?
)

@Entity(tableName = "series_fts")
@Fts4(contentEntity = SeriesEntity::class)
data class SeriesFtsEntity(
    @PrimaryKey
    val rowid: Long,
    val title: String,
    val originalTitle: String?,
    val overview: String?
)
