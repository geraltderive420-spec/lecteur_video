package com.lecteur.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lecteur.core.data.library.ContinueItem
import com.lecteur.core.data.library.FolderRepository
import com.lecteur.core.data.library.HomeRepository
import com.lecteur.core.data.library.ItemActions
import com.lecteur.core.data.library.ListPickerModel
import com.lecteur.core.data.library.UserListsRepository
import com.lecteur.core.data.library.NextUpItem
import com.lecteur.core.data.playback.PlayPlanner
import com.lecteur.core.data.settings.HomeLayoutRepository
import com.lecteur.core.model.HomeRow
import com.lecteur.core.model.LibraryItem
import com.lecteur.core.model.LibrarySection
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.PlayPlan
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUi(
    /** The rows to show, in the order the user chose. */
    val rows: List<HomeRow> = emptyList(),
    val continueItems: List<ContinueItem> = emptyList(),
    val nextUp: List<NextUpItem> = emptyList(),
    val recent: List<LibraryItem> = emptyList(),
    val movies: List<LibraryItem> = emptyList(),
    val series: List<LibraryItem> = emptyList(),
    val unwatched: List<LibraryItem> = emptyList(),
    val favorites: List<LibraryItem> = emptyList(),
    val folderCount: Int = 0,
    val fileCount: Int = 0,
    val loaded: Boolean = false
) {
    fun itemsOf(row: HomeRow): List<LibraryItem> = when (row) {
        HomeRow.RECENT -> recent
        HomeRow.MOVIES -> movies
        HomeRow.SERIES -> series
        HomeRow.UNWATCHED -> unwatched
        HomeRow.FAVORITES -> favorites
        HomeRow.CONTINUE, HomeRow.NEXT_UP -> emptyList()
    }

    /** True when a row has something to show: empty rows are left out rather than shown as headings over nothing. */
    fun hasContent(row: HomeRow): Boolean = when (row) {
        HomeRow.CONTINUE -> continueItems.isNotEmpty()
        HomeRow.NEXT_UP -> nextUp.isNotEmpty()
        else -> itemsOf(row).isNotEmpty()
    }
}

sealed interface HomeEvent {
    data class Play(val plan: PlayPlan) : HomeEvent
    data class Message(val text: String) : HomeEvent
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    home: HomeRepository,
    layout: HomeLayoutRepository,
    folders: FolderRepository,
    private val planner: PlayPlanner,
    private val actions: ItemActions,
    lists: UserListsRepository
) : ViewModel() {

    val listPicker = ListPickerModel(lists, viewModelScope)

    fun addToList(item: LibraryItem) = listPicker.open(item.kind, item.id, item.title)

    private data class Top(val continueItems: List<ContinueItem>, val nextUp: List<NextUpItem>, val recent: List<LibraryItem>)
    private data class Shelves(val movies: List<LibraryItem>, val series: List<LibraryItem>, val unwatched: List<LibraryItem>, val favorites: List<LibraryItem>)

    private val top = combine(home.continueWatching, home.nextUp, home.recentlyAdded, ::Top)
    private val shelves = combine(home.shelf(LibrarySection.MOVIES), home.shelf(LibrarySection.SERIES), home.unwatched, home.favoriteTitles, ::Shelves)

    val state: StateFlow<HomeUi> = combine(layout.layout, top, shelves, folders.folders) { layout, top, shelves, folders ->
        HomeUi(
            rows = layout.visibleRows,
            continueItems = top.continueItems,
            nextUp = top.nextUp,
            recent = top.recent,
            movies = shelves.movies,
            series = shelves.series,
            unwatched = shelves.unwatched,
            favorites = shelves.favorites,
            folderCount = folders.size,
            fileCount = folders.sumOf { it.fileCount },
            loaded = true
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())

    private val eventChannel = Channel<HomeEvent>(Channel.BUFFERED)
    val events: Flow<HomeEvent> = eventChannel.receiveAsFlow()

    /** An episode in progress resumes and carries on with the series; a film or an unidentified file plays alone. */
    fun play(item: ContinueItem) = launchPlan("Ce fichier n'est pas disponible : le support est peut-être débranché.") {
        val episodeId = item.episodeId
        val seriesId = item.titleId
        if (item.kind == MediaKind.SERIES && episodeId != null && seriesId != null) planner.forEpisode(seriesId, episodeId, item.mediaFileId)
        else planner.forFile(item.mediaFileId)
    }

    fun play(item: NextUpItem) = launchPlan("Cet épisode n'est pas disponible : le support est peut-être débranché.") {
        planner.forEpisode(item.seriesId, item.episodeId, item.mediaFileId)
    }

    fun play(item: LibraryItem) = launchPlan("Aucun fichier de « ${item.title} » n'est disponible : le support est peut-être débranché.") {
        actions.play(item)
    }

    fun setWatched(item: LibraryItem, watched: Boolean) {
        viewModelScope.launch { actions.setWatched(item, watched) }
    }

    fun toggleFavorite(item: LibraryItem) {
        viewModelScope.launch {
            val nowFavorite = actions.toggleFavorite(item)
            eventChannel.send(HomeEvent.Message(if (nowFavorite) "« ${item.title} » ajouté aux favoris" else "« ${item.title} » retiré des favoris"))
        }
    }

    suspend fun isFavorite(item: LibraryItem): Boolean = actions.isFavorite(item)

    private fun launchPlan(unavailable: String, plan: suspend () -> PlayPlan?) {
        viewModelScope.launch {
            val result = plan()
            eventChannel.send(if (result != null) HomeEvent.Play(result) else HomeEvent.Message(unavailable))
        }
    }
}
