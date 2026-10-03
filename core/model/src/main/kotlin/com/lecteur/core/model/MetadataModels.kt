package com.lecteur.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Person(
    val id: Long = 0,
    val tmdbId: Long? = null,
    val name: String,
    val profilePath: String? = null
)

@Serializable
data class CastMember(
    val id: Long = 0,
    val personId: Long,
    val personName: String,
    val profilePath: String? = null,
    val movieId: Long? = null,
    val seriesId: Long? = null,
    val character: String? = null,
    val order: Int = 0,
    val isDirector: Boolean = false
)

@Serializable
data class Genre(
    val id: Long = 0,
    val tmdbId: Long? = null,
    val name: String
)

@Serializable
data class MediaCollection(
    val id: Long = 0,
    val tmdbId: Long? = null,
    val name: String,
    val overview: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null
)

@Serializable
data class UserList(
    val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class UserListItem(
    val id: Long = 0,
    val listId: Long,
    val movieId: Long? = null,
    val seriesId: Long? = null,
    val addedAt: Long = System.currentTimeMillis()
)

@Serializable
data class SeriesPreference(
    val seriesId: Long,
    val preferredAudioLanguage: String? = null,
    val preferredSubtitleLanguage: String? = null
)
