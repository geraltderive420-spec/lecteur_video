package com.lecteur.core.player.resume

enum class ResumeStatus { NOT_STARTED, IN_PROGRESS, COMPLETED }

/** Rules from the spec: below 2% = not started, beyond 90% (or in the last minutes) = watched. */
object ResumePolicy {
    const val NOT_STARTED_FRACTION = 0.02
    const val COMPLETED_FRACTION = 0.90

    /** "Last minutes" rule, only applied to media long enough for it to make sense. */
    const val END_WINDOW_MS = 120_000L
    const val END_WINDOW_MIN_DURATION_MS = 10 * 60_000L

    fun status(positionMs: Long, durationMs: Long): ResumeStatus {
        if (durationMs <= 0L) {
            return if (positionMs > 0L) ResumeStatus.IN_PROGRESS else ResumeStatus.NOT_STARTED
        }
        val fraction = positionMs.toDouble() / durationMs.toDouble()
        val remaining = durationMs - positionMs
        return when {
            fraction >= COMPLETED_FRACTION -> ResumeStatus.COMPLETED
            durationMs >= END_WINDOW_MIN_DURATION_MS && remaining <= END_WINDOW_MS -> ResumeStatus.COMPLETED
            fraction < NOT_STARTED_FRACTION -> ResumeStatus.NOT_STARTED
            else -> ResumeStatus.IN_PROGRESS
        }
    }

    /** Position to offer in the "Reprendre à HH:MM:SS" prompt, or null when playback simply starts from 0. */
    fun resumePosition(positionMs: Long, durationMs: Long, isCompleted: Boolean): Long? {
        if (isCompleted) return null
        return if (status(positionMs, durationMs) == ResumeStatus.IN_PROGRESS) positionMs else null
    }
}
