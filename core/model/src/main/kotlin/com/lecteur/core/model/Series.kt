package com.lecteur.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Series(
    val id: Long = 0,
    val tmdbId: Long? = null,
    val imdbId: String? = null,
    val title: String,
    val originalTitle: String? = null,
    val sortTitle: String = title,
    val firstAirDate: String? = null,
    val status: String? = null,
    val overview: String? = null,
    val rating: Float? = null,
    val certification: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val logoPath: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val matchState: MatchState = MatchState.UNIDENTIFIED,
    val matchLocked: Boolean = false
)
