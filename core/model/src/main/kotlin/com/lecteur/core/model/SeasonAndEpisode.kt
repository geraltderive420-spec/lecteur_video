package com.lecteur.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Season(
    val id: Long = 0,
    val seriesId: Long,
    val seasonNumber: Int,
    val name: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val airDate: String? = null
)

@Serializable
data class Episode(
    val id: Long = 0,
    val seriesId: Long,
    val seasonId: Long,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val absoluteNumber: Int? = null,
    val title: String? = null,
    val overview: String? = null,
    val stillPath: String? = null,
    val airDate: String? = null,
    val runtimeMinutes: Int? = null
)
