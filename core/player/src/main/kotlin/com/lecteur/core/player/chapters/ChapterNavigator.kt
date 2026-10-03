package com.lecteur.core.player.chapters

import java.util.Locale

data class MediaChapter(val index: Int, val startMs: Long, val endMs: Long, val title: String?)

/** Chapter lookup and navigation; chapters are expected sorted by start time. */
class ChapterNavigator(chapters: List<MediaChapter>) {

    val chapters: List<MediaChapter> = chapters.sortedBy { it.startMs }

    val isEmpty: Boolean get() = chapters.isEmpty()

    /** Index (in [chapters]) of the chapter containing [positionMs], or -1 before the first one / without chapters. */
    fun currentIndex(positionMs: Long): Int = chapters.indexOfLast { it.startMs <= positionMs }

    fun current(positionMs: Long): MediaChapter? = chapters.getOrNull(currentIndex(positionMs))

    /** Start of the next chapter, or null when already in the last one. */
    fun nextStart(positionMs: Long): Long? = chapters.firstOrNull { it.startMs > positionMs }?.startMs

    /** Like a CD player: more than [RESTART_THRESHOLD_MS] into a chapter restarts it, otherwise goes to the previous one. */
    fun previousStart(positionMs: Long): Long? {
        val index = currentIndex(positionMs)
        if (index < 0) return null
        val current = chapters[index]
        return if (positionMs - current.startMs > RESTART_THRESHOLD_MS || index == 0) current.startMs else chapters[index - 1].startMs
    }

    /** Where the "Passer le générique" button leads, or null when the current chapter is not an intro/opening. */
    fun introSkipTarget(positionMs: Long): Long? {
        val chapter = current(positionMs) ?: return null
        val title = chapter.title?.lowercase(Locale.ROOT) ?: return null
        return if (introTitle.containsMatchIn(title) && chapter.endMs > positionMs) chapter.endMs else null
    }

    /**
     * Start of the end credits when a chapter is named for them and sits in the last part of the file (a "Credits" chapter
     * at the start of a film would be the opening titles). Null otherwise.
     */
    fun creditsStart(durationMs: Long): Long? {
        if (durationMs <= 0) return null
        return chapters.firstOrNull { chapter ->
            val title = chapter.title?.lowercase(Locale.ROOT) ?: return@firstOrNull false
            creditsTitle.containsMatchIn(title) && chapter.startMs >= durationMs * CREDITS_MIN_FRACTION
        }?.startMs
    }

    companion object {
        const val RESTART_THRESHOLD_MS = 3_000L
        private const val CREDITS_MIN_FRACTION = 0.6
        private val creditsTitle = Regex("\\b(credits?|ending|outro|closing|générique de fin|generique de fin)\\b|^ed\\d*$")
        private val introTitle = Regex("\\b(intro|opening|générique|generique|credits? d'ouverture|op)\\b|^op\\d*$")
    }
}
