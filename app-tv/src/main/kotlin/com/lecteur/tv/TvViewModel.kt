package com.lecteur.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.lecteur.core.data.library.ContinueItem
import com.lecteur.core.data.library.FolderRepository
import com.lecteur.core.data.library.HomeRepository
import com.lecteur.core.data.library.LibraryRepository
import com.lecteur.core.data.library.NextUpItem
import com.lecteur.core.data.playback.PlayPlanner
import com.lecteur.core.data.playback.PlaybackRequestFactory
import com.lecteur.core.model.LibraryItem
import com.lecteur.core.model.LibraryQuery
import com.lecteur.core.model.LibrarySection
import com.lecteur.core.model.LibrarySort
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.PlayPlan
import com.lecteur.feature.cast.receiver.ReceiverHost
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TvHomeUi(
    val continueItems: List<ContinueItem> = emptyList(),
    val nextUp: List<NextUpItem> = emptyList(),
    val recent: List<LibraryItem> = emptyList(),
    /** False until the library has been read once: an empty TV library is explained, a loading one is not. */
    val loaded: Boolean = false,
    val hasFolders: Boolean = false
)

/** Reads the TV's own library and starts local playback through the shared receiver host. */
@OptIn(UnstableApi::class)
@HiltViewModel
class TvViewModel @Inject constructor(
    home: HomeRepository,
    library: LibraryRepository,
    folders: FolderRepository,
    private val planner: PlayPlanner,
    private val requestFactory: PlaybackRequestFactory,
    private val host: ReceiverHost
) : ViewModel() {

    val homeUi: StateFlow<TvHomeUi> = combine(home.continueWatching, home.nextUp, home.recentlyAdded, folders.folders) { c, n, r, f ->
        TvHomeUi(c, n, r, loaded = true, hasFolders = f.isNotEmpty())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TvHomeUi())

    val movies: StateFlow<List<LibraryItem>> = library.observe(LibraryQuery(LibrarySection.MOVIES, LibrarySort()))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val series: StateFlow<List<LibraryItem>> = library.observe(LibraryQuery(LibrarySection.SERIES, LibrarySort()))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun playFile(mediaFileId: Long) = viewModelScope.launch {
        requestFactory.forMediaFile(mediaFileId)?.let { open(it.request, it.resumePromptMs) }
    }

    fun playItem(item: LibraryItem) = viewModelScope.launch {
        val plan: PlayPlan? = when (item.kind) {
            MediaKind.MOVIE -> planner.forMovie(item.id)
            MediaKind.SERIES -> planner.forSeries(item.id)
        }
        val entry = plan?.entries?.getOrNull(plan.startIndex) ?: return@launch
        val prepared = entry.mediaFileId?.let { requestFactory.forMediaFile(it) } ?: requestFactory.forUri(entry.uri)
        open(prepared.request, prepared.resumePromptMs)
    }

    private fun open(request: com.lecteur.core.player.engine.PlaybackRequest, resumeMs: Long?) {
        // A TV remote has no comfortable "resume or restart?" dialog: it resumes, and the player's back key leaves.
        host.open(request.copy(startPositionMs = resumeMs ?: 0, playWhenReady = true), trackLocally = true)
    }
}
