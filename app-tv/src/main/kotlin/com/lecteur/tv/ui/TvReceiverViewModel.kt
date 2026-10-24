package com.lecteur.tv.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.lecteur.core.player.chapters.MediaChapter
import com.lecteur.core.player.display.DisplayMode
import com.lecteur.core.player.engine.Media3PlayerEngine
import com.lecteur.core.player.probe.MediaProbe
import com.lecteur.core.player.settings.EqualizerPreset
import com.lecteur.core.player.settings.PlayerSettings
import com.lecteur.core.player.settings.PlayerSettingsRepository
import com.lecteur.core.player.settings.SubtitleStyle
import com.lecteur.core.player.sleep.SleepTimer
import com.lecteur.core.player.sleep.SleepTimerState
import com.lecteur.feature.cast.receiver.ReceiverHost
import com.lecteur.feature.cast.receiver.ReceiverUi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The receiver host as seen by the TV screens, plus what the TV player offers on top of the engine, as on the phone:
 * display mode, sleep timer, chapters, subtitle style and sound settings (the last two shared with the Settings tab).
 */
@UnstableApi
@HiltViewModel
class TvReceiverViewModel @Inject constructor(
    private val host: ReceiverHost,
    private val settingsRepository: PlayerSettingsRepository,
    private val probe: MediaProbe
) : ViewModel() {
    val ui: StateFlow<ReceiverUi> = host.ui
    val engine: StateFlow<Media3PlayerEngine?> = host.engine

    val settings: StateFlow<PlayerSettings> = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, PlayerSettings())

    private val _displayMode = MutableStateFlow(DisplayMode.FIT)
    val displayMode: StateFlow<DisplayMode> = _displayMode.asStateFlow()

    private val _chapters = MutableStateFlow<List<MediaChapter>>(emptyList())
    val chapters: StateFlow<List<MediaChapter>> = _chapters.asStateFlow()

    private val sleepTimer = SleepTimer(viewModelScope) { engine.value?.pause() }
    val sleep: StateFlow<SleepTimerState> = sleepTimer.state

    private var probedUri: String? = null

    fun closePlayback() {
        sleepTimer.cancel()
        host.closePlayback()
    }

    fun setDisplayMode(mode: DisplayMode) {
        _displayMode.value = mode
    }

    fun startSleep(minutes: Int) = if (minutes <= 0) sleepTimer.cancel() else sleepTimer.startAfter(minutes * 60_000L)

    /** Chapters of the file being played, read once per file (an MKV's chapter list; nothing for most streams). */
    fun loadChapters(engine: Media3PlayerEngine) {
        val uri = engine.player.currentMediaItem?.localConfiguration?.uri?.toString() ?: return
        if (uri == probedUri) return
        probedUri = uri
        _chapters.value = emptyList()
        viewModelScope.launch { _chapters.value = probe.probe(Uri.parse(uri))?.chapters.orEmpty() }
    }

    fun setSubtitleStyle(transform: (SubtitleStyle) -> SubtitleStyle) = viewModelScope.launch {
        settingsRepository.update { it.copy(subtitleStyle = transform(it.subtitleStyle)) }
    }

    fun setNightMode(enabled: Boolean) = viewModelScope.launch {
        engine.value?.setNightMode(enabled)
        settingsRepository.update { it.copy(nightMode = enabled) }
    }

    fun setEqualizer(preset: EqualizerPreset) = viewModelScope.launch {
        engine.value?.setEqualizer(preset)
        settingsRepository.update { it.copy(equalizer = preset) }
    }
}
