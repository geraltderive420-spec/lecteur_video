package com.lecteur.core.common.match

import com.lecteur.core.model.MediaCategory
import com.lecteur.core.model.ParsedMediaInfo

/**
 * When one folder is watched under several categories (films, series, anime...), decides which entry takes a file.
 * Every file goes to exactly one entry, so nothing is listed twice.
 */
object CategoryFit {

    private val ANIME_PATH = Regex("(?i)(^|/)(anim(e|es|és|ations?)|manga)(/|$)")
    private val DOCUMENTARY_PATH = Regex("(?i)documentair|documentar")
    private val FALLBACK_ORDER = listOf(
        MediaCategory.MOVIES, MediaCategory.SERIES, MediaCategory.ANIME,
        MediaCategory.DOCUMENTARIES, MediaCategory.PERSONAL, MediaCategory.GENERIC
    )

    /** Continuous numbering ("Titre - 112") or an "anime" folder in the path. */
    fun looksLikeAnime(parsed: ParsedMediaInfo, relativePath: String): Boolean =
        parsed.absoluteEpisodeNumber != null || ANIME_PATH.containsMatchIn(relativePath)

    /** @param siblings the categories of the entries watching the folder (never empty). */
    fun choose(siblings: Collection<MediaCategory>, parsed: ParsedMediaInfo, relativePath: String): MediaCategory {
        if (siblings.size == 1) return siblings.first()
        val preferred = when {
            parsed.isSeries ->
                if (looksLikeAnime(parsed, relativePath)) listOf(MediaCategory.ANIME, MediaCategory.SERIES) else listOf(MediaCategory.SERIES, MediaCategory.ANIME)
            DOCUMENTARY_PATH.containsMatchIn(relativePath) -> listOf(MediaCategory.DOCUMENTARIES, MediaCategory.MOVIES)
            else -> listOf(MediaCategory.MOVIES, MediaCategory.DOCUMENTARIES)
        }
        return preferred.firstOrNull { it in siblings } ?: FALLBACK_ORDER.first { it in siblings }
    }
}
