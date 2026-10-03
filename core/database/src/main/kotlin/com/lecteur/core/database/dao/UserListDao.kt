package com.lecteur.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lecteur.core.database.entity.UserListEntity
import com.lecteur.core.database.entity.UserListItemEntity
import kotlinx.coroutines.flow.Flow

/**
 * Lists of titles (favourites today, personal lists in phase 6). Not folded into MetadataDao: that one inserts lists with
 * REPLACE, which on a name clash deletes the old row and, through the cascade, every title in it.
 */
@Dao
interface UserListDao {

    /** Returns -1 when a list of that name already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertList(list: UserListEntity): Long

    @Query("SELECT id FROM user_lists WHERE name = :name")
    suspend fun findListId(name: String): Long?

    @Query("SELECT * FROM user_lists ORDER BY name COLLATE NOCASE")
    fun observeLists(): Flow<List<UserListEntity>>

    @Insert
    suspend fun insertItem(item: UserListItemEntity): Long

    @Query("SELECT EXISTS (SELECT 1 FROM user_list_items WHERE listId = :listId AND movieId = :movieId)")
    suspend fun hasMovie(listId: Long, movieId: Long): Boolean

    @Query("SELECT EXISTS (SELECT 1 FROM user_list_items WHERE listId = :listId AND seriesId = :seriesId)")
    suspend fun hasSeries(listId: Long, seriesId: Long): Boolean

    @Query("SELECT EXISTS (SELECT 1 FROM user_list_items WHERE listId = :listId AND movieId = :movieId)")
    fun observeHasMovie(listId: Long, movieId: Long): Flow<Boolean>

    @Query("SELECT EXISTS (SELECT 1 FROM user_list_items WHERE listId = :listId AND seriesId = :seriesId)")
    fun observeHasSeries(listId: Long, seriesId: Long): Flow<Boolean>

    @Query("DELETE FROM user_list_items WHERE listId = :listId AND movieId = :movieId")
    suspend fun removeMovie(listId: Long, movieId: Long)

    @Query("DELETE FROM user_list_items WHERE listId = :listId AND seriesId = :seriesId")
    suspend fun removeSeries(listId: Long, seriesId: Long)
}
