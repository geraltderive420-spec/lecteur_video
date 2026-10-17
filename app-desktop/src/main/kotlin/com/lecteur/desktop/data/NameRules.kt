package com.lecteur.desktop.data

import com.lecteur.core.common.parser.FilenameParser
import com.lecteur.core.common.scan.FoundFile
import com.lecteur.core.model.ParsedMediaInfo

/**
 * The shared [FilenameParser], plus two corrections that come from what a scanned folder looks like on a PC:
 * - "Show - 01 - Episode title.mp4" inside a "Saison 1" folder is episode 1 of season 1 of the show named by the
 *   folder above (the file name's own title often changes from one season to the next: "Kaiji Ultimate Survivor",
 *   "Kaiji Against All Rules");
 * - a lone number that is a year ("Wall-E.2008") is the film's year, not episode 2008.
 */
object NameRules {
    private val SEASON_FOLDER = Regex("(?i)^(?:season|saison|série|serie|s)[ ._-]*(\\d{1,3})$")
    private val EXPLICIT_EPISODE = Regex("(?i)(^|[^a-z0-9])(s\\d{1,3}[ ._-]?e\\d{1,3}|\\d{1,2}x\\d{2,3})([^a-z0-9]|$)")
    private val DASHED_NUMBER = Regex("^(.+?)[ ._]+-[ ._]+(\\d{1,4})(?:v\\d)?(?:[ ._]+-[ ._]+.*|[ ._]*[\\[(].*)?$")

    /**
     * The show title the file name itself carries when [parse] named the show after its folder ("Kaiji Ultimate
     * Survivor" for a file in "Kaiji l'ultime survivant/Saison 1"): a second chance for the online search.
     */
    fun fileTitle(name: String, parent: String?): String? {
        if (parent?.trim()?.let { SEASON_FOLDER.matchEntire(it) } == null) return null
        if (FilenameParser.parse(name).isSeries) return null
        val m = DASHED_NUMBER.matchEntire(name.substringBeforeLast('.')) ?: return null
        return FilenameParser.parse(m.groupValues[1]).cleanTitle.takeIf { it.isNotBlank() }
    }

    fun parse(found: FoundFile): ParsedMediaInfo = parse(found.name, found.parentName, found.grandparentName)

    fun parse(name: String, parent: String?, grandparent: String?): ParsedMediaInfo {
        val parsed = FilenameParser.parse(name, parent, grandparent)
        val season = parent?.trim()?.let { SEASON_FOLDER.matchEntire(it) }?.groupValues?.get(1)?.toIntOrNull()

        if (!parsed.isSeries && season != null) {
            val base = name.substringBeforeLast('.')
            DASHED_NUMBER.matchEntire(base)?.let { m ->
                val number = m.groupValues[2].toInt()
                val show = grandparent?.takeIf { it.isNotBlank() }?.let { FilenameParser.parse(it).cleanTitle }?.takeIf { it.isNotBlank() }
                    ?: FilenameParser.parse(m.groupValues[1]).cleanTitle
                return parsed.copy(cleanTitle = show, seasonNumber = season, episodeNumbers = listOf(number), absoluteEpisodeNumber = null, isSeries = true, year = null)
            }
        }

        // The parser files "Wall-E.2008" as season 1, episode 2008: a year-like number and no explicit SxxEyy / 1x02
        val lone = parsed.episodeNumbers.singleOrNull()
        if (parsed.isSeries && lone != null && lone in 1900..2100 && (parsed.seasonNumber ?: 1) == 1 && season == null &&
            (parsed.absoluteEpisodeNumber == null || parsed.absoluteEpisodeNumber == lone) && !EXPLICIT_EPISODE.containsMatchIn(name)
        ) {
            val base = name.substringBeforeLast('.')
            val title = base.substringBefore(lone.toString()).replace('.', ' ').replace('_', ' ').trim(' ', '-', '(', '[')
            return parsed.copy(
                cleanTitle = title.ifBlank { parsed.cleanTitle },
                absoluteEpisodeNumber = null,
                year = parsed.year ?: lone,
                seasonNumber = null,
                episodeNumbers = emptyList(),
                isSeries = false
            )
        }
        return parsed
    }
}
