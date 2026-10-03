package com.lecteur.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.lecteur.core.database.entity.MovieEntity
import com.lecteur.core.database.entity.SeriesEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchDao {

    @Query(
        """
        SELECT movies.* FROM movies
        JOIN movies_fts ON movies.id = movies_fts.rowid
        WHERE movies_fts MATCH :query
        ORDER BY movies.title ASC
        """
    )
    fun searchMovies(query: String): Flow<List<MovieEntity>>

    @Query(
        """
        SELECT series.* FROM series
        JOIN series_fts ON series.id = series_fts.rowid
        WHERE series_fts MATCH :query
        ORDER BY series.title ASC
        """
    )
    fun searchSeries(query: String): Flow<List<SeriesEntity>>
}
