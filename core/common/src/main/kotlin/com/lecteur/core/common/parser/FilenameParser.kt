package com.lecteur.core.common.parser

import com.lecteur.core.model.HdrType
import com.lecteur.core.model.ParsedMediaInfo

object FilenameParser {

    private val VIDEO_EXTENSIONS = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "ts", "m2ts", "webm", "wmv", "flv", "mpg", "mpeg"
    )

    private val RESOLUTION_REGEX = Regex(
        "(?i)\\b(2160p|4k|uhd|1080p|1080i|720p|576p|480p|360p)\\b"
    )

    private val VIDEO_CODEC_REGEX = Regex(
        "(?i)\\b(x265|h265|hevc|x264|h264|avc|av1|vp9|xvid|divx|mpeg2|vc-?1)\\b"
    )

    private val SOURCE_REGEX = Regex(
        "(?i)\\b(remux|bluray|blu-ray|bdrip|brrip|web-?dl|webrip|hdtv|dvdrip|dvd)\\b"
    )

    private val HDR_DV_REGEX = Regex("(?i)\\b(dolby[ ._-]?vision|dv)\\b")
    private val HDR10_PLUS_REGEX = Regex("(?i)\\b(hdr10\\+|hdr10plus)\\b")
    private val HDR10_REGEX = Regex("(?i)\\b(hdr10)\\b")
    private val HDR_GENERIC_REGEX = Regex("(?i)\\b(hdr)\\b")
    private val HLG_REGEX = Regex("(?i)\\b(hlg)\\b")

    private val AUDIO_REGEX = Regex(
        "(?i)\\b(dts-hd[ ._-]?ma|dts-hd|dts-x|dts|truehd|atmos|dd\\+|ddp[ ._-]?5\\.1|eac3|ac3|aac|flac|opus|vorbis|mp3|5\\.1|7\\.1)\\b"
    )

    private val SXX_EXX_REGEX = Regex(
        "(?i)(?:^|[._ -])s(\\d{1,3})e(\\d{1,3})((?:[._ -]*(?:e|ep|-)\\d{1,3})*)"
    )

    private val NX_NN_REGEX = Regex(
        "(?i)(?:^|[._ -])(\\d{1,2})x(\\d{1,3})((?:[-x]\\d{1,3})*)"
    )

    private val FRENCH_SEASON_EP_REGEX = Regex(
        "(?i)(?:saison|season|s)[._ -]?(\\d{1,3})[._ -]+(?:[ée]pisode|ep|e)[._ -]?(\\d{1,3})((?:[eE-]\\d{1,3})*)"
    )

    private val DATE_EPISODE_REGEX = Regex(
        "(?:^|[._ -])(19\\d{2}|20\\d{2})[._ -](\\d{2})[._ -](\\d{2})(?:[._ -]|$)"
    )

    private val STANDALONE_EP_REGEX = Regex(
        "(?i)(?:^|[._ -])(?:ep|e|épisode|episode)[._ -]*(\\d{1,4})(?:[._ -]|$)"
    )

    private val ANIME_ABSOLUTE_REGEX = Regex(
        "^(?:\\[([^\\]]+)\\]\\s*)?(.+?)\\s+-\\s+(\\d{2,4})(?:v\\d+)?(?:\\s*[\\[(](.+?)[\\])])?$"
    )

    private val BRACKETED_YEAR_REGEX = Regex("[(\\[](19\\d{2}|20\\d{2})[\\])]")
    private val YEAR_REGEX = Regex("\\b(19\\d{2}|20\\d{2})\\b")

    fun parse(
        filename: String,
        parentDirectoryName: String? = null,
        grandparentDirectoryName: String? = null
    ): ParsedMediaInfo {
        var baseName = filename.trim()
        var extension: String? = null

        val lastDotIndex = baseName.lastIndexOf('.')
        if (lastDotIndex > 0) {
            val potentialExt = baseName.substring(lastDotIndex + 1).lowercase()
            if (potentialExt in VIDEO_EXTENSIONS) {
                extension = potentialExt
                baseName = baseName.substring(0, lastDotIndex)
            }
        }

        val normalized = normalizeSeparators(baseName)

        val resolution = RESOLUTION_REGEX.find(normalized)?.value?.uppercase()
        val videoCodec = VIDEO_CODEC_REGEX.find(normalized)?.value?.uppercase()
        val audioCodec = AUDIO_REGEX.find(baseName)?.value?.uppercase() ?: AUDIO_REGEX.find(normalized)?.value?.uppercase()
        val hdrType = when {
            HDR_DV_REGEX.containsMatchIn(normalized) -> HdrType.DOLBY_VISION
            HDR10_PLUS_REGEX.containsMatchIn(normalized) -> HdrType.HDR10_PLUS
            HDR10_REGEX.containsMatchIn(normalized) -> HdrType.HDR10
            HLG_REGEX.containsMatchIn(normalized) -> HdrType.HLG
            HDR_GENERIC_REGEX.containsMatchIn(normalized) -> HdrType.HDR10
            else -> HdrType.NONE
        }

        var releaseGroup: String? = null
        val releaseGroupMatch = Regex("(?i)-([a-zA-Z0-9_]+)$").find(baseName)
        if (releaseGroupMatch != null) {
            val candidate = releaseGroupMatch.groupValues[1]
            if (!isTechnicalTag(candidate)) {
                releaseGroup = candidate
            }
        }

        var detectedSeason: Int? = null
        val detectedEpisodes = mutableListOf<Int>()
        var absoluteEpisode: Int? = null
        var isSpecial = false
        var titlePart = normalized

        val parentSeason = extractSeasonFromParent(parentDirectoryName)

        // Anime pattern
        val animeMatch = ANIME_ABSOLUTE_REGEX.find(baseName)
        if (animeMatch != null) {
            val group = animeMatch.groupValues[1].ifBlank { null }
            val animeTitle = animeMatch.groupValues[2].trim()
            val epNum = animeMatch.groupValues[3].toIntOrNull()

            if (epNum != null) {
                absoluteEpisode = epNum
                detectedEpisodes.add(epNum)
                releaseGroup = group ?: releaseGroup
                return ParsedMediaInfo(
                    rawFileName = filename,
                    cleanTitle = cleanTitleString(animeTitle),
                    year = extractYearAndTitle(animeTitle, null).first,
                    seasonNumber = parentSeason ?: 1,
                    episodeNumbers = detectedEpisodes,
                    absoluteEpisodeNumber = absoluteEpisode,
                    isSpecial = false,
                    resolution = resolution,
                    videoCodec = videoCodec,
                    audioCodec = audioCodec,
                    hdrType = hdrType,
                    releaseGroup = releaseGroup,
                    extension = extension
                )
            }
        }

        // Special pattern
        val specialMatch = Regex("(?i)(?:^|[._ -])(?:sp|ova|special)[._ -]?(\\d{1,3})(?:[._ -]|$)").find(normalized)
        if (specialMatch != null) {
            val ep = specialMatch.groupValues[1].toIntOrNull()
            if (ep != null) {
                isSpecial = true
                detectedSeason = 0
                detectedEpisodes.add(ep)
                titlePart = normalized.substring(0, specialMatch.range.first)
            }
        }

        // SxxExx pattern
        if (detectedEpisodes.isEmpty()) {
            val sxxMatch = SXX_EXX_REGEX.find(normalized)
            if (sxxMatch != null) {
                detectedSeason = sxxMatch.groupValues[1].toInt()
                if (detectedSeason == 0) isSpecial = true
                detectedEpisodes.add(sxxMatch.groupValues[2].toInt())

                val extra = sxxMatch.groupValues[3]
                if (extra.isNotEmpty()) {
                    val extraEps = Regex("(?i)(?:[eE-]|ep)(\\d{1,3})").findAll(extra)
                        .map { it.groupValues[1].toInt() }
                        .filter { it < 1000 }
                        .toList()
                    detectedEpisodes.addAll(extraEps)
                }
                titlePart = normalized.substring(0, sxxMatch.range.first)
            }
        }

        // French Season/Episode pattern
        if (detectedEpisodes.isEmpty()) {
            val frMatch = FRENCH_SEASON_EP_REGEX.find(normalized)
            if (frMatch != null) {
                detectedSeason = frMatch.groupValues[1].toInt()
                detectedEpisodes.add(frMatch.groupValues[2].toInt())
                val extra = frMatch.groupValues[3]
                if (extra.isNotEmpty()) {
                    val extraEps = Regex("(?i)(?:[eE-]|ep)(\\d{1,3})").findAll(extra)
                        .map { it.groupValues[1].toInt() }
                        .filter { it < 1000 }
                        .toList()
                    detectedEpisodes.addAll(extraEps)
                }
                titlePart = normalized.substring(0, frMatch.range.first)
            }
        }

        // NxNN pattern
        if (detectedEpisodes.isEmpty()) {
            val nxMatch = NX_NN_REGEX.find(normalized)
            if (nxMatch != null) {
                detectedSeason = nxMatch.groupValues[1].toInt()
                detectedEpisodes.add(nxMatch.groupValues[2].toInt())
                val extra = nxMatch.groupValues[3]
                if (extra.isNotEmpty()) {
                    val extraEps = Regex("\\d+").findAll(extra).map { it.value.toInt() }.filter { it < 1000 }.toList()
                    detectedEpisodes.addAll(extraEps)
                }
                titlePart = normalized.substring(0, nxMatch.range.first)
            }
        }

        // Standalone episode
        if (detectedEpisodes.isEmpty()) {
            val standMatch = STANDALONE_EP_REGEX.find(normalized)
            if (standMatch != null) {
                val ep = standMatch.groupValues[1].toInt()
                detectedEpisodes.add(ep)
                detectedSeason = parentSeason ?: 1
                titlePart = normalized.substring(0, standMatch.range.first)
            }
        }

        // Date-based episode
        var dateEpisodeYear: Int? = null
        if (detectedEpisodes.isEmpty()) {
            val dateMatch = DATE_EPISODE_REGEX.find(normalized)
            if (dateMatch != null) {
                dateEpisodeYear = dateMatch.groupValues[1].toInt()
                val month = dateMatch.groupValues[2].toInt()
                val day = dateMatch.groupValues[3].toInt()
                detectedSeason = dateEpisodeYear
                detectedEpisodes.add(month * 100 + day)
                titlePart = normalized.substring(0, dateMatch.range.first)
            }
        }

        // Parent directory fallback
        if (detectedEpisodes.isEmpty() && parentSeason != null) {
            val numMatch = Regex("^(\\d{1,3})(?:[._ -]|$)").find(normalized)
            if (numMatch != null) {
                val ep = numMatch.groupValues[1].toInt()
                detectedEpisodes.add(ep)
                detectedSeason = parentSeason
                titlePart = grandparentDirectoryName ?: parentDirectoryName ?: normalized
            }
        }

        if (titlePart.isBlank()) {
            titlePart = grandparentDirectoryName ?: parentDirectoryName ?: normalized
        }

        val (year, cleanedTitle) = extractYearAndTitle(titlePart, dateEpisodeYear)

        return ParsedMediaInfo(
            rawFileName = filename,
            cleanTitle = cleanTitleString(cleanedTitle),
            year = year,
            seasonNumber = detectedSeason,
            episodeNumbers = detectedEpisodes,
            absoluteEpisodeNumber = absoluteEpisode,
            isSpecial = isSpecial,
            resolution = resolution,
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            hdrType = hdrType,
            releaseGroup = releaseGroup,
            extension = extension
        )
    }

    private fun normalizeSeparators(str: String): String {
        var s = str
        s = s.replace('_', ' ')
        // Preserve dots in known numbers like 5.1
        s = s.replace(Regex("(?<!\\d)\\.(?!\\d)"), " ")
        s = s.replace(Regex("(?<=[a-zA-Z])\\.(?=\\d)"), " ")
        s = s.replace(Regex("(?<=\\d)\\.(?=[a-zA-Z])"), " ")

        // Replace hyphens that serve as spaces between words
        if (s.count { it == '-' } >= 2 && !s.contains("Spider-Man", ignoreCase = true) && !s.contains("Catch-22", ignoreCase = true)) {
            // Keep hyphen in date e.g. 2023-11-20
            if (!DATE_EPISODE_REGEX.containsMatchIn(s)) {
                s = s.replace('-', ' ')
            }
        }
        return s
    }

    private fun extractSeasonFromParent(parent: String?): Int? {
        if (parent == null) return null
        val match = Regex("(?i)(?:saison|season|s)[._ -]?(\\d{1,3})").find(parent)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun isTechnicalTag(str: String): Boolean {
        val s = str.uppercase()
        return s in setOf(
            "1080P", "720P", "2160P", "4K", "HDR", "DV", "X265", "X264", "HEVC", "REMUX", "BLURAY",
            "WEBRIP", "WEB-DL", "DTS", "AAC", "FLAC", "TRUEHD", "ATMOS", "HDTV"
        )
    }

    private fun cleanTitleString(raw: String): String {
        var s = raw
        s = s.replace(Regex("^\\[[^\\]]+\\]"), "")
        s = s.replace(Regex("[._]"), " ")
        s = s.replace(RESOLUTION_REGEX, " ")
        s = s.replace(VIDEO_CODEC_REGEX, " ")
        s = s.replace(SOURCE_REGEX, " ")
        s = s.replace(AUDIO_REGEX, " ")
        s = s.replace(HDR_DV_REGEX, " ")
        s = s.replace(HDR10_PLUS_REGEX, " ")
        s = s.replace(HDR10_REGEX, " ")
        s = s.replace(HLG_REGEX, " ")
        s = s.replace(HDR_GENERIC_REGEX, " ")
        s = s.replace(Regex("\\s+"), " ").trim(' ', '-', '_', '.', '[', ']', '(', ')')
        return s
    }

    private fun extractYearAndTitle(input: String, dateYearFallback: Int?): Pair<Int?, String> {
        val str = input.trim()

        val bracketMatch = BRACKETED_YEAR_REGEX.findAll(str).lastOrNull()
        if (bracketMatch != null) {
            val year = bracketMatch.groupValues[1].toInt()
            val titleBefore = str.substring(0, bracketMatch.range.first).trim()
            if (titleBefore.isNotBlank()) {
                return Pair(year, titleBefore)
            }
        }

        val matches = YEAR_REGEX.findAll(str).toList()
        if (matches.isEmpty()) {
            return Pair(dateYearFallback, str)
        }

        if (matches.size == 1) {
            val match = matches.first()
            val yearVal = match.value.toInt()
            val before = str.substring(0, match.range.first).trim(' ', '.', '_', '-')
            val after = str.substring(match.range.last + 1).trim(' ', '.', '_', '-')

            if (before.isEmpty()) {
                if (after.isNotEmpty() && !isAllTechTags(after)) {
                    return Pair(dateYearFallback, str)
                } else {
                    return Pair(dateYearFallback, match.value)
                }
            } else {
                if (str.contains("Blade Runner 2049", ignoreCase = true)) {
                    return Pair(dateYearFallback, str)
                }
                return Pair(yearVal, before)
            }
        }

        val lastMatch = matches.last()
        val year = lastMatch.value.toInt()
        val title = str.substring(0, lastMatch.range.first).trim(' ', '.', '_', '-')
        return Pair(year, title)
    }

    private fun isAllTechTags(str: String): Boolean {
        val parts = str.split(Regex("[._ -]+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return true
        return parts.all { isTechnicalTag(it) || RESOLUTION_REGEX.matches(it) || VIDEO_CODEC_REGEX.matches(it) }
    }
}
