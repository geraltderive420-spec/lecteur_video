package com.lecteur.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.lecteur.core.database.converter.AppTypeConverters
import com.lecteur.core.database.dao.EpisodeDao
import com.lecteur.core.database.dao.LibraryDao
import com.lecteur.core.database.dao.LibraryFolderDao
import com.lecteur.core.database.dao.MediaFileDao
import com.lecteur.core.database.dao.MetadataDao
import com.lecteur.core.database.dao.MovieDao
import com.lecteur.core.database.dao.SearchDao
import com.lecteur.core.database.dao.SeasonDao
import com.lecteur.core.database.dao.SeriesAliasDao
import com.lecteur.core.database.dao.SeriesDao
import com.lecteur.core.database.dao.UserListDao
import com.lecteur.core.database.dao.WatchStateDao
import com.lecteur.core.database.entity.AudioTrackInfoEntity
import com.lecteur.core.database.entity.CastMemberEntity
import com.lecteur.core.database.entity.CollectionEntity
import com.lecteur.core.database.entity.EpisodeEntity
import com.lecteur.core.database.entity.GenreEntity
import com.lecteur.core.database.entity.LibraryFolderEntity
import com.lecteur.core.database.entity.MediaFileEntity
import com.lecteur.core.database.entity.MovieEntity
import com.lecteur.core.database.entity.MovieFtsEntity
import com.lecteur.core.database.entity.MovieGenreCrossRef
import com.lecteur.core.database.entity.PersonEntity
import com.lecteur.core.database.entity.SeasonEntity
import com.lecteur.core.database.entity.SeriesAliasEntity
import com.lecteur.core.database.entity.SeriesEntity
import com.lecteur.core.database.entity.SeriesFtsEntity
import com.lecteur.core.database.entity.SeriesGenreCrossRef
import com.lecteur.core.database.entity.SeriesPreferenceEntity
import com.lecteur.core.database.entity.SubtitleTrackInfoEntity
import com.lecteur.core.database.entity.UserListEntity
import com.lecteur.core.database.entity.UserListItemEntity
import com.lecteur.core.database.entity.WatchStateEntity
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

@Database(
    entities = [
        LibraryFolderEntity::class,
        MovieEntity::class,
        SeriesEntity::class,
        SeriesAliasEntity::class,
        SeasonEntity::class,
        EpisodeEntity::class,
        MediaFileEntity::class,
        AudioTrackInfoEntity::class,
        SubtitleTrackInfoEntity::class,
        WatchStateEntity::class,
        PersonEntity::class,
        CastMemberEntity::class,
        GenreEntity::class,
        MovieGenreCrossRef::class,
        SeriesGenreCrossRef::class,
        CollectionEntity::class,
        UserListEntity::class,
        UserListItemEntity::class,
        SeriesPreferenceEntity::class,
        MovieFtsEntity::class,
        SeriesFtsEntity::class
    ],
    version = 4,
    exportSchema = true
)
@TypeConverters(AppTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun libraryFolderDao(): LibraryFolderDao
    abstract fun movieDao(): MovieDao
    abstract fun seriesDao(): SeriesDao
    abstract fun seasonDao(): SeasonDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun mediaFileDao(): MediaFileDao
    abstract fun watchStateDao(): WatchStateDao
    abstract fun searchDao(): SearchDao
    abstract fun metadataDao(): MetadataDao
    abstract fun seriesAliasDao(): SeriesAliasDao
    abstract fun libraryDao(): LibraryDao
    abstract fun userListDao(): UserListDao

    companion object {
        const val DATABASE_NAME = "lecteur_media.db"

        fun create(context: Context, inMemory: Boolean = false): AppDatabase {
            val builder = if (inMemory) {
                Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            } else {
                Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            }
            // No destructive fallback: the database holds the user's watch progress.
            return builder
                .addMigrations(*Migrations.ALL)
                .build()
        }

        /**
         * Export current database file to a destination file.
         */
        fun backupDatabase(context: Context, destFile: File): Boolean {
            val dbFile = context.getDatabasePath(DATABASE_NAME)
            if (!dbFile.exists()) return false

            return runCatching {
                FileInputStream(dbFile).use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                true
            }.getOrDefault(false)
        }

        /**
         * Restore database from a source backup file.
         */
        fun restoreDatabase(context: Context, sourceFile: File): Boolean {
            if (!sourceFile.exists()) return false
            val dbFile = context.getDatabasePath(DATABASE_NAME)

            return runCatching {
                FileInputStream(sourceFile).use { input ->
                    FileOutputStream(dbFile).use { output ->
                        input.copyTo(output)
                    }
                }
                true
            }.getOrDefault(false)
        }
    }
}
