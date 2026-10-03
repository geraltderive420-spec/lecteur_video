package com.lecteur.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lecteur.core.data.library.FolderRepository
import com.lecteur.core.data.library.ReviewRepository
import com.lecteur.core.data.settings.AppearanceRepository
import com.lecteur.core.data.settings.HomeLayoutRepository
import com.lecteur.core.data.settings.ImageCacheController
import com.lecteur.core.data.settings.LibrarySettings
import com.lecteur.core.data.settings.LibrarySettingsRepository
import com.lecteur.core.data.settings.LibraryViewRepository
import com.lecteur.core.model.AppearanceSettings
import com.lecteur.core.model.HomeLayout
import com.lecteur.core.model.HomeRow
import com.lecteur.core.model.LibrarySection
import com.lecteur.core.model.MetadataProvider
import com.lecteur.core.model.PosterSize
import com.lecteur.core.model.ThemeMode
import com.lecteur.core.player.settings.PlayerSettings
import com.lecteur.core.player.settings.PlayerSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SettingsUi(
    val library: LibrarySettings = LibrarySettings(),
    val player: PlayerSettings = PlayerSettings(),
    val appearance: AppearanceSettings = AppearanceSettings(),
    val posterSize: PosterSize = PosterSize.MEDIUM,
    val homeLayout: HomeLayout = HomeLayout.DEFAULT,
    val folderCount: Int = 0,
    val reviewCount: Int = 0,
    val metadataConfigured: Boolean = true
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val librarySettings: LibrarySettingsRepository,
    private val playerSettings: PlayerSettingsRepository,
    private val appearance: AppearanceRepository,
    private val homeLayout: HomeLayoutRepository,
    private val views: LibraryViewRepository,
    private val imageCache: ImageCacheController,
    folders: FolderRepository,
    reviews: ReviewRepository,
    provider: MetadataProvider
) : ViewModel() {

    private data class Basics(val library: LibrarySettings, val player: PlayerSettings, val appearance: AppearanceSettings)
    private data class Layout(val posterSize: PosterSize, val homeLayout: HomeLayout)
    private data class Counts(val folders: Int, val review: Int)

    private val basics = combine(librarySettings.settings, playerSettings.settings, appearance.settings, ::Basics)
    private val layout = combine(views.prefs(LibrarySection.MOVIES).map { it.posterSize }, homeLayout.layout, ::Layout)
    private val counts = combine(folders.folders.map { it.size }, reviews.count, ::Counts)

    val state: StateFlow<SettingsUi> = combine(basics, layout, counts) { basics, layout, counts ->
        SettingsUi(
            library = basics.library,
            player = basics.player,
            appearance = basics.appearance,
            posterSize = layout.posterSize,
            homeLayout = layout.homeLayout,
            folderCount = counts.folders,
            reviewCount = counts.review,
            metadataConfigured = provider.isConfigured
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUi(metadataConfigured = provider.isConfigured))

    /** Used and maximum size of the image cache, in bytes. */
    private val _cache = MutableStateFlow(0L to 0L)
    val cache: StateFlow<Pair<Long, Long>> = _cache

    init {
        refreshCache()
    }

    fun refreshCache() {
        viewModelScope.launch { _cache.value = withContext(Dispatchers.IO) { imageCache.sizeBytes() to imageCache.maxSizeBytes() } }
    }

    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { imageCache.clear() }
            refreshCache()
        }
    }

    // region library

    fun setScanOnLaunch(enabled: Boolean) = launch { librarySettings.setScanOnLaunch(enabled) }
    fun setScanInterval(hours: Int) = launch { librarySettings.setScanIntervalHours(hours) }
    fun setMetadataLanguage(tag: String) = launch { librarySettings.setMetadataLanguage(tag) }

    // endregion

    fun updatePlayer(transform: (PlayerSettings) -> PlayerSettings) = launch { playerSettings.update(transform) }

    // region appearance and home

    fun setThemeMode(mode: ThemeMode) = launch { appearance.setThemeMode(mode) }
    fun setDynamicColor(enabled: Boolean) = launch { appearance.setDynamicColor(enabled) }
    fun setPosterSize(size: PosterSize) = launch { views.setPosterSize(size) }
    fun moveHomeRow(row: HomeRow, delta: Int) = launch { homeLayout.move(row, delta) }
    fun setHomeRowVisible(row: HomeRow, visible: Boolean) = launch { homeLayout.setVisible(row, visible) }
    fun resetHome() = launch { homeLayout.reset() }

    // endregion

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
