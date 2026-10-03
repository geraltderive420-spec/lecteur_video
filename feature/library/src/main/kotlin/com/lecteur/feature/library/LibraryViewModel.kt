package com.lecteur.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.lecteur.core.data.library.ItemActions
import com.lecteur.core.data.library.ListPickerModel
import com.lecteur.core.data.library.UserListsRepository
import com.lecteur.core.data.library.LibraryRepository
import com.lecteur.core.data.settings.LibraryViewRepository
import com.lecteur.core.model.FilterOptions
import com.lecteur.core.model.LibraryFilters
import com.lecteur.core.model.LibraryItem
import com.lecteur.core.model.LibraryQuery
import com.lecteur.core.model.LibrarySection
import com.lecteur.core.model.LibrarySort
import com.lecteur.core.model.LibraryView
import com.lecteur.core.model.PlayPlan
import com.lecteur.core.model.PosterSize
import com.lecteur.core.model.SectionPrefs
import com.lecteur.core.model.SortField
import com.lecteur.core.model.WatchStatus
import com.lecteur.core.model.defaultOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What one library page shows: a shelf, narrowed by the entry the user browsed from (an actor, a saga...). */
data class LibraryBinding(val section: LibrarySection, val scope: LibraryFilters)

sealed interface LibraryEvent {
    data class Play(val plan: PlayPlan) : LibraryEvent
    data class Message(val text: String) : LibraryEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val views: LibraryViewRepository,
    private val actions: ItemActions,
    lists: UserListsRepository
) : ViewModel() {

    val listPicker = ListPickerModel(lists, viewModelScope)

    fun addToList(item: LibraryItem) = listPicker.open(item.kind, item.id, item.title)

    private val binding = MutableStateFlow<LibraryBinding?>(null)
    private val _filters = MutableStateFlow(LibraryFilters())
    val filters: StateFlow<LibraryFilters> = _filters

    private val _options = MutableStateFlow<FilterOptions?>(null)
    val options: StateFlow<FilterOptions?> = _options

    private val storedPrefs: Flow<SectionPrefs> = binding.filterNotNull().flatMapLatest { views.prefs(it.section) }

    val prefs: StateFlow<SectionPrefs> = storedPrefs.stateIn(viewModelScope, SharingStarted.Eagerly, SectionPrefs())

    /**
     * The grid. Rebuilt when the sort or a filter changes, kept across rotations. It waits for the stored sort rather than
     * starting with the default one: the first query already uses the order the user left.
     */
    val items: Flow<PagingData<LibraryItem>> = combine(binding.filterNotNull(), storedPrefs.map { it.sort }.distinctUntilChanged(), _filters) { b, sort, filters ->
        LibraryQuery(b.section, sort, filters)
    }.distinctUntilChanged().flatMapLatest { library.paged(it) }.cachedIn(viewModelScope)

    private val eventChannel = Channel<LibraryEvent>(Channel.BUFFERED)
    val events: Flow<LibraryEvent> = eventChannel.receiveAsFlow()

    /** Called by the page with what it shows; a second call with the same binding changes nothing (recomposition, rotation). */
    fun bind(section: LibrarySection, scope: LibraryFilters) {
        val next = LibraryBinding(section, scope)
        if (binding.value == next) return
        binding.value = next
        _filters.value = scope
        viewModelScope.launch { _options.value = library.filterOptions(section) }
    }

    fun setFilters(filters: LibraryFilters) {
        _filters.value = filters
    }

    fun updateFilters(transform: (LibraryFilters) -> LibraryFilters) = _filters.update(transform)

    /** Drops what the filter sheet set; the actor or saga the page was opened for stays. */
    fun clearFilters() = _filters.update { it.withoutSheetFilters() }

    /** Same field again flips the order, another field starts from its natural order (titles A-Z, the rest best first). */
    fun sortBy(field: SortField) {
        val section = binding.value?.section ?: return
        val current = prefs.value.sort
        val next = if (current.field == field) LibrarySort(field, current.order.flipped()) else LibrarySort(field, field.defaultOrder())
        viewModelScope.launch { views.setSort(section, next) }
    }

    fun setView(view: LibraryView) {
        val section = binding.value?.section ?: return
        viewModelScope.launch { views.setView(section, view) }
    }

    fun setPosterSize(size: PosterSize) {
        viewModelScope.launch { views.setPosterSize(size) }
    }

    fun play(item: LibraryItem) {
        viewModelScope.launch {
            val plan = actions.play(item)
            eventChannel.send(
                if (plan != null) LibraryEvent.Play(plan)
                else LibraryEvent.Message("Aucun fichier de « ${item.title} » n'est disponible : le support est peut-être débranché.")
            )
        }
    }

    fun toggleWatched(item: LibraryItem) {
        viewModelScope.launch { actions.setWatched(item, item.watch != WatchStatus.WATCHED) }
    }

    fun toggleFavorite(item: LibraryItem) {
        viewModelScope.launch {
            val now = actions.toggleFavorite(item)
            eventChannel.send(LibraryEvent.Message(if (now) "« ${item.title} » ajouté aux favoris" else "« ${item.title} » retiré des favoris"))
        }
    }

    suspend fun isFavorite(item: LibraryItem): Boolean = actions.isFavorite(item)
}
