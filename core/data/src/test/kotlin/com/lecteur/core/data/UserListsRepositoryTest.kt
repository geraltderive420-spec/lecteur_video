package com.lecteur.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lecteur.core.common.lists.ListNameProblem
import com.lecteur.core.data.library.FavoritesRepository
import com.lecteur.core.data.library.ListResult
import com.lecteur.core.data.library.UserListsRepository
import com.lecteur.core.database.AppDatabase
import com.lecteur.core.database.entity.MovieEntity
import com.lecteur.core.database.entity.SeriesEntity
import com.lecteur.core.model.MediaKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class UserListsRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var favorites: FavoritesRepository
    private lateinit var repo: UserListsRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        favorites = FavoritesRepository(db.userListDao())
        repo = UserListsRepository(db.userListDao(), favorites)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun movie(title: String) = db.movieDao().insertMovie(MovieEntity(title = title))
    private suspend fun series(title: String) = db.seriesDao().insertSeries(SeriesEntity(title = title))

    private suspend fun created(name: String) = (repo.create(name) as ListResult.Done).listId

    @Test fun `create makes a list that appears empty`() = runTest {
        val id = created("  À voir  ")
        val list = repo.lists.first().single { it.id == id }
        assertThat(list.name).isEqualTo("À voir")
        assertThat(list.itemCount).isEqualTo(0)
        assertThat(list.isFavorites).isFalse()
    }

    @Test fun `favourites come first and cannot be shadowed`() = runTest {
        created("Zèbre")
        created("Alpha")
        favorites.listId()
        assertThat(repo.lists.first().map { it.name }).containsExactly("Favoris", "Alpha", "Zèbre").inOrder()
        assertThat((repo.create("favoris") as ListResult.Refused).problem).isEqualTo(ListNameProblem.RESERVED)
    }

    @Test fun `duplicate names are refused whatever the case`() = runTest {
        created("Noël")
        assertThat((repo.create("noël") as ListResult.Refused).problem).isEqualTo(ListNameProblem.DUPLICATE)
        assertThat((repo.create("  ") as ListResult.Refused).problem).isEqualTo(ListNameProblem.EMPTY)
    }

    @Test fun `adding to a list is idempotent and counts`() = runTest {
        val list = created("Culte")
        val film = movie("A")
        repo.setMember(MediaKind.MOVIE, film, list, true)
        repo.setMember(MediaKind.MOVIE, film, list, true)
        repo.setMember(MediaKind.SERIES, series("S"), list, true)
        assertThat(repo.lists.first().single { it.id == list }.itemCount).isEqualTo(2)
    }

    @Test fun `removing works and is also idempotent`() = runTest {
        val list = created("Culte")
        val film = movie("A")
        repo.setMember(MediaKind.MOVIE, film, list, true)
        repo.setMember(MediaKind.MOVIE, film, list, false)
        repo.setMember(MediaKind.MOVIE, film, list, false)
        assertThat(repo.lists.first().single { it.id == list }.itemCount).isEqualTo(0)
    }

    @Test fun `choices flag the lists holding the title and follow changes`() = runTest {
        val a = created("A")
        val b = created("B")
        val film = movie("F")
        repo.setMember(MediaKind.MOVIE, film, b, true)
        val choices = repo.choicesFor(MediaKind.MOVIE, film).first()
        assertThat(choices.associate { it.name to it.isMember }).containsExactly("Favoris", false, "A", false, "B", true)
        // The same ids on the series side do not leak: a series with the film's id is a different title.
        val show = series("S")
        assertThat(repo.choicesFor(MediaKind.SERIES, show).first().none { it.isMember }).isTrue()
        repo.setMember(MediaKind.MOVIE, film, a, true)
        assertThat(repo.choicesFor(MediaKind.MOVIE, film).first().filter { it.id == a || it.id == b }.all { it.isMember }).isTrue()
    }

    @Test fun `favourites toggled from the heart show up in the picker`() = runTest {
        val film = movie("F")
        favorites.toggleMovie(film)
        val choices = repo.choicesFor(MediaKind.MOVIE, film).first()
        assertThat(choices.single { it.name == "Favoris" }.isMember).isTrue()
    }

    @Test fun `rename keeps items, refuses clashes and refuses favourites`() = runTest {
        val a = created("A")
        created("B")
        val film = movie("F")
        repo.setMember(MediaKind.MOVIE, film, a, true)

        assertThat(repo.rename(a, "Alpha")).isEqualTo(ListResult.Done(a))
        assertThat(repo.lists.first().single { it.id == a }.itemCount).isEqualTo(1)
        // Changing only the capitals of its own name is allowed.
        assertThat(repo.rename(a, "ALPHA")).isEqualTo(ListResult.Done(a))
        assertThat((repo.rename(a, "b") as ListResult.Refused).problem).isEqualTo(ListNameProblem.DUPLICATE)
        assertThat((repo.rename(favorites.listId(), "Autre") as ListResult.Refused).problem).isEqualTo(ListNameProblem.RESERVED)
    }

    @Test fun `delete removes the list and its items but not the titles, favourites survive`() = runTest {
        val list = created("Temp")
        val film = movie("F")
        repo.setMember(MediaKind.MOVIE, film, list, true)
        repo.delete(list)
        assertThat(repo.lists.first().any { it.id == list }).isFalse()
        assertThat(db.movieDao().getMovieById(film)).isNotNull()

        val fav = favorites.listId()
        repo.delete(fav)
        assertThat(repo.lists.first().any { it.isFavorites }).isTrue()
    }

    @Test fun `createAndAdd puts the title in the new list and reports refusals`() = runTest {
        val film = movie("F")
        val result = repo.createAndAdd(MediaKind.MOVIE, film, "Nouvelle") as ListResult.Done
        assertThat(repo.choicesFor(MediaKind.MOVIE, film).first().single { it.id == result.listId }.isMember).isTrue()
        assertThat(repo.createAndAdd(MediaKind.MOVIE, film, "nouvelle")).isInstanceOf(ListResult.Refused::class.java)
    }
}
