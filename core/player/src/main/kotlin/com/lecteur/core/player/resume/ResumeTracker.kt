package com.lecteur.core.player.resume

import com.lecteur.core.model.WatchState

/** What the tracker needs to know about the player at a given instant. */
data class PlaybackSnapshot(
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val selectedAudioTrackIndex: Int?,
    val selectedSubtitleTrackIndex: Int?,
    val audioDelayMs: Long,
    val subtitleDelayMs: Long,
    val displayMode: String?
)

/**
 * Persists playback progress: periodically while playing (never per frame), and on demand through [flush]
 * (pause, exit). One instance per playback session of one media file.
 */
class ResumeTracker(
    private val mediaFileId: Long,
    private val store: WatchStateStore,
    initial: WatchState?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val intervalMs: Long = SAVE_INTERVAL_MS
) {
    private var lastState: WatchState? = initial
    private var lastSavedAt = Long.MIN_VALUE
    private var lastSavedSnapshot: PlaybackSnapshot? = null

    /** Call as often as convenient (e.g. every second); writes at most once per [intervalMs] and only while playing. */
    suspend fun onProgress(snapshot: PlaybackSnapshot) {
        if (!snapshot.isPlaying) return
        val now = clock()
        if (lastSavedAt != Long.MIN_VALUE && now - lastSavedAt < intervalMs) return
        write(snapshot, now)
    }

    /** Immediate save, skipped only when nothing changed since the last write. */
    suspend fun flush(snapshot: PlaybackSnapshot) {
        if (snapshot == lastSavedSnapshot) return
        write(snapshot, clock())
    }

    private suspend fun write(snapshot: PlaybackSnapshot, now: Long) {
        val previous = lastState
        val status = ResumePolicy.status(snapshot.positionMs, snapshot.durationMs)
        val wasCompleted = previous?.isCompleted == true
        val nowCompleted = status == ResumeStatus.COMPLETED

        val state = WatchState(
            id = previous?.id ?: 0,
            mediaFileId = mediaFileId,
            positionMs = if (status == ResumeStatus.NOT_STARTED) 0L else snapshot.positionMs,
            durationMs = snapshot.durationMs,
            // Re-watching an already seen media (IN_PROGRESS again) un-completes it; a "seen" flag set by hand
            // survives merely opening the file (position still below 2%).
            isCompleted = nowCompleted || (wasCompleted && status == ResumeStatus.NOT_STARTED),
            playCount = (previous?.playCount ?: 0) + if (nowCompleted && !wasCompleted) 1 else 0,
            lastWatchedAt = now,
            selectedAudioTrackIndex = snapshot.selectedAudioTrackIndex,
            selectedSubtitleTrackIndex = snapshot.selectedSubtitleTrackIndex,
            audioDelayMs = snapshot.audioDelayMs,
            subtitleDelayMs = snapshot.subtitleDelayMs,
            displayMode = snapshot.displayMode
        )
        store.save(state)
        lastState = state
        lastSavedAt = now
        lastSavedSnapshot = snapshot
    }

    companion object {
        const val SAVE_INTERVAL_MS = 5_000L
    }
}
