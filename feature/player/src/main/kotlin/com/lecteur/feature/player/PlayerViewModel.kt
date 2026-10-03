package com.lecteur.feature.player

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.lecteur.core.data.playback.PlaybackQueueStore
import com.lecteur.core.data.playback.PlaybackRequestFactory
import com.lecteur.core.data.playback.PreparedPlayback
import com.lecteur.core.model.HdrType
import com.lecteur.core.model.PlayPlan
import com.lecteur.core.player.chapters.ChapterNavigator
import com.lecteur.core.player.display.DisplayMode
import com.lecteur.core.player.engine.EngineConfig
import com.lecteur.core.player.engine.ExternalSubtitleSource
import com.lecteur.core.player.engine.Media3PlayerEngine
import com.lecteur.core.player.engine.PlaybackError
import com.lecteur.core.player.engine.PlaybackErrorKind
import com.lecteur.core.player.engine.PlayerEngine
import com.lecteur.core.player.engine.PlayerEvent
import com.lecteur.core.player.probe.ChapterUnits
import com.lecteur.core.player.probe.MediaProbe
import com.lecteur.core.player.queue.PlaybackQueue
import com.lecteur.core.player.queue.QueueItem
import com.lecteur.core.player.queue.RepeatMode
import com.lecteur.core.player.resume.PlaybackSnapshot
import com.lecteur.core.player.resume.ResumeTracker
import com.lecteur.core.player.resume.WatchStateStore
import com.lecteur.core.player.session.PlayerHolder
import com.lecteur.core.player.settings.DecoderMode
import com.lecteur.core.player.settings.PlayerSettings
import com.lecteur.core.player.settings.PlayerSettingsRepository
import com.lecteur.core.player.sleep.SleepTimer
import com.lecteur.core.player.sleep.SleepTimerState
import com.lecteur.core.player.subtitles.SubtitleFormat
import com.lecteur.core.player.tracks.SUBTITLE_OFF
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

sealed interface PlayerPhase {
    data object Loading : PlayerPhase

    /** The file was left in progress: ask whether to resume. */
    data class ResumePrompt(val positionMs: Long) : PlayerPhase

    data object Playing : PlayerPhase
    data class Failed(val error: PlaybackError, val canRetryWithSoftware: Boolean) : PlayerPhase
}

/** The files being played one after the other, as the screen needs to show them. */
data class QueueUi(
    /** Titles in play order. */
    val titles: List<String>,
    val currentIndex: Int,
    val repeat: RepeatMode,
    val shuffled: Boolean,
    /** What the "next episode" card announces; null when nothing follows or the same file repeats. */
    val upNextTitle: String?,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val label: String?
) {
    val size: Int get() = titles.size
    val isSingle: Boolean get() = titles.size <= 1
}

data class PlayerUiState(
    val phase: PlayerPhase = PlayerPhase.Loading,
    val title: String? = null,
    val displayMode: DisplayMode = DisplayMode.FIT,
    val isLocked: Boolean = false,
    val chapters: ChapterNavigator = ChapterNavigator(emptyList()),
    val sleepTimer: SleepTimerState = SleepTimerState.Off,
    /** HDR types the screen can show, reported by the activity. */
    val displayHdrTypes: Set<HdrType> = emptySet(),
    val queue: QueueUi? = null,
    /** The user dismissed the "next episode" card: the file ends without moving on. */
    val upNextCancelled: Boolean = false
)

@UnstableApi
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val holder: PlayerHolder,
    private val factory: PlaybackRequestFactory,
    private val settingsRepository: PlayerSettingsRepository,
    private val watchStateStore: WatchStateStore,
    private val probe: MediaProbe,
    private val queueStore: PlaybackQueueStore
) : ViewModel() {

    private val _ui = MutableStateFlow(PlayerUiState())
    val ui: StateFlow<PlayerUiState> = _ui.asStateFlow()

    val settings: StateFlow<PlayerSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, PlayerSettings())

    private val _engine = MutableStateFlow<Media3PlayerEngine?>(null)
    val engine: StateFlow<Media3PlayerEngine?> = _engine.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** One-shot messages for the user (toasts). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var currentUri: String? = null
    private var currentMediaFileId: Long? = null
    private var queue: PlaybackQueue? = null
    private var prepared: PreparedPlayback? = null
    private var tracker: ResumeTracker? = null
    private var boundEngine: Media3PlayerEngine? = null
    private val engineJobs = mutableListOf<Job>()
    private var chaptersJob: Job? = null

    private val sleepTimer = SleepTimer(viewModelScope) { _engine.value?.pause() }

    init {
        viewModelScope.launch { sleepTimer.state.collect { s -> _ui.update { it.copy(sleepTimer = s) } } }
    }

    // region lifecycle

    /**
     * Starts what the screen was opened for. With [hasQueue] the plan published by the launcher is taken (a series from this
     * episode on, the files of a folder); without it [uri] plays alone. Called again by a recreated screen it changes nothing.
     */
    fun open(uri: String, hasQueue: Boolean = false) {
        if (hasQueue) {
            val plan = queueStore.take()
            if (plan != null) {
                startPlan(plan)
                return
            }
            // A recreated screen finds the plan already taken: what plays now stays. A fresh process falls through to the uri.
            if (currentUri != null) return
        }
        if (uri == currentUri) return
        queue = null
        publishQueue()
        currentUri = uri
        currentMediaFileId = null
        viewModelScope.launch { start(uri) }
    }

    private fun startPlan(plan: PlayPlan) {
        val items = plan.entries.map { QueueItem(it.uri, it.title, it.mediaFileId) }
        val created = PlaybackQueue(items, plan.startIndex, plan.shuffle).also { it.label = plan.label }
        queue = created
        publishQueue()
        playItem(created.current, autoResume = false)
    }

    private fun playItem(item: QueueItem, autoResume: Boolean) {
        currentUri = item.uri
        currentMediaFileId = item.mediaFileId
        _ui.update { it.copy(upNextCancelled = false) }
        viewModelScope.launch { start(item.uri, item.mediaFileId, autoResume) }
    }

    private suspend fun start(uri: String, mediaFileId: Long? = null, autoResume: Boolean = false) {
        _ui.update { it.copy(phase = PlayerPhase.Loading, chapters = ChapterNavigator(emptyList())) }
        val settings = settingsRepository.settings.first()

        val prepared = runCatching { mediaFileId?.let { factory.forMediaFile(it) } ?: factory.forUri(uri) }.getOrElse {
            fail(
                PlaybackError(
                    PlaybackErrorKind.UNKNOWN,
                    "Impossible d'ouvrir ce fichier.",
                    "Vérifiez qu'il existe toujours et que l'application a le droit de le lire.",
                    it.message
                ),
                settings
            )
            return
        }
        this.prepared = prepared

        val engine = holder.acquire(EngineConfig(settings.decoderMode, settings.passthrough, settings.tunneling))
        bind(engine)
        applyLiveSettings(engine, settings)

        tracker = ResumeTracker(prepared.request.mediaFileId, watchStateStore, prepared.watchState)
        val prompt = prepared.resumePromptMs
        // Moving on to the next file of a queue picks up where it was left without asking again
        val resumeNow = autoResume && prompt != null
        engine.open(
            prepared.request.copy(
                playWhenReady = prompt == null || resumeNow,
                startPositionMs = if (resumeNow) prompt!! else 0
            )
        )

        _ui.update {
            it.copy(
                phase = if (prompt != null && !resumeNow) PlayerPhase.ResumePrompt(prompt) else PlayerPhase.Playing,
                title = prepared.request.title,
                displayMode = prepared.displayMode
            )
        }
        loadChapters(uri, engine)
    }

    private fun bind(engine: Media3PlayerEngine) {
        _engine.value = engine
        if (boundEngine === engine) return
        engineJobs.forEach { it.cancel() }
        engineJobs.clear()
        boundEngine = engine

        engineJobs += viewModelScope.launch {
            engine.events.collect { event ->
                when (event) {
                    PlayerEvent.Ended -> {
                        saveNow()
                        // "Until the end of the episode" means exactly that: nothing starts afterwards
                        val stopAtEnd = sleepTimer.state.value == SleepTimerState.UntilEndOfMedia
                        sleepTimer.onMediaEnded()
                        if (!stopAtEnd) advanceOnEnd()
                    }
                    is PlayerEvent.Failed -> fail(event.error, settings.value)
                }
            }
        }
        engineJobs += viewModelScope.launch {
            engine.progress.collect {
                // While the resume prompt is up the player sits at 0: saving would erase the stored position
                if (_ui.value.phase == PlayerPhase.Playing) tracker?.onProgress(snapshot(engine))
            }
        }
        engineJobs += viewModelScope.launch {
            engine.state.map { it.isPlaying }.distinctUntilChanged().collect { playing ->
                if (!playing) saveNow()
            }
        }
    }

    private fun fail(error: PlaybackError, settings: PlayerSettings) {
        _ui.update {
            it.copy(
                phase = PlayerPhase.Failed(
                    error,
                    canRetryWithSoftware = error.kind == PlaybackErrorKind.UNSUPPORTED_FORMAT &&
                        settings.decoderMode != DecoderMode.SOFTWARE_PREFERRED
                )
            )
        }
    }

    private fun loadChapters(uri: String, engine: Media3PlayerEngine) {
        chaptersJob?.cancel()
        chaptersJob = viewModelScope.launch {
            val chapters = probe.probe(Uri.parse(uri))?.chapters.orEmpty()
            if (chapters.isEmpty()) return@launch
            val duration = withTimeoutOrNull(30_000) { engine.progress.first { it.durationMs > 0 }.durationMs } ?: return@launch
            _ui.update { it.copy(chapters = ChapterNavigator(ChapterUnits.normalize(chapters, duration))) }
        }
    }

    private fun applyLiveSettings(engine: Media3PlayerEngine, settings: PlayerSettings) {
        engine.setNightMode(settings.nightMode)
        engine.setEqualizer(settings.equalizer)
    }

    // endregion

    // region progress

    private fun snapshot(engine: PlayerEngine): PlaybackSnapshot {
        val state = engine.state.value
        val progress = engine.progress.value
        return PlaybackSnapshot(
            positionMs = progress.positionMs,
            durationMs = progress.durationMs,
            isPlaying = state.isPlaying,
            selectedAudioTrackIndex = state.selectedAudioIndex,
            selectedSubtitleTrackIndex = state.selectedSubtitleIndex ?: if (state.subtitleOptions.isNotEmpty()) SUBTITLE_OFF else null,
            audioDelayMs = state.audioDelayMs,
            subtitleDelayMs = state.subtitleDelayMs,
            displayMode = _ui.value.displayMode.name
        )
    }

    private fun saveNow() {
        val engine = _engine.value ?: return
        // Nothing worth saving until the media is loaded and the user has answered the resume prompt
        if (_ui.value.phase != PlayerPhase.Playing || engine.progress.value.durationMs <= 0) return
        viewModelScope.launch { tracker?.flush(snapshot(engine)) }
    }

    /** Called when the screen stops: the position must reach the database before the process can be killed. */
    fun saveProgress() = saveNow()

    /** The user left the player for good: save, stop and free the engine (and with it the media notification). */
    fun closePlayback() {
        val engine = _engine.value ?: return
        saveNow()
        engine.stop()
        sleepTimer.cancel()
        engineJobs.forEach { it.cancel() }
        engineJobs.clear()
        boundEngine = null
        _engine.value = null
        currentUri = null
        currentMediaFileId = null
        queue = null
        _ui.update { it.copy(queue = null, upNextCancelled = false) }
        holder.release()
    }

    // endregion

    // region user actions

    fun resumeFromPrompt() {
        val phase = _ui.value.phase as? PlayerPhase.ResumePrompt ?: return
        _engine.value?.apply {
            seekTo(phase.positionMs)
            play()
        }
        _ui.update { it.copy(phase = PlayerPhase.Playing) }
    }

    fun startOver() {
        _engine.value?.apply {
            seekTo(0)
            play()
        }
        _ui.update { it.copy(phase = PlayerPhase.Playing) }
    }

    fun retry() {
        val uri = currentUri ?: return
        viewModelScope.launch { start(uri, currentMediaFileId) }
    }

    fun retryWithSoftwareDecoding() {
        viewModelScope.launch {
            settingsRepository.update { it.copy(decoderMode = DecoderMode.SOFTWARE_PREFERRED) }
            retry()
        }
    }

    // region queue

    /** Next file of the queue, now. Saves the position of the one being left. */
    fun playNext() {
        val q = queue ?: return
        val item = q.next() ?: return
        switchTo(item)
    }

    fun playPrevious() {
        val q = queue ?: return
        // Like a CD player: past the first seconds, "previous" restarts the current file
        val engine = _engine.value
        if (engine != null && engine.progress.value.positionMs > RESTART_THRESHOLD_MS || !q.hasPrevious()) {
            engine?.seekTo(0)
            return
        }
        val item = q.previous() ?: return
        switchTo(item)
    }

    fun playQueueIndex(indexInPlayOrder: Int) {
        val q = queue ?: return
        val item = q.jumpTo(indexInPlayOrder) ?: return
        switchTo(item)
    }

    fun cycleRepeat() {
        queue?.cycleRepeat()
        publishQueue()
    }

    fun toggleShuffle() {
        val q = queue ?: return
        q.setShuffle(!q.isShuffled)
        publishQueue()
    }

    /** "Annuler" on the next-episode card: this file ends without moving on. */
    fun cancelUpNext() = _ui.update { it.copy(upNextCancelled = true) }

    private fun switchTo(item: QueueItem) {
        saveNow()
        publishQueue()
        playItem(item, autoResume = true)
    }

    private fun advanceOnEnd() {
        val q = queue ?: return
        val repeatOne = q.repeat == RepeatMode.ONE
        if (!repeatOne && (!settings.value.autoPlayNext || _ui.value.upNextCancelled)) return
        val item = q.advanceOnEnd() ?: return
        publishQueue()
        playItem(item, autoResume = true)
    }

    private fun publishQueue() {
        val q = queue
        _ui.update {
            it.copy(
                queue = q?.let { queue ->
                    QueueUi(
                        titles = queue.playOrder.map(QueueItem::title),
                        currentIndex = queue.currentIndexInPlayOrder,
                        repeat = queue.repeat,
                        shuffled = queue.isShuffled,
                        upNextTitle = if (queue.repeat == RepeatMode.ONE) null else queue.peekNext()?.title,
                        hasPrevious = queue.hasPrevious(),
                        hasNext = queue.hasNext(),
                        label = queue.label
                    )
                }
            )
        }
    }

    // endregion

    fun togglePlayPause() {
        val engine = _engine.value ?: return
        if (engine.state.value.playWhenReady && engine.state.value.status != com.lecteur.core.player.engine.PlaybackStatus.ENDED) {
            engine.pause()
        } else {
            engine.play()
        }
    }

    fun seekBy(deltaMs: Long) {
        _engine.value?.seekBy(deltaMs)
    }

    fun seekTo(positionMs: Long) {
        _engine.value?.seekTo(positionMs)
    }

    fun setSpeed(speed: Float) {
        _engine.value?.setSpeed(speed)
    }

    fun selectAudio(index: Int) {
        _engine.value?.selectAudio(index)
    }

    fun selectSubtitle(index: Int) {
        _engine.value?.selectSubtitle(index)
    }

    fun adjustAudioDelay(stepMs: Long) {
        val engine = _engine.value ?: return
        engine.setAudioDelayMs(engine.state.value.audioDelayMs + stepMs)
    }

    fun adjustSubtitleDelay(stepMs: Long) {
        val engine = _engine.value ?: return
        engine.setSubtitleDelayMs(engine.state.value.subtitleDelayMs + stepMs)
    }

    fun resetDelays() {
        _engine.value?.apply {
            setAudioDelayMs(0)
            setSubtitleDelayMs(0)
        }
    }

    fun setDisplayMode(mode: DisplayMode) {
        _ui.update { it.copy(displayMode = mode) }
        saveNow()
    }

    fun cycleDisplayMode() = setDisplayMode(_ui.value.displayMode.next())

    fun setLocked(locked: Boolean) = _ui.update { it.copy(isLocked = locked) }

    fun setDisplayHdrTypes(types: Set<HdrType>) = _ui.update { it.copy(displayHdrTypes = types) }

    fun setVolumeBoost(fraction: Float) {
        _engine.value?.setVolumeBoost(fraction)
    }

    fun startSleepTimer(minutes: Int) = sleepTimer.startAfter(minutes * 60_000L)
    fun startSleepTimerUntilEnd() = sleepTimer.startUntilEndOfMedia()
    fun cancelSleepTimer() = sleepTimer.cancel()

    /** Loads a subtitle file picked by the user. */
    fun addSubtitle(uri: String, fileName: String) {
        val format = SubtitleFormat.fromFileName(fileName)
        val mime = format?.mimeType
        if (format == null || mime == null) {
            _messages.tryEmit(
                if (format != null) "Ce format de sous-titres (image VOBSUB) n'est pas pris en charge."
                else "Format de sous-titres non reconnu (SRT, ASS, SSA, VTT ou SUP attendus)."
            )
            return
        }
        _engine.value?.addExternalSubtitle(ExternalSubtitleSource(uri, mime, null, fileName))
        _messages.tryEmit("Sous-titres chargés : $fileName")
    }

    fun updateSettings(transform: (PlayerSettings) -> PlayerSettings) {
        viewModelScope.launch {
            settingsRepository.update(transform)
            _engine.value?.let { applyLiveSettings(it, settingsRepository.settings.first()) }
        }
    }

    // endregion

    private companion object {
        const val RESTART_THRESHOLD_MS = 3_000L
    }

    override fun onCleared() {
        sleepTimer.cancel()
        super.onCleared()
    }
}
