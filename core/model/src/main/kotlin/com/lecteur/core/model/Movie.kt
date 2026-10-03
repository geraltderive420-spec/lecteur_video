package com.lecteur.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Movie(
    val id: Long = 0,
    val tmdbId: Long? = null,
    val imdbId: String? = null,
    val title: String,
    val originalTitle: String? = null,
    val sortTitle: String = title,
    val year: Int? = null,
    val releaseDate: String? = null,
    val overview: String? = null,
    val runtimeMinutes: Int? = null,
    val rating: Float? = null,
    val certification: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val logoPath: String? = null,
    val trailerKey: String? = null,
    val collectionId: Long? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val matchState: MatchState = MatchState.UNIDENTIFIED,
    val matchLocked: Boolean = false
)
