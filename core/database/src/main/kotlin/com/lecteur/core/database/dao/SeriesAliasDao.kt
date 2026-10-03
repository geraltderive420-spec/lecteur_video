package com.lecteur.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lecteur.core.database.entity.SeriesAliasEntity

@Dao
interface SeriesAliasDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(alias: SeriesAliasEntity)

    /** Registers a name only if it is free: a generic key must not be stolen from the series that owns it. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putIfAbsent(alias: SeriesAliasEntity)

    @Query("SELECT seriesId FROM series_aliases WHERE alias = :alias")
    suspend fun findSeriesId(alias: String): Long?

    /** When two series are merged, the names of the absorbed one now lead to the survivor. */
    @Query("UPDATE series_aliases SET seriesId = :to WHERE seriesId = :from")
    suspend fun repoint(from: Long, to: Long)

    @Query("SELECT alias FROM series_aliases WHERE seriesId = :seriesId")
    suspend fun aliasesOf(seriesId: Long): List<String>
}
