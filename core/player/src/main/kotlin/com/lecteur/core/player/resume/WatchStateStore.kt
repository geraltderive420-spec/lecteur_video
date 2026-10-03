package com.lecteur.core.player.resume

import com.lecteur.core.model.WatchState

/** Port to persistence, implemented on top of Room in core:data. */
interface WatchStateStore {
    suspend fun get(mediaFileId: Long): WatchState?
    suspend fun save(state: WatchState)
}
