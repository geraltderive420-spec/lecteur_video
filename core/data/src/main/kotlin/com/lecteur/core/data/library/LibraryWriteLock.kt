package com.lecteur.core.data.library

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serialises the multi-statement library writes of the scanner (filing a file under a movie or series) and of the
 * identifier (merging rows, rebuilding episode trees), which run in separate workers and would otherwise race on the
 * same rows. Only the short database phases hold it; network calls and file inspection never do.
 */
@Singleton
class LibraryWriteLock @Inject constructor() {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
