package com.lecteur.core.model

import kotlinx.serialization.Serializable

@Serializable
data class LibraryFolder(
    val id: Long = 0,
    val uri: String,
    val displayPath: String,
    val category: MediaCategory,
    val enabled: Boolean = true,
    val lastScannedAt: Long? = null
)
