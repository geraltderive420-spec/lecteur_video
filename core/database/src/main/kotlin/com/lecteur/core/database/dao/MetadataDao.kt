package com.lecteur.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.lecteur.core.database.entity.CastMemberEntity
import com.lecteur.core.database.entity.CollectionEntity
import com.lecteur.core.database.entity.GenreEntity
import com.lecteur.core.database.entity.MovieGenreCrossRef
import com.lecteur.core.database.entity.PersonEntity
import com.lecteur.core.database.entity.SeriesGenreCrossRef
import com.lecteur.core.database.entity.SeriesPreferenceEntity
import com.lecteur.core.database.entity.UserListEntity
import com.lecteur.core.database.entity.UserListItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MetadataDao {

    // Genres
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGenres(genres: List<GenreEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMovieGenreCrossRef(crossRef: MovieGenreCrossRef)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSeriesGenreCrossRef(crossRef: SeriesGenreCrossRef)

    @Query("SELECT * FROM genres ORDER BY name ASC")
    fun getAllGenres(): Flow<List<GenreEntity>>

    // Persons & Cast
    @Upsert
    suspend fun insertPerson(person: PersonEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCastMembers(cast: List<CastMemberEntity>)

    @Query("SELECT * FROM cast_members WHERE movieId = :movieId ORDER BY `order` ASC")
    suspend fun getCastForMovie(movieId: Long): List<CastMemberEntity>

    @Query("SELECT * FROM cast_members WHERE seriesId = :seriesId ORDER BY `order` ASC")
    suspend fun getCastForSeries(seriesId: Long): List<CastMemberEntity>

    // Collections
    @Upsert
    suspend fun insertCollection(collection: CollectionEntity): Long

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun getCollectionById(id: Long): CollectionEntity?

    // User Lists
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUserList(list: UserListEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUserListItem(item: UserListItemEntity): Long

    @Query("SELECT * FROM user_lists ORDER BY name ASC")
    fun getAllUserLists(): Flow<List<UserListEntity>>

    @Query("SELECT * FROM user_list_items WHERE listId = :listId")
    suspend fun getListItems(listId: Long): List<UserListItemEntity>

    // Series Preferences
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSeriesPreference(preference: SeriesPreferenceEntity)

    @Query("SELECT * FROM series_preferences WHERE seriesId = :seriesId")
    suspend fun getSeriesPreference(seriesId: Long): SeriesPreferenceEntity?

    @Query("SELECT * FROM series_preferences WHERE seriesId = :seriesId")
    fun observeSeriesPreference(seriesId: Long): Flow<SeriesPreferenceEntity?>

    // Idempotent replacement of a title's genres and cast when its metadata is refreshed or corrected
    @Query("SELECT * FROM genres WHERE tmdbId = :tmdbId")
    suspend fun getGenreByTmdbId(tmdbId: Long): GenreEntity?

    @Query("SELECT * FROM genres WHERE name = :name")
    suspend fun getGenreByName(name: String): GenreEntity?

    @Insert
    suspend fun insertGenre(genre: GenreEntity): Long

    @Query("DELETE FROM movie_genres WHERE movieId = :movieId")
    suspend fun clearMovieGenres(movieId: Long)

    @Query("DELETE FROM series_genres WHERE seriesId = :seriesId")
    suspend fun clearSeriesGenres(seriesId: Long)

    @Query("DELETE FROM cast_members WHERE movieId = :movieId")
    suspend fun clearCastForMovie(movieId: Long)

    @Query("DELETE FROM cast_members WHERE seriesId = :seriesId")
    suspend fun clearCastForSeries(seriesId: Long)

    @Query("SELECT * FROM persons WHERE tmdbId = :tmdbId")
    suspend fun getPersonByTmdbId(tmdbId: Long): PersonEntity?

    @Insert
    suspend fun insertNewPerson(person: PersonEntity): Long

    @Query("SELECT * FROM collections WHERE tmdbId = :tmdbId")
    suspend fun getCollectionByTmdbId(tmdbId: Long): CollectionEntity?

    @Query("SELECT genres.* FROM genres JOIN movie_genres ON genres.id = movie_genres.genreId WHERE movie_genres.movieId = :movieId")
    suspend fun getGenresForMovie(movieId: Long): List<GenreEntity>

    @Query("SELECT genres.* FROM genres JOIN series_genres ON genres.id = series_genres.genreId WHERE series_genres.seriesId = :seriesId")
    suspend fun getGenresForSeries(seriesId: Long): List<GenreEntity>
}
