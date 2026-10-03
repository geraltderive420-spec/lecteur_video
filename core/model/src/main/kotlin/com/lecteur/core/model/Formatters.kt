package com.lecteur.core.model

import java.util.Locale

/** Text of durations, sizes and ratings, shared by the lists, the detail pages and the settings. */
object Formatters {

    /** 148 -> "2 h 28 min", 45 -> "45 min", 120 -> "2 h"; null for unknown or empty. */
    fun runtime(minutes: Int?): String? {
        if (minutes == null || minutes <= 0) return null
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0 -> "$rest min"
            rest == 0 -> "$hours h"
            else -> "$hours h $rest min"
        }
    }

    /** Duration of a file in milliseconds, as a runtime. */
    fun runtimeOfMs(durationMs: Long?): String? = runtime(durationMs?.let { ((it + 30_000) / 60_000).toInt() })

    /** 4_500_000_000 -> "4,2 Go" (decimal comma with a French locale). Powers of 1024, labelled the way file managers do. */
    fun size(bytes: Long, locale: Locale = Locale.getDefault()): String {
        if (bytes < 1024) return "$bytes o"
        val units = listOf("Ko", "Mo", "Go", "To")
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        val format = if (value >= 100 || unit == 0) "%.0f %s" else "%.1f %s"
        return String.format(locale, format, value, units[unit])
    }

    /** 7.84 -> "7,8"; null when there is no rating yet. */
    fun rating(rating: Float?, locale: Locale = Locale.getDefault()): String? =
        rating?.takeIf { it > 0f }?.let { String.format(locale, "%.1f", it) }

    /** "1990" -> "Années 1990". */
    fun decade(decade: Int): String = "Années $decade"

    /** "2021-03-05" -> 2021, null when the date is missing or malformed. */
    fun yearOf(date: String?): Int? = date?.take(4)?.toIntOrNull()?.takeIf { it > 0 }

    /** Episodes of a series: "12 épisodes", "1 épisode". */
    fun episodes(count: Int): String = "$count épisode${if (count > 1) "s" else ""}"

    fun seasons(count: Int): String = "$count saison${if (count > 1) "s" else ""}"
}
