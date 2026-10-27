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
import com.lecteur.core.data.shared.CatalogState
import com.lecteur.core.data.shared.SharedLibraryRepository
import com.lecteur.core.data.shared.SharedQueries
import com.lecteur.core.data.shared.catalogOrNull
import com.lecteur.core.model.HomeRow
import com.lecteur.core.model.LibraryItem
import com.lecteur.core.model.LibraryQuery
import com.lecteur.core.model.LibrarySection
import com.lecteur.core.model.LibrarySort
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.PlayPlan
import com.lecteur.core.model.SortField
import com.lecteur.core.model.SortOrder
import com.lecteur.core.model.WatchStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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
    val loaded: Boolean = false,
    /** The PC whose library is shown; null for this phone's. */
    val source: String? = null,
    /** The PC does not answer and no catalog of it was read yet. */
    val sourceError: String? = null
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

/**
 * The home of the library on display: this phone's, or a paired PC's ([bind]). Both fill the same rows, in the order the
 * user chose; a PC's titles stream from the PC and keep the PC's progress.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    home: HomeRepository,
    private val layout: HomeLayoutRepository,
    folders: FolderRepository,
    private val planner: PlayPlanner,
    private val actions: ItemActions,
    lists: UserListsRepository,
    private val shared: SharedLibraryRepository
) : ViewModel() {

    val listPicker = ListPickerModel(lists, viewModelScope)

    fun addToList(item: LibraryItem) = listPicker.open(item.kind, item.id, item.title)

    private data class Top(val continueItems: List<ContinueItem>, val nextUp: List<NextUpItem>, val recent: List<LibraryItem>)
    private data class Shelves(val movies: List<LibraryItem>, val series: List<LibraryItem>, val unwatched: List<LibraryItem>, val favorites: List<LibraryItem>)

    private val top = combine(home.continueWatching, home.nextUp, home.recentlyAdded, ::Top)
    private val shelves = combine(home.shelf(LibrarySection.MOVIES), home.shelf(LibrarySection.SERIES), home.unwatched, home.favoriteTitles, ::Shelves)

    private val local: Flow<HomeUi> = combine(layout.layout, top, shelves, folders.folders) { layout, top, shelves, folders ->
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
    }

    /** The PC on display, null for this phone. */
    private val serverId = MutableStateFlow<String?>(null)

    val state: StateFlow<HomeUi> = serverId.flatMapLatest { id -> if (id == null) local else remote(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())

    private val eventChannel = Channel<HomeEvent>(Channel.BUFFERED)
    val events: Flow<HomeEvent> = eventChannel.receiveAsFlow()

    /** Shows a PC's library ([id]) or this phone's (null); called by the screen with the library chosen. */
    fun bind(id: String?) {
        serverId.value = id
    }

    /** Reads the PC's catalog again (a PC that did not answer, progress made elsewhere). */
    fun refreshSource() {
        serverId.value?.let(shared::refresh)
    }

    /** A PC's rows: the same as this phone's, computed from its catalog (no favourites: they belong to this phone). */
    private fun remote(id: String): Flow<HomeUi> {
        val mapping = shared.mapping(id)
        return combine(layout.layout, shared.observe(id), shared.servers) { layout, state, servers ->
            val name = servers.firstOrNull { it.serverId == id }?.name ?: "PC"
            val catalog = state.catalogOrNull
            when {
                catalog != null -> {
                    fun shelf(section: LibrarySection, sort: LibrarySort = LibrarySort(SortField.ADDED, SortOrder.DESC)) =
                        SharedQueries.items(catalog, mapping, LibraryQuery(section, sort, limit = ROW_SIZE))
                    val movies = shelf(LibrarySection.MOVIES)
                    val series = shelf(LibrarySection.SERIES)
                    HomeUi(
                        rows = layout.visibleRows,
                        continueItems = mapping.continueItems(catalog),
                        nextUp = mapping.nextUpItems(catalog),
                        recent = (movies + series).sortedByDescending { it.addedAt }.take(ROW_SIZE),
                        movies = movies,
                        series = series,
                        unwatched = SharedQueries.items(catalog, mapping, LibraryQuery(LibrarySection.MOVIES, LibrarySort(SortField.ADDED, SortOrder.DESC)))
                            .filter { it.watch == WatchStatus.UNWATCHED && it.isAvailable }.take(ROW_SIZE),
                        folderCount = 1,
                        fileCount = catalog.movies.sumOf { it.files.size } + catalog.series.sumOf { s -> s.episodes.sumOf { it.files.size } },
                        loaded = true,
                        source = name
                    )
                }
                state is CatalogState.Failed -> HomeUi(rows = layout.visibleRows, loaded = true, source = name, sourceError = state.message)
                else -> HomeUi(source = name)
            }
        }
    }

    /** An episode in progress resumes and carries on with the series; a film or an unidentified file plays alone. */
    fun play(item: ContinueItem) = launchPlan("Ce fichier n'est pas disponible : le support est peut-être débranché.") {
        serverId.value?.let { return@launchPlan shared.planForFile(it, item.mediaFileId) }
        val episodeId = item.episodeId
        val seriesId = item.titleId
        if (item.kind == MediaKind.SERIES && episodeId != null && seriesId != null) planner.forEpisode(seriesId, episodeId, item.mediaFileId)
        else planner.forFile(item.mediaFileId)
    }

    fun play(item: NextUpItem) = launchPlan("Cet épisode n'est pas disponible : le support est peut-être débranché.") {
        serverId.value?.let { return@launchPlan shared.planForEpisode(it, item.seriesId, item.episodeId, item.mediaFileId) }
        planner.forEpisode(item.seriesId, item.episodeId, item.mediaFileId)
    }

    fun play(item: LibraryItem) = launchPlan("Aucun fichier de « ${item.title} » n'est disponible : le support est peut-être débranché.") {
        serverId.value?.let { return@launchPlan shared.planForItem(it, item) }
        actions.play(item)
    }

    fun setWatched(item: LibraryItem, watched: Boolean) {
        serverId.value?.let { return shared.setWatched(it, item, watched) }
        viewModelScope.launch { actions.setWatched(item, watched) }
    }

    /** Favourites belong to this phone's library: a PC's title has none. */
    val canFavorite: Boolean get() = serverId.value == null

    fun toggleFavorite(item: LibraryItem) {
        if (!canFavorite) return
        viewModelScope.launch {
            val nowFavorite = actions.toggleFavorite(item)
            eventChannel.send(HomeEvent.Message(if (nowFavorite) "« ${item.title} » ajouté aux favoris" else "« ${item.title} » retiré des favoris"))
        }
    }

    suspend fun isFavorite(item: LibraryItem): Boolean = canFavorite && actions.isFavorite(item)

    private fun launchPlan(unavailable: String, plan: suspend () -> PlayPlan?) {
        viewModelScope.launch {
            val result = plan()
            eventChannel.send(if (result != null) HomeEvent.Play(result) else HomeEvent.Message(unavailable))
        }
    }

    private companion object {
        const val ROW_SIZE = 20
    }
}
