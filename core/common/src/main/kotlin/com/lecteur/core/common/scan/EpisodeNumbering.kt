package com.lecteur.core.common.scan

import com.lecteur.core.common.match.TitleSimilarity

object EpisodeNumbering {

    /**
     * Anime are often numbered across the whole run ("Titre - 112"). TMDB numbers per season, so the absolute number
     * is walked through the season sizes (specials, season 0, do not count).
     * @return season to episode, or null when the number is beyond the known episodes.
     */
    fun fromAbsolute(absolute: Int, episodeCountBySeason: Map<Int, Int>): Pair<Int, Int>? {
        if (absolute < 1) return null
        var remaining = absolute
        for ((season, count) in episodeCountBySeason.filterKeys { it > 0 }.toSortedMap()) {
            if (remaining <= count) return season to remaining
            remaining -= count
        }
        return null
    }

    /**
     * The filename parser encodes a date episode ("Show 2024.03.15") as season = year and episode = month * 100 + day.
     * Real seasons never reach 1900, so the pair is unambiguous.
     */
    fun isDateBased(season: Int?, episode: Int?): Boolean {
        if (season == null || episode == null || season !in 1900..2200) return false
        val month = episode / 100
        val day = episode % 100
        return month in 1..12 && day in 1..31
    }

    /** ISO date ("2024-03-15") of a date-encoded episode, as TMDB writes air dates. */
    fun dateOf(season: Int, episode: Int): String = "%04d-%02d-%02d".format(season, episode / 100, episode % 100)
}

/** Keys under which a series is remembered between scans, so a new episode finds its series again. */
object SeriesKey {

    /** Most specific first: "doctor who 2005", then "doctor who". */
    fun lookupKeys(title: String, year: Int?): List<String> {
        val plain = TitleSimilarity.normalize(title)
        if (plain.isEmpty()) return emptyList()
        return if (year != null) listOf("$plain $year", plain) else listOf(plain)
    }

    /** The key a newly created series is registered under. */
    fun registrationKey(title: String, year: Int?): String = lookupKeys(title, year).first()
}

object VideoFiles {
    val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "m4v", "avi", "mov", "ts", "m2ts", "webm", "wmv", "flv", "mpg", "mpeg")

    fun extensionOf(name: String): String? = name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() && '/' !in it }

    fun isVideo(name: String): Boolean = extensionOf(name) in VIDEO_EXTENSIONS

    fun isNfo(name: String): Boolean = extensionOf(name) == "nfo"

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    private val POSTER_NAMES = setOf("poster", "folder", "cover", "movie")
    private val BACKDROP_NAMES = setOf("fanart", "backdrop", "background", "landscape")

    fun isImage(name: String): Boolean = extensionOf(name) in IMAGE_EXTENSIONS

    /** `poster.jpg`, `folder.jpg`, or `<video name>-poster.jpg` / `<video name>.jpg`. */
    fun isPosterFor(imageName: String, videoBaseName: String?): Boolean = artworkMatches(imageName, videoBaseName, POSTER_NAMES, "poster")

    fun isBackdropFor(imageName: String, videoBaseName: String?): Boolean = artworkMatches(imageName, videoBaseName, BACKDROP_NAMES, "fanart")

    private fun artworkMatches(imageName: String, videoBaseName: String?, generic: Set<String>, suffix: String): Boolean {
        if (!isImage(imageName)) return false
        val base = imageName.substringBeforeLast('.').lowercase()
        if (base in generic) return true
        val video = videoBaseName?.lowercase() ?: return false
        return base == "$video-$suffix" || (suffix == "poster" && base == video)
    }
}
