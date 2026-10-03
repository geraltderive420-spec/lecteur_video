package com.lecteur.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.lecteur.core.database.entity.AudioTrackInfoEntity
import com.lecteur.core.database.entity.MediaFileEntity
import com.lecteur.core.database.entity.SubtitleTrackInfoEntity
import com.lecteur.core.database.entity.WatchStateEntity
import com.lecteur.core.database.relation.MediaFileWithTracks
import kotlinx.coroutines.flow.Flow

data class FolderFileCount(val folderId: Long, val fileCount: Int, val availableCount: Int)

/** Where the user stands in one file, keyed by its uri: lets the folder explorer show progress without loading files one by one. */
data class FileProgressRow(val uri: String, val completed: Boolean, val positionMs: Long, val durationMs: Long)

@Dao
interface MediaFileDao {

    // @Upsert (not REPLACE): REPLACE deletes the row first and cascades to watch_states / tracks.
    // Callers must resolve an existing row by uri/fingerprint first (uri is unique).
    @Upsert
    suspend fun insertMediaFile(file: MediaFileEntity): Long

    @Upsert
    suspend fun insertMediaFiles(files: List<MediaFileEntity>): List<Long>

    /**
     * Scanner entry point: inserts a new file or updates the row already known for this uri,
     * keeping its id (and therefore its watch state and tracks). Plain [insertMediaFile] cannot
     * do this: on a uri clash with a different id, @Upsert silently drops the row.
     */
    @Transaction
    suspend fun saveScannedFile(file: MediaFileEntity): Long {
        val existing = getMediaFileByUri(file.uri)
        return insertMediaFile(if (existing != null) file.copy(id = existing.id) else file)
            .let { if (it == -1L) existing!!.id else it }
    }

    @Update
    suspend fun updateMediaFile(file: MediaFileEntity)

    @Query("DELETE FROM media_files WHERE id = :id")
    suspend fun deleteMediaFile(id: Long)

    @Query("SELECT * FROM media_files WHERE id = :id")
    suspend fun getMediaFileById(id: Long): MediaFileEntity?

    @Query("SELECT * FROM media_files WHERE uri = :uri")
    suspend fun getMediaFileByUri(uri: String): MediaFileEntity?

    @Query("SELECT * FROM media_files WHERE fingerprint = :fingerprint")
    suspend fun getMediaFileByFingerprint(fingerprint: String): MediaFileEntity?

    @Query("SELECT * FROM media_files WHERE folderId = :folderId")
    suspend fun getMediaFilesByFolder(folderId: Long): List<MediaFileEntity>

    @Query("SELECT * FROM media_files WHERE movieId = :movieId")
    suspend fun getMediaFilesForMovie(movieId: Long): List<MediaFileEntity>

    @Query("SELECT * FROM media_files WHERE episodeId = :episodeId")
    suspend fun getMediaFilesForEpisode(episodeId: Long): List<MediaFileEntity>

    @Transaction
    @Query("SELECT * FROM media_files WHERE id = :id")
    suspend fun getMediaFileWithTracks(id: Long): MediaFileWithTracks?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudioTracks(tracks: List<AudioTrackInfoEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubtitleTracks(tracks: List<SubtitleTrackInfoEntity>)

    @Query("DELETE FROM audio_tracks WHERE mediaFileId = :mediaFileId")
    suspend fun clearAudioTracks(mediaFileId: Long)

    @Query("DELETE FROM subtitle_tracks WHERE mediaFileId = :mediaFileId")
    suspend fun clearSubtitleTracks(mediaFileId: Long)

    @Query("UPDATE media_files SET isAvailable = :available WHERE folderId = :folderId")
    suspend fun setFolderAvailability(folderId: Long, available: Boolean)

    @Query("UPDATE media_files SET isAvailable = :available WHERE id IN (:ids)")
    suspend fun setAvailability(ids: List<Long>, available: Boolean)

    /** A moved or renamed file keeps its row (and so its progress): only the location changes. */
    @Query(
        """
        UPDATE media_files SET uri = :uri, displayPath = :displayPath, fileName = :fileName,
            lastModified = :lastModified, size = :size, isAvailable = 1
        WHERE id = :id
        """
    )
    suspend fun relocate(id: Long, uri: String, displayPath: String, fileName: String, lastModified: Long, size: Long)

    /** Detaches a folder's files from their movie/episode so the next scan files them again (after a category change). */
    @Query("UPDATE media_files SET movieId = NULL, episodeId = NULL WHERE folderId = :folderId")
    suspend fun unlinkFolder(folderId: Long)

    /** A file claimed by another entry of the same folder (the category that fits it better). */
    @Query("UPDATE media_files SET folderId = :folderId WHERE id = :id")
    suspend fun reassignFolder(id: Long, folderId: Long)

    @Query("UPDATE media_files SET movieId = :to WHERE movieId = :from")
    suspend fun reassignMovie(from: Long, to: Long)

    @Query("UPDATE media_files SET episodeId = :to WHERE id = :mediaFileId")
    suspend fun setEpisode(mediaFileId: Long, to: Long?)

    @Query("UPDATE media_files SET movieId = :movieId, episodeId = :episodeId WHERE id = :mediaFileId")
    suspend fun setLinks(mediaFileId: Long, movieId: Long?, episodeId: Long?)

    @Query("SELECT * FROM media_files WHERE episodeId IN (SELECT id FROM episodes WHERE seriesId = :seriesId)")
    suspend fun getFilesForSeries(seriesId: Long): List<MediaFileEntity>

    @Query("SELECT folderId, COUNT(*) AS fileCount, SUM(CASE WHEN isAvailable = 1 THEN 1 ELSE 0 END) AS availableCount FROM media_files GROUP BY folderId")
    fun observeFolderCounts(): Flow<List<FolderFileCount>>

    @Query(
        """
        SELECT f.uri AS uri, COALESCE(w.isCompleted, 0) AS completed, COALESCE(w.positionMs, 0) AS positionMs,
            COALESCE(w.durationMs, 0) AS durationMs
        FROM media_files f LEFT JOIN watch_states w ON w.mediaFileId = f.id
        WHERE f.folderId IN (:folderIds)
        """
    )
    suspend fun progressForFolders(folderIds: List<Long>): List<FileProgressRow>

    @Query("SELECT * FROM audio_tracks WHERE mediaFileId = :mediaFileId ORDER BY trackIndex")
    suspend fun getAudioTracks(mediaFileId: Long): List<AudioTrackInfoEntity>

    @Query("SELECT * FROM subtitle_tracks WHERE mediaFileId = :mediaFileId ORDER BY trackIndex")
    suspend fun getSubtitleTracks(mediaFileId: Long): List<SubtitleTrackInfoEntity>
}

@Dao
interface WatchStateDao {

    // Leaf table with a unique index on mediaFileId: REPLACE is safe here (no children) and resolves that conflict
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWatchState(state: WatchStateEntity): Long

    @Query("SELECT * FROM watch_states WHERE mediaFileId = :mediaFileId")
    suspend fun getWatchState(mediaFileId: Long): WatchStateEntity?

    @Query("SELECT * FROM watch_states WHERE mediaFileId = :mediaFileId")
    fun observeWatchState(mediaFileId: Long): Flow<WatchStateEntity?>

    // Items currently in progress (position > 2% and not completed) for the "Resume" home row
    @Query(
        """
        SELECT * FROM watch_states 
        WHERE isCompleted = 0 
          AND positionMs > (durationMs * 0.02) 
          AND positionMs < (durationMs * 0.90)
        ORDER BY lastWatchedAt DESC 
        LIMIT :limit
        """
    )
    fun getInProgressWatchStates(limit: Int = 20): Flow<List<WatchStateEntity>>

    @Query("DELETE FROM watch_states WHERE mediaFileId = :mediaFileId")
    suspend fun deleteWatchState(mediaFileId: Long)
}
