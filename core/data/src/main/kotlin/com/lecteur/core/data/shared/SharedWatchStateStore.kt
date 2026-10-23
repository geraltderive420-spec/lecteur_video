package com.lecteur.core.data.shared

import com.lecteur.core.data.watch.RoomWatchStateStore
import com.lecteur.core.database.dao.MediaFileDao
import com.lecteur.core.model.WatchState
import com.lecteur.core.player.resume.WatchStateStore
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * The watch store every player uses: progress is saved locally as before and, for a file streamed from a shared library,
 * also sent to its PC. The players need not know where a file comes from.
 */
class SharedWatchStateStore @Inject constructor(
    private val local: RoomWatchStateStore,
    private val mediaFileDao: MediaFileDao,
    private val shared: SharedLibraryRepository
) : WatchStateStore {
    /** media_files id → its URI when it is a shared stream, "" otherwise: looked up once per file, not every 5 s. */
    private val uris = ConcurrentHashMap<Long, String>()

    override suspend fun get(mediaFileId: Long): WatchState? = local.get(mediaFileId)

    override suspend fun save(state: WatchState) {
        local.save(state)
        val uri = uris[state.mediaFileId] ?: (mediaFileDao.getMediaFileById(state.mediaFileId)?.uri?.takeIf(shared::isShared) ?: "")
            .also { uris[state.mediaFileId] = it }
        if (uri.isNotEmpty()) shared.report(uri, state)
    }
}
