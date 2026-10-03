package com.lecteur.core.data.library

import com.lecteur.core.common.lists.ListNameCheck
import com.lecteur.core.common.lists.ListNameProblem
import com.lecteur.core.common.lists.ListNames
import com.lecteur.core.database.dao.UserListDao
import com.lecteur.core.database.entity.UserListEntity
import com.lecteur.core.database.entity.UserListItemEntity
import com.lecteur.core.model.ListChoice
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.UserListSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ListResult {
    data class Done(val listId: Long) : ListResult
    data class Refused(val problem: ListNameProblem) : ListResult
}

/**
 * The user's own lists. Favourites are one of them (see [FavoritesRepository]) but cannot be renamed or deleted, and no
 * other list can take their name.
 */
@Singleton
class UserListsRepository @Inject constructor(
    private val dao: UserListDao,
    private val favorites: FavoritesRepository
) {
    /** Serialises writes so two quick taps cannot create the same list twice or add a title twice. */
    private val writes = Mutex()

    /** Every list with its size, favourites first. */
    val lists: Flow<List<UserListSummary>> = dao.observeSummaries().map { rows ->
        rows.map { UserListSummary(it.id, it.name, it.itemCount, isFavorites = it.name == FavoritesRepository.NAME) }
            .sortedWith(compareByDescending<UserListSummary> { it.isFavorites })
    }

    /** The picker's content for one title: all lists, each flagged when the title is in it. */
    fun choicesFor(kind: MediaKind, id: Long): Flow<List<ListChoice>> {
        val memberOf = if (kind == MediaKind.MOVIE) dao.observeListsOfMovie(id) else dao.observeListsOfSeries(id)
        return combine(lists, memberOf) { lists, member ->
            val ids = member.toSet()
            lists.map { ListChoice(it.id, it.name, it.id in ids) }
        }
    }

    suspend fun create(rawName: String): ListResult = writes.withLock {
        favorites.listId()
        val existing = dao.observeSummaries().first().map { it.name }
        when (val check = ListNames.check(rawName, existing, FavoritesRepository.NAME)) {
            is ListNameCheck.Invalid -> ListResult.Refused(check.problem)
            is ListNameCheck.Valid -> ListResult.Done(dao.insertList(UserListEntity(name = check.name)))
        }
    }

    suspend fun rename(listId: Long, rawName: String): ListResult = writes.withLock {
        val all = dao.observeSummaries().first()
        if (all.firstOrNull { it.id == listId }?.name == FavoritesRepository.NAME) {
            return@withLock ListResult.Refused(ListNameProblem.RESERVED)
        }
        when (val check = ListNames.check(rawName, all.filter { it.id != listId }.map { it.name }, FavoritesRepository.NAME)) {
            is ListNameCheck.Invalid -> ListResult.Refused(check.problem)
            is ListNameCheck.Valid -> {
                dao.renameList(listId, check.name)
                ListResult.Done(listId)
            }
        }
    }

    /** Favourites are refused silently: the screen does not offer the action for them. */
    suspend fun delete(listId: Long) {
        writes.withLock {
        val name = dao.observeSummaries().first().firstOrNull { it.id == listId }?.name
        if (name != null && name != FavoritesRepository.NAME) dao.deleteList(listId)
        }
    }

    suspend fun setMember(kind: MediaKind, id: Long, listId: Long, member: Boolean) {
        writes.withLock {
        val present = if (kind == MediaKind.MOVIE) dao.hasMovie(listId, id) else dao.hasSeries(listId, id)
        when {
            member && !present -> dao.insertItem(
                if (kind == MediaKind.MOVIE) UserListItemEntity(listId = listId, movieId = id) else UserListItemEntity(listId = listId, seriesId = id)
            )
            !member && present -> if (kind == MediaKind.MOVIE) dao.removeMovie(listId, id) else dao.removeSeries(listId, id)
        }
        }
    }

    /** Creates a list and puts the title in it: the "new list" line of the picker. */
    suspend fun createAndAdd(kind: MediaKind, id: Long, rawName: String): ListResult {
        val result = create(rawName)
        if (result is ListResult.Done) setMember(kind, id, result.listId, true)
        return result
    }
}
