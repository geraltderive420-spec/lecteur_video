package com.lecteur.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.lecteur.core.database.entity.LibraryFolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryFolderDao {

    @Upsert
    suspend fun insertFolder(folder: LibraryFolderEntity): Long

    @Update
    suspend fun updateFolder(folder: LibraryFolderEntity)

    @Query("DELETE FROM library_folders WHERE id = :id")
    suspend fun deleteFolder(id: Long)

    @Query("SELECT * FROM library_folders WHERE id = :id")
    suspend fun getFolderById(id: Long): LibraryFolderEntity?

    @Query("SELECT * FROM library_folders WHERE uri = :uri LIMIT 1")
    suspend fun getFolderByUri(uri: String): LibraryFolderEntity?

    /** Every entry watching this folder: one per category. */
    @Query("SELECT * FROM library_folders WHERE uri = :uri")
    suspend fun getFoldersByUri(uri: String): List<LibraryFolderEntity>

    @Query("SELECT * FROM library_folders ORDER BY displayPath ASC")
    fun getAllFolders(): Flow<List<LibraryFolderEntity>>

    @Query("UPDATE library_folders SET lastScannedAt = :at WHERE id = :id")
    suspend fun setLastScanned(id: Long, at: Long)

    @Query("UPDATE library_folders SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE library_folders SET category = :category WHERE id = :id")
    suspend fun setCategory(id: Long, category: com.lecteur.core.model.MediaCategory)

    /** Folders shown to the user: the hidden pseudo-folder of manually opened files is not one of them. */
    @Query("SELECT * FROM library_folders WHERE uri != 'lecteur://manual' ORDER BY displayPath ASC")
    fun observeUserFolders(): Flow<List<LibraryFolderEntity>>

    @Query("SELECT * FROM library_folders WHERE uri != 'lecteur://manual'")
    suspend fun getUserFolders(): List<LibraryFolderEntity>

    @Query("SELECT * FROM library_folders WHERE enabled = 1")
    suspend fun getActiveFolders(): List<LibraryFolderEntity>
}
