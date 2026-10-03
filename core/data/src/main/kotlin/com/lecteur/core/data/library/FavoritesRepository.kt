package com.lecteur.core.data.library

import com.lecteur.core.database.dao.UserListDao
import com.lecteur.core.database.entity.UserListEntity
import com.lecteur.core.database.entity.UserListItemEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Favourites are an ordinary user list with a reserved name, so the personal lists of phase 6 can show them like any other. */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FavoritesRepository @Inject constructor(
    private val dao: UserListDao
) {
    private val creation = Mutex()

    /** A double tap must not insert the title twice. */
    private val toggling = Mutex()

    /** Id of the favourites list, created the first time it is needed. */
    suspend fun listId(): Long = creation.withLock {
        dao.findListId(NAME) ?: run {
            dao.insertList(UserListEntity(name = NAME))
            dao.findListId(NAME) ?: error("The favourites list could not be created")
        }
    }

    fun observeMovie(movieId: Long): Flow<Boolean> =
        flow { emit(listId()) }.flatMapLatest { dao.observeHasMovie(it, movieId) }

    fun observeSeries(seriesId: Long): Flow<Boolean> =
        flow { emit(listId()) }.flatMapLatest { dao.observeHasSeries(it, seriesId) }

    /** Returns whether the film is a favourite afterwards. */
    suspend fun toggleMovie(movieId: Long): Boolean = toggling.withLock {
        val list = listId()
        if (dao.hasMovie(list, movieId)) {
            dao.removeMovie(list, movieId)
            false
        } else {
            dao.insertItem(UserListItemEntity(listId = list, movieId = movieId))
            true
        }
    }

    suspend fun toggleSeries(seriesId: Long): Boolean = toggling.withLock {
        val list = listId()
        if (dao.hasSeries(list, seriesId)) {
            dao.removeSeries(list, seriesId)
            false
        } else {
            dao.insertItem(UserListItemEntity(listId = list, seriesId = seriesId))
            true
        }
    }

    companion object {
        const val NAME = "Favoris"
    }
}
