package com.lecteur.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lecteur.core.model.MediaCategory

@Entity(
    tableName = "library_folders",
    indices = [
        // The same folder may be watched once per category (films, series, anime...), like Plex libraries
        Index(value = ["uri", "category"], unique = true),
        Index(value = ["uri"]),
        Index(value = ["category"])
    ]
)
data class LibraryFolderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uri: String,
    val displayPath: String,
    val category: MediaCategory,
    val enabled: Boolean = true,
    val lastScannedAt: Long? = null
)
