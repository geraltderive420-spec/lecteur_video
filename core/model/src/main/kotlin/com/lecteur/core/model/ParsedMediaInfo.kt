package com.lecteur.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ParsedMediaInfo(
    val rawFileName: String,
    val cleanTitle: String,
    val year: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumbers: List<Int> = emptyList(),
    val absoluteEpisodeNumber: Int? = null,
    val isSpecial: Boolean = false,
    val isSeries: Boolean = seasonNumber != null || episodeNumbers.isNotEmpty() || absoluteEpisodeNumber != null,
    val resolution: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val hdrType: HdrType = HdrType.NONE,
    val releaseGroup: String? = null,
    val extension: String? = null
) {
    val primaryEpisodeNumber: Int?
        get() = episodeNumbers.firstOrNull() ?: absoluteEpisodeNumber
}
