package com.lecteur.core.data.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lecteur.core.data.playback.MediaFileRegistrar.Companion.MANUAL_FOLDER_URI
import com.lecteur.core.data.playback.StoragePaths
import com.lecteur.core.database.dao.LibraryFolderDao
import com.lecteur.core.database.dao.MediaFileDao
import com.lecteur.core.database.dao.MovieDao
import com.lecteur.core.database.dao.SeriesDao
import com.lecteur.core.database.entity.LibraryFolderEntity
import com.lecteur.core.model.LibraryFolder
import com.lecteur.core.model.MediaCategory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.net.URLDecoder
import javax.inject.Inject

data class FolderStatus(
    val folder: LibraryFolder,
    val fileCount: Int,
    val availableCount: Int
) {
    val isPaused: Boolean get() = !folder.enabled

    /** Some files are missing (drive unplugged, files deleted) while others are still there or were there. */
    val hasUnavailableFiles: Boolean get() = availableCount < fileCount
}

sealed interface AddFolderResult {
    data class Added(val folderId: Long) : AddFolderResult
    /** The folder is, contains, or sits inside a folder that is already watched: it would list every file twice. */
    data class Overlaps(val existing: LibraryFolder) : AddFolderResult

    /** This exact folder is already watched with this category. */
    data class AlreadyAdded(val existing: LibraryFolder) : AddFolderResult
}

/** Grants that outlive the picker: without them every watched folder would be unreadable after a reboot. */
interface UriPermissions {
    fun persist(uri: String): Boolean
    fun release(uri: String)
}

class AndroidUriPermissions @Inject constructor(
    @ApplicationContext private val context: Context
) : UriPermissions {

    override fun persist(uri: String): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    }.getOrDefault(false)

    override fun release(uri: String) {
        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
}

object TreeOverlap {

    private fun parts(treeUri: String): Pair<String, String>? {
        val authority = treeUri.substringAfter("://", "").substringBefore('/')
        val id = treeUri.substringAfter("/tree/", "").substringBefore('/')
        if (authority.isEmpty() || id.isEmpty()) return null
        return authority to URLDecoder.decode(id, "UTF-8").trimEnd('/')
    }

    /** True when one tree is the other or contains it: same provider, and one document id is a path prefix of the other. */
    fun overlaps(a: String, b: String): Boolean {
        val (authorityA, idA) = parts(a) ?: return a == b
        val (authorityB, idB) = parts(b) ?: return a == b
        if (authorityA != authorityB) return false
        return idA == idB || idA.startsWith("$idB/") || idB.startsWith("$idA/")
    }
}

class FolderRepository @Inject constructor(
    private val folderDao: LibraryFolderDao,
    private val mediaFileDao: MediaFileDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val permissions: UriPermissions
) {

    val folders: Flow<List<FolderStatus>> = combine(folderDao.observeUserFolders(), mediaFileDao.observeFolderCounts()) { folders, counts ->
        val byFolder = counts.associateBy { it.folderId }
        folders.map { FolderStatus(it.toModel(), byFolder[it.id]?.fileCount ?: 0, byFolder[it.id]?.availableCount ?: 0) }
    }

    /**
     * Ids of the folders a scan should visit: all enabled ones, or the one asked for. A folder scanned on request is scanned
     * even when paused: pausing only leaves it out of the automatic scans.
     */
    suspend fun scanTargets(folderId: Long?): List<Long> =
        if (folderId == null) folderDao.getActiveFolders().filter { it.uri != MANUAL_FOLDER_URI }.map { it.id }
        else listOfNotNull(folderDao.getFolderById(folderId)?.id)

    suspend fun add(treeUri: String, category: MediaCategory, displayName: String? = null): AddFolderResult {
        val overlapping = folderDao.getUserFolders().filter { TreeOverlap.overlaps(it.uri, treeUri) }
        overlapping.firstOrNull { it.uri == treeUri && it.category == category }?.let { return AddFolderResult.AlreadyAdded(it.toModel()) }
        // The same folder under another category is welcome (one entry per category); anything else overlapping would double-list files.
        // "Mixte" already takes everything, so it cannot share a folder with specific categories.
        overlapping.firstOrNull { it.uri != treeUri || it.category == MediaCategory.GENERIC || category == MediaCategory.GENERIC }
            ?.let { return AddFolderResult.Overlaps(it.toModel()) }

        permissions.persist(treeUri)
        val id = folderDao.insertFolder(
            LibraryFolderEntity(
                uri = treeUri,
                displayPath = StoragePaths.fromUri(treeUri) ?: displayName ?: Uri.decode(treeUri.substringAfter("/tree/", treeUri)),
                category = category
            )
        )
        return AddFolderResult.Added(id)
    }

    /** Pausing keeps everything in the library but leaves the folder out of scans. */
    suspend fun setPaused(folderId: Long, paused: Boolean) = folderDao.setEnabled(folderId, !paused)

    /**
     * A different category means different rules (a "Séries" folder files episodes, a "Films" one files movies):
     * the folder's files are detached and filed again by the next scan, which the caller must trigger.
     */
    suspend fun setCategory(folderId: Long, category: MediaCategory) {
        val current = folderDao.getFolderById(folderId) ?: return
        if (current.category == category) return
        // Same rule as adding: no second entry with that category for this folder, no mixing "Mixte" with specific ones
        val siblings = folderDao.getFoldersByUri(current.uri).filter { it.id != folderId }
        if (siblings.any { it.category == category || it.category == MediaCategory.GENERIC || category == MediaCategory.GENERIC }) return
        folderDao.setCategory(folderId, category)
        mediaFileDao.unlinkFolder(folderId)
        removeOrphans()
    }

    /** Removes the folder from the library and its files with it (their watch progress included). The files on disk are untouched. */
    suspend fun remove(folderId: Long) {
        val folder = folderDao.getFolderById(folderId) ?: return
        folderDao.deleteFolder(folderId)
        permissions.release(folder.uri)
        removeOrphans()
    }

    private suspend fun removeOrphans() {
        movieDao.deleteOrphanMovies()
        seriesDao.deleteOrphanSeries()
    }
}

private fun LibraryFolderEntity.toModel() = LibraryFolder(id, uri, displayPath, category, enabled, lastScannedAt)
