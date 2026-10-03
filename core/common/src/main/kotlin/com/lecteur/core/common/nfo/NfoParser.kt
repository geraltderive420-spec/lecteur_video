package com.lecteur.core.common.nfo

enum class NfoKind { MOVIE, SERIES, EPISODE, UNKNOWN }

/** What a Kodi/Jellyfin-style `.nfo` sidecar says. Every field is optional: nfo files in the wild are very uneven. */
data class NfoData(
    val kind: NfoKind,
    val title: String? = null,
    val originalTitle: String? = null,
    val year: Int? = null,
    val overview: String? = null,
    val runtimeMinutes: Int? = null,
    val rating: Float? = null,
    val certification: String? = null,
    val tmdbId: Long? = null,
    val imdbId: String? = null,
    val genres: List<String> = emptyList()
) {
    /** True when the file names the title, so it can stand on its own without any online lookup. */
    val isUsable: Boolean get() = !title.isNullOrBlank() || tmdbId != null || imdbId != null
}

/**
 * Tolerant parser: a regex pass over the text rather than a strict XML parse, because real nfo files are
 * often malformed (several roots, stray text, a bare URL). Returns null when nothing useful is found.
 */
object NfoParser {

    private val ROOT = Regex("<\\s*(movie|tvshow|episodedetails)\\b", RegexOption.IGNORE_CASE)
    private val TMDB_URL = Regex("themoviedb\\.org/(movie|tv)/(\\d+)", RegexOption.IGNORE_CASE)
    private val IMDB_ID = Regex("\\btt\\d{6,10}\\b")
    private val CDATA = Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL)

    fun parse(text: String): NfoData? {
        val content = text.removePrefix("\uFEFF")
        val kind = when (ROOT.find(content)?.groupValues?.get(1)?.lowercase()) {
            "movie" -> NfoKind.MOVIE
            "tvshow" -> NfoKind.SERIES
            "episodedetails" -> NfoKind.EPISODE
            else -> NfoKind.UNKNOWN
        }

        val tmdbUrl = TMDB_URL.find(content)
        val tmdbId = uniqueId(content, "tmdb")?.toLongOrNull() ?: tag(content, "tmdbid")?.toLongOrNull() ?: tmdbUrl?.groupValues?.get(2)?.toLongOrNull()
        val imdbId = (uniqueId(content, "imdb") ?: tag(content, "imdbid") ?: tag(content, "id"))
            ?.takeIf { IMDB_ID.matches(it) }
            ?: IMDB_ID.find(content)?.value

        val resolvedKind = if (kind == NfoKind.UNKNOWN && tmdbUrl != null) {
            if (tmdbUrl.groupValues[1].equals("tv", ignoreCase = true)) NfoKind.SERIES else NfoKind.MOVIE
        } else kind

        val title = tag(content, "title")
        val data = NfoData(
            kind = resolvedKind,
            title = title,
            originalTitle = tag(content, "originaltitle"),
            year = tag(content, "year")?.toIntOrNull()
                ?: (tag(content, "premiered") ?: tag(content, "releasedate") ?: tag(content, "aired"))?.take(4)?.toIntOrNull(),
            overview = tag(content, "plot") ?: tag(content, "outline"),
            runtimeMinutes = tag(content, "runtime")?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }?.takeIf { it > 0 },
            rating = (tag(content, "value") ?: tag(content, "rating"))?.replace(',', '.')?.toFloatOrNull()?.takeIf { it > 0f },
            certification = tag(content, "mpaa")?.let(::cleanCertification),
            tmdbId = tmdbId,
            imdbId = imdbId,
            genres = tags(content, "genre")
        )
        return data.takeIf { it.isUsable }
    }

    private fun uniqueId(content: String, type: String): String? =
        Regex("<\\s*uniqueid\\b[^>]*\\btype\\s*=\\s*[\"']$type[\"'][^>]*>(.*?)</\\s*uniqueid\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(content)?.groupValues?.get(1)?.let(::clean)?.takeIf(String::isNotEmpty)

    private fun tag(content: String, name: String): String? = tags(content, name).firstOrNull()

    private fun tags(content: String, name: String): List<String> =
        Regex("<\\s*$name\\b[^>/]*>(.*?)</\\s*$name\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(content).map { clean(it.groupValues[1]) }.filter(String::isNotEmpty).toList()

    private fun clean(raw: String): String {
        val unwrapped = CDATA.replace(raw) { it.groupValues[1] }
        return unwrapped
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
            .trim()
    }

    /** "Rated PG-13" / "FR:12" / "12" -> the bare rating. */
    private fun cleanCertification(raw: String): String? =
        raw.removePrefix("Rated").substringAfter(':').trim().takeIf(String::isNotEmpty)
}
