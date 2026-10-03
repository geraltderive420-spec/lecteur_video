package com.lecteur.feature.scanner.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lecteur.core.data.library.AddFolderResult
import com.lecteur.core.data.library.FolderRepository
import com.lecteur.core.data.library.FolderStatus
import com.lecteur.core.data.library.ReviewRepository
import com.lecteur.core.model.MediaCategory
import com.lecteur.core.model.MetadataProvider
import com.lecteur.feature.scanner.work.LibraryJobState
import com.lecteur.feature.scanner.work.ScanScheduler
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

data class FoldersUiState(
    val folders: List<FolderStatus> = emptyList(),
    val job: LibraryJobState = LibraryJobState(),
    val reviewCount: Int = 0,
    val metadataConfigured: Boolean = true,
    val loaded: Boolean = false
)

@HiltViewModel
class FoldersViewModel @Inject constructor(
    private val folders: FolderRepository,
    private val scheduler: ScanScheduler,
    reviews: ReviewRepository,
    provider: MetadataProvider
) : ViewModel() {

    val state: StateFlow<FoldersUiState> = combine(folders.folders, scheduler.jobState, reviews.count) { list, job, count ->
        FoldersUiState(list, job, count, provider.isConfigured, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FoldersUiState(metadataConfigured = provider.isConfigured))

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    /** Adds the folder once per chosen category (like several Plex libraries on the same folder). */
    fun addFolder(treeUri: Uri, categories: Set<MediaCategory>) {
        viewModelScope.launch {
            // Every entry is created before any scan starts, so each file is filed under the category that fits it from the start
            val added = ArrayList<Long>()
            for (category in categories) {
                when (val result = folders.add(treeUri.toString(), category)) {
                    is AddFolderResult.Added -> added += result.folderId
                    is AddFolderResult.AlreadyAdded ->
                        messageChannel.send("« ${result.existing.displayPath} » est déjà dans la bibliothèque en ${category.label()}.")
                    is AddFolderResult.Overlaps ->
                        messageChannel.send(
                            "Impossible d'ajouter en ${category.label()} : « ${result.existing.displayPath} » (${result.existing.category.label()}) " +
                                "chevauche ce dossier et ses fichiers seraient comptés deux fois."
                        )
                }
            }
            added.forEach { scheduler.scanFolder(it) }
        }
    }

    fun setPaused(folderId: Long, paused: Boolean) {
        viewModelScope.launch { folders.setPaused(folderId, paused) }
    }

    fun setCategory(folderId: Long, category: MediaCategory) {
        viewModelScope.launch {
            folders.setCategory(folderId, category)
            // The files were detached from their movies/series: file them again under the new rules
            scheduler.scanFolder(folderId, force = true)
        }
    }

    fun rescan(folderId: Long) = scheduler.scanFolder(folderId, force = true)

    fun scanAll() = scheduler.scanAll()

    fun cancelJobs() = scheduler.cancelAll()

    fun remove(folderId: Long) {
        viewModelScope.launch { folders.remove(folderId) }
    }
}
