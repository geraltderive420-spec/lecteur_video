package com.lecteur.desktop.player

import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.WatchState
import com.lecteur.core.player.queue.PlaybackQueue
import com.lecteur.core.player.queue.QueueItem
import com.lecteur.core.player.queue.RepeatMode
import com.lecteur.core.player.queue.UpNext
import com.lecteur.core.player.resume.PlaybackSnapshot
import com.lecteur.core.player.resume.ResumePolicy
import com.lecteur.core.player.resume.ResumeTracker
import com.lecteur.core.player.resume.WatchStateStore
import com.lecteur.core.player.sleep.SleepTimer
import com.lecteur.core.player.tracks.LanguageCodes
import com.lecteur.core.player.tracks.LanguagePreferences
import com.lecteur.core.player.tracks.SUBTITLE_OFF
import com.lecteur.desktop.data.LibrarySnapshot
import com.lecteur.desktop.data.LibraryStore
import com.lecteur.desktop.data.ResumeBehavior
import com.lecteur.desktop.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Who drives the player right now: the user on this PC, or a phone through companion mode. */
enum class PlaybackOwner { LOCAL, REMOTE }

data class ResumeQuestion(val mediaFileId: Long, val positionMs: Long, val title: String)

data class SessionUi(
    val active: Boolean = false,
    val owner: PlaybackOwner = PlaybackOwner.LOCAL,
    val title: String? = null,
    val subtitle: String? = null,
    val mediaFileId: Long? = null,
    val queue: List<QueueItem> = emptyList(),
    val queueIndex: Int = 0,
    val queueLabel: String? = null,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    /** Seconds before the next episode starts by itself; null when no card is shown. */
    val upNextSeconds: Int? = null,
    val upNextTitle: String? = null,
    val repeat: RepeatMode = RepeatMode.OFF,
    val shuffle: Boolean = false,
    val resumeQuestion: ResumeQuestion? = null,
    /** Who controls the PC from a phone, shown in the player ("Piloté par Pixel 8"). */
    val remoteController: String? = null
)

/**
 * Local playback on top of the one [VlcEngine]: what to open for a library file (resume point, remembered tracks and
 * delays, external subtitles, the series' language overrides), the queue (rest of the season, files of a folder),
 * resume saving through core:player's [ResumeTracker], the "next episode" card and the sleep timer.
 */
class PlaybackController(
    val engine: VlcEngine,
    private val store: LibraryStore,
    private val settings: SettingsStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _ui = MutableStateFlow(SessionUi())
    val ui: StateFlow<SessionUi> = _ui.asStateFlow()

    private var queue: PlaybackQueue? = null
    private var tracker: ResumeTracker? = null
    private var jobs = mutableListOf<Job>()
    private var upNextDismissed = false
    private var currentSeriesId: Long? = null

    val sleepTimer = SleepTimer(scope) { engine.pause() }

    private val watchStore = object : WatchStateStore {
        override suspend fun get(mediaFileId: Long): WatchState? = withContext(Dispatchers.IO) { store.watchState(mediaFileId) }
        override suspend fun save(state: WatchState) = withContext(Dispatchers.IO) { store.saveWatch(state) }
    }

    /** Set by the receiver: called when the local session takes the player back from a phone. */
    var onLocalTakeover: (() -> Unit)? = null

    // region starting

    /** Plays a library file; [queueIds] is the play order it belongs to (the rest of the season, say). Asks about resuming first when the settings say so. */
    fun play(mediaFileId: Long, queueIds: List<Long> = listOf(mediaFileId), fromStart: Boolean? = null, label: String? = null) {
        scope.launch {
            val snapshot = store.snapshot.value
            val items = queueIds.mapNotNull { id -> snapshot.fileIndex[id]?.let { QueueItem(it.file.uri, titleOf(snapshot, id).first, id) } }
                .ifEmpty { listOfNotNull(store.mediaFile(mediaFileId)?.let { QueueItem(it.uri, it.fileName, it.id) }) }
            if (items.isEmpty()) return@launch
            val start = items.indexOfFirst { it.mediaFileId == mediaFileId }.coerceAtLeast(0)
            queue = PlaybackQueue(items, start).also { it.label = label }

            val watch = withContext(Dispatchers.IO) { store.watchState(mediaFileId) }
            val resumeAt = watch?.let { ResumePolicy.resumePosition(it.positionMs, it.durationMs, it.isCompleted) }
            when {
                fromStart != null -> openCurrent(if (fromStart) 0 else resumeAt ?: 0)
                resumeAt == null -> openCurrent(0)
                settings.value.resumeBehavior == ResumeBehavior.ALWAYS -> openCurrent(resumeAt)
                settings.value.resumeBehavior == ResumeBehavior.NEVER -> openCurrent(0)
                else -> _ui.update { it.copy(resumeQuestion = ResumeQuestion(mediaFileId, resumeAt, titleOf(snapshot, mediaFileId).first)) }
            }
        }
    }

    fun answerResume(resume: Boolean?) {
        val question = _ui.value.resumeQuestion ?: return
        _ui.update { it.copy(resumeQuestion = null) }
        if (resume == null) return
        scope.launch { openCurrent(if (resume) question.positionMs else 0) }
    }

    /** A file that is not in the library (opened from the explorer or dropped on the window). */
    fun playPath(file: File, siblings: List<File> = listOf(file)) {
        scope.launch {
            val known = withContext(Dispatchers.IO) { siblings.map { f -> store.mediaFileByPath(f.absolutePath) } }
            val items = siblings.mapIndexed { i, f -> QueueItem(f.absolutePath, f.nameWithoutExtension, known[i]?.id) }
            queue = PlaybackQueue(items, siblings.indexOf(file).coerceAtLeast(0)).also { it.label = file.parentFile?.name }
            val id = items[queue!!.currentIndexInPlayOrder].mediaFileId
            val watch = id?.let { withContext(Dispatchers.IO) { store.watchState(it) } }
            openCurrent(watch?.let { ResumePolicy.resumePosition(it.positionMs, it.durationMs, it.isCompleted) } ?: 0)
        }
    }

    private suspend fun openCurrent(startMs: Long) {
        val q = queue ?: return
        val item = q.current
        flush()
        bindEngine()
        onLocalTakeover?.invoke()
        val snapshot = store.snapshot.value
        val id = item.mediaFileId
        val s = settings.value
        val watch = id?.let { withContext(Dispatchers.IO) { store.watchState(it) } }
        val (title, subtitle) = id?.let { titleOf(snapshot, it) } ?: (item.title to null)

        val seriesId = id?.let { snapshot.fileIndex[it]?.file?.episodeId }?.let { snapshot.episodeToSeries[it] }
        currentSeriesId = seriesId
        val (seriesAudio, seriesSub) = seriesId?.let { withContext(Dispatchers.IO) { store.seriesPrefs(it) } } ?: (null to null)
        val languages = LanguagePreferences(
            audio = listOfNotNull(seriesAudio) + s.audioLanguages,
            subtitles = listOfNotNull(seriesSub) + s.subtitleLanguages,
            subtitleMode = s.subtitleMode
        )
        val externals = id?.let { withContext(Dispatchers.IO) { store.subtitleTracks(it) } }.orEmpty()
            .filter { it.externalPath != null && File(it.externalPath!!).isFile }
            .map { ExternalSubtitle(it.externalPath!!, it.language, it.title, it.isForced) }

        tracker = id?.let { ResumeTracker(it, watchStore, watch) }
        upNextDismissed = false
        _ui.update {
            SessionUi(
                active = true, owner = PlaybackOwner.LOCAL, title = title, subtitle = subtitle, mediaFileId = id,
                queue = q.playOrder, queueIndex = q.currentIndexInPlayOrder, queueLabel = q.label,
                hasNext = q.hasNext(), hasPrevious = q.hasPrevious(), remoteController = it.remoteController,
                repeat = q.repeat, shuffle = q.isShuffled
            )
        }
        engine.awaitSurface()
        engine.open(
            OpenRequest(
                location = item.uri, title = title, subtitle = subtitle, mediaFileId = id, startPositionMs = startMs,
                audioIndex = watch?.selectedAudioTrackIndex, subtitleIndex = watch?.selectedSubtitleTrackIndex,
                audioDelayMs = watch?.audioDelayMs ?: 0, subtitleDelayMs = watch?.subtitleDelayMs ?: 0,
                externalSubtitles = externals, languages = languages, hardwareDecoding = s.hardwareDecoding,
                nightMode = s.nightMode
            )
        )
        engine.setVolume(s.volume)
    }

    // endregion

    // region queue

    fun next() {
        val q = queue ?: return
        q.next() ?: return
        scope.launch { openCurrent(0) }
    }

    fun previous() {
        val q = queue ?: return
        // Like every player: "previous" in the first seconds goes back one item, later it restarts the current one
        if (engine.state.value.positionMs > 5_000 || !q.hasPrevious()) {
            engine.seekTo(0)
            return
        }
        q.previous() ?: return
        scope.launch { openCurrent(0) }
    }

    fun jumpTo(indexInPlayOrder: Int) {
        val q = queue ?: return
        q.jumpTo(indexInPlayOrder) ?: return
        scope.launch { openCurrent(resumePointOf(q.current.mediaFileId)) }
    }

    fun cycleRepeat() {
        queue?.cycleRepeat()
        _ui.update { it.copy(repeat = queue?.repeat ?: RepeatMode.OFF, hasNext = queue?.hasNext() == true) }
    }

    fun setShuffle(on: Boolean) {
        val q = queue ?: return
        q.setShuffle(on)
        _ui.update { it.copy(shuffle = q.isShuffled, queue = q.playOrder, queueIndex = q.currentIndexInPlayOrder, hasNext = q.hasNext(), hasPrevious = q.hasPrevious()) }
    }

    fun setNightMode(on: Boolean) {
        settings.update { it.copy(nightMode = on) }
        engine.reopen { it.copy(nightMode = on) }
    }

    fun dismissUpNext() {
        upNextDismissed = true
        _ui.update { it.copy(upNextSeconds = null) }
    }

    private suspend fun resumePointOf(id: Long?): Long {
        val w = id?.let { withContext(Dispatchers.IO) { store.watchState(it) } } ?: return 0
        return ResumePolicy.resumePosition(w.positionMs, w.durationMs, w.isCompleted) ?: 0
    }

    // endregion

    // region user choices remembered per file / per series

    fun selectAudio(index: Int) {
        engine.selectAudio(index)
        rememberSeriesLanguage(audio = engine.state.value.audio.getOrNull(index)?.language)
    }

    fun selectSubtitle(index: Int) {
        engine.selectSubtitle(index)
        if (index >= 0) rememberSeriesLanguage(subtitle = engine.state.value.subtitles.getOrNull(index)?.language)
    }

    private fun rememberSeriesLanguage(audio: String? = null, subtitle: String? = null) {
        val seriesId = currentSeriesId ?: return
        scope.launch(Dispatchers.IO) {
            val (a, s) = store.seriesPrefs(seriesId)
            store.setSeriesPrefs(seriesId, LanguageCodes.normalize(audio) ?: a, LanguageCodes.normalize(subtitle) ?: s)
        }
    }

    // endregion

    /** Leaves the player: the position is saved first. */
    fun close() {
        if (_ui.value.owner == PlaybackOwner.REMOTE) return
        val snapshot = snapshot()
        val t = tracker
        if (snapshot.durationMs > 0) scope.launch { t?.flush(snapshot) }
        tracker = null
        unbind()
        engine.stop()
        sleepTimer.cancel()
        queue = null
        _ui.update { SessionUi(remoteController = it.remoteController) }
    }

    /** The receiver took the player for a phone: local tracking stops, the window stays. */
    fun handOverToRemote(title: String?, controller: String?) {
        val snapshot = snapshot()
        val t = tracker
        scope.launch { t?.flush(snapshot) }
        tracker = null
        unbind()
        queue = null
        _ui.value = SessionUi(active = true, owner = PlaybackOwner.REMOTE, title = title, remoteController = controller)
    }

    fun setRemoteController(name: String?) = _ui.update { it.copy(remoteController = name) }

    fun endRemote() {
        if (_ui.value.owner != PlaybackOwner.REMOTE) return
        engine.stop()
        _ui.update { SessionUi(remoteController = it.remoteController) }
    }

    // region engine binding

    private fun bindEngine() {
        unbind()
        jobs += scope.launch {
            engine.state.collect { state ->
                val snap = snapshot()
                // Nothing is saved before the file is really open (an unreadable file must not look "started")
                if (snap.durationMs > 0) {
                    if (state.isPlaying) tracker?.onProgress(snap) else if (state.status == EngineStatus.READY) tracker?.flush(snap)
                }
                updateUpNext(state)
            }
        }
        jobs += scope.launch {
            engine.events.collect { event ->
                if (event is EngineEvent.Ended) onEnded()
            }
        }
    }

    private fun unbind() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private suspend fun onEnded() {
        tracker?.flush(snapshot().copy(positionMs = engine.state.value.durationMs, isPlaying = false))
        sleepTimer.onMediaEnded()
        val q = queue
        val chain = settings.value.autoPlayNext || q?.repeat != RepeatMode.OFF
        if (q != null && chain && !upNextDismissed && q.peekNextOnEnd() != null && sleepTimer.state.value is com.lecteur.core.player.sleep.SleepTimerState.Off) {
            q.advanceOnEnd()
            openCurrent(resumePointOf(q.current.mediaFileId))
        } else {
            delay(400)
            close()
        }
    }

    private fun updateUpNext(state: EngineState) {
        val q = queue
        val next = q?.peekNextOnEnd()
        val prompt = if (next != null && q.repeat != RepeatMode.ONE && settings.value.autoPlayNext && !upNextDismissed && state.isPlaying) {
            UpNext.prompt(state.positionMs, state.durationMs, state.speed, settings.value.upNextSeconds, creditsStart(state))
        } else null
        val seconds = prompt?.secondsLeft
        if (seconds != _ui.value.upNextSeconds) {
            _ui.update { it.copy(upNextSeconds = seconds, upNextTitle = next?.let { n -> n.mediaFileId?.let { id -> titleOf(store.snapshot.value, id).let { t -> t.second ?: t.first } } ?: n.title }) }
        }
    }

    /** A chapter named like the end credits ("Générique", "Credits", "End") starts the card early. */
    private fun creditsStart(state: EngineState): Long? =
        state.chapters.lastOrNull { (name, _) -> CREDITS.containsMatchIn(name) }?.second?.takeIf { it > state.durationMs / 2 }

    private suspend fun flush() {
        val s = snapshot()
        if (s.durationMs > 0) tracker?.flush(s)
    }

    private fun snapshot(): PlaybackSnapshot {
        val s = engine.state.value
        return PlaybackSnapshot(
            positionMs = s.positionMs,
            durationMs = s.durationMs,
            isPlaying = s.isPlaying,
            selectedAudioTrackIndex = s.selectedAudioIndex,
            selectedSubtitleTrackIndex = s.selectedSubtitleIndex ?: if (s.subtitles.isNotEmpty()) SUBTITLE_OFF else null,
            audioDelayMs = s.audioDelayMs,
            subtitleDelayMs = s.subtitleDelayMs,
            displayMode = s.displayMode.name
        )
    }

    // endregion

    companion object {
        private val CREDITS = Regex("(?i)g[ée]n[ée]rique|credits|end ?roll|ending|outro")

        /** "Inception" / null, or "Breaking Bad" / "S01E02 · Cat's in the Bag...". */
        fun titleOf(snapshot: LibrarySnapshot, mediaFileId: Long): Pair<String, String?> {
            val file = snapshot.fileIndex[mediaFileId]?.file ?: return "Vidéo" to null
            file.movieId?.let { id -> snapshot.movies[id]?.let { return it.movie.title to it.movie.year?.toString() } }
            file.episodeId?.let { eid ->
                val sid = snapshot.episodeToSeries[eid]
                val s = sid?.let { snapshot.series[it] }
                val ep = s?.episodes?.firstOrNull { it.episode.id == eid }
                if (s != null && ep != null) return s.series.title to LibrarySnapshot.episodeLabel(ep.episode)
            }
            return file.fileName.substringBeforeLast('.') to null
        }

        fun kindOf(snapshot: LibrarySnapshot, mediaFileId: Long): MediaKind? {
            val file = snapshot.fileIndex[mediaFileId]?.file ?: return null
            return if (file.movieId != null) MediaKind.MOVIE else if (file.episodeId != null) MediaKind.SERIES else null
        }
    }
}
