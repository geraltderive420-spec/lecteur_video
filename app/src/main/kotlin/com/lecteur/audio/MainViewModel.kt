package com.lecteur.audio

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lecteur.audio.navigation.BrowseKind
import com.lecteur.core.data.details.DetailRepository
import com.lecteur.core.data.library.FolderRepository
import com.lecteur.core.data.settings.AppearanceRepository
import com.lecteur.core.data.settings.OnboardingRepository
import com.lecteur.core.model.AppearanceSettings
import com.lecteur.core.model.Formatters
import com.lecteur.core.model.LibraryFilters
import com.lecteur.core.model.LibrarySection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where the app opens: the guided first launch until it has been seen (or a folder exists already), the home screen after. */
enum class Startup { WELCOME, HOME }

@HiltViewModel
class MainViewModel @Inject constructor(
    appearanceRepository: AppearanceRepository,
    private val onboarding: OnboardingRepository,
    folders: FolderRepository
) : ViewModel() {

    val appearance: StateFlow<AppearanceSettings> =
        appearanceRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppearanceSettings())

    /** Null until the first read: the screen stays blank for a few milliseconds instead of flashing the wrong start. */
    val startup: StateFlow<Startup?> = flow<Startup?> {
        // Decided once, at launch: adding the first folder later must not pull the user out of the screen they are on
        val seen = onboarding.isDone.first()
        val hasFolders = folders.folders.first().isNotEmpty()
        emit(if (seen || hasFolders) Startup.HOME else Startup.WELCOME)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun markWelcomeDone() {
        viewModelScope.launch { onboarding.markDone() }
    }
}

/** What a "browse by" screen is about: the scope that narrows the library and the label saying so. */
@HiltViewModel
class BrowseViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val detail: DetailRepository,
    private val lists: com.lecteur.core.data.library.UserListsRepository
) : ViewModel() {

    val section: LibrarySection = LibrarySection.valueOf(checkNotNull(savedState.get<String>("section")))
    private val kind: BrowseKind = BrowseKind.valueOf(checkNotNull(savedState.get<String>("kind")))
    private val id: Long = checkNotNull(savedState.get<Long>("id"))

    val scope: LibraryFilters = kind.scope(id)

    val label: Flow<String?> = flow {
        emit(
            when (kind) {
                BrowseKind.GENRE -> detail.genreName(id)
                BrowseKind.PERSON -> detail.person(id)?.let { "Avec ${it.name}" }
                BrowseKind.COLLECTION -> detail.collection(id)?.name
                BrowseKind.DECADE -> Formatters.decade(id.toInt())
                BrowseKind.LIST -> lists.lists.first().firstOrNull { it.id == id }?.name
            }
        )
    }
}
