package com.lecteur.core.data.browse

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.lecteur.core.common.scan.VideoFiles
import com.lecteur.core.common.text.NaturalOrder
import com.lecteur.core.data.scan.DocumentsContractLister
import com.lecteur.core.data.scan.DocumentsContractWalker
import com.lecteur.core.database.dao.LibraryFolderDao
import com.lecteur.core.database.dao.MediaFileDao
import com.lecteur.core.model.PlayPlan
import com.lecteur.core.model.QueueEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.random.Random

/** A line of the folder explorer. */
data class BrowseEntry(
    val documentId: String,
    val name: String,
    val isDirectory: Boolean,
    val isVideo: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    /** Document URI of a file, the one the library stores (so progress is shared with the library); null for folders. */
    val uri: String?,
    /** True/false when the library holds this file (watched or not); null for files it does not know. */
    val watched: Boolean? = null,
    /** Fraction played of a file being watched, 0 otherwise. */
    val progress: Float = 0f
)

data class DirectoryListing(
    val treeUri: String,
    val documentId: String,
    val entries: List<BrowseEntry>
) {
    val videos: List<BrowseEntry> get() = entries.filter { it.isVideo }
}

/** A watched folder as the entry point of the explorer. */
data class BrowseRoot(val treeUri: String, val displayPath: String, val rootDocumentId: String)

/** Lists the content of a watched folder as it is on disk, identified or not. */
interface DirectoryBrowser {
    /** Null when the folder cannot be read (drive unplugged, permission revoked). */
    suspend fun list(treeUri: String, documentId: String): DirectoryListing?
}

class DocumentsContractBrowser @Inject constructor(
    @ApplicationContext private val context: Context
) : DirectoryBrowser {

    private val lister = DocumentsContractLister(context)

    override suspend fun list(treeUri: String, documentId: String): DirectoryListing? = withContext(Dispatchers.IO) {
        val tree = Uri.parse(treeUri)
        val children = lister.list(tree, documentId) ?: return@withContext null
        DirectoryListing(
            treeUri = treeUri,
            documentId = documentId,
            entries = children.map { child ->
                BrowseEntry(
                    documentId = child.documentId,
                    name = child.name,
                    isDirectory = child.isDirectory,
                    isVideo = !child.isDirectory && VideoFiles.isVideo(child.name),
                    sizeBytes = child.size,
                    lastModified = child.lastModified,
                    uri = if (child.isDirectory) null else DocumentsContract.buildDocumentUriUsingTree(tree, child.documentId).toString()
                )
            }
        )
    }
}

/** Explorer logic on top of a [DirectoryBrowser]: ordering, library progress, and the play plans of "read this folder". */
class BrowseRepository @Inject constructor(
    private val browser: DirectoryBrowser,
    private val folderDao: LibraryFolderDao,
    private val mediaFileDao: MediaFileDao
) {

    /** The watched folders, one entry per location even when it is watched under several categories. */
    suspend fun roots(): List<BrowseRoot> =
        folderDao.getUserFolders().distinctBy { it.uri }.mapNotNull { folder ->
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(folder.uri)) }.getOrNull() ?: return@mapNotNull null
            BrowseRoot(folder.uri, folder.displayPath, rootId)
        }

    /** The folder's content, sub-folders first, each group in natural order, with what the library knows about the files. */
    suspend fun open(treeUri: String, documentId: String): DirectoryListing? {
        val listing = browser.list(treeUri, documentId) ?: return null
        val visible = listing.entries.filter { !it.isDirectory || !DocumentsContractWalker.isIgnoredDirectory(it.name) }
        val progress = libraryProgress(treeUri)
        val decorated = visible.map { entry ->
            val known = entry.uri?.let(progress::get)
            if (known == null) entry else entry.copy(watched = known.watched, progress = known.progress)
        }
        return listing.copy(entries = sort(decorated))
    }

    /** Plan for "Lire le dossier": the videos of one folder in natural order, starting at [start] (a document id) or the first. */
    fun planFor(listing: DirectoryListing, label: String?, start: String? = null, shuffle: Boolean = false, random: Random = Random.Default): PlayPlan? {
        val videos = listing.videos.mapNotNull { video -> video.uri?.let { QueueEntry(it, video.name) } }
        if (videos.isEmpty()) return null
        val startIndex = when {
            start != null -> listing.videos.indexOfFirst { it.documentId == start }.coerceAtLeast(0)
            shuffle -> random.nextInt(videos.size)
            else -> 0
        }
        return PlayPlan(videos, startIndex, label, shuffle)
    }

    private class Known(val watched: Boolean, val progress: Float)

    private suspend fun libraryProgress(treeUri: String): Map<String, Known> {
        val folderIds = folderDao.getFoldersByUri(treeUri).map { it.id }
        if (folderIds.isEmpty()) return emptyMap()
        return mediaFileDao.progressForFolders(folderIds).associate { row ->
            val fraction = if (row.durationMs > 0) (row.positionMs.toFloat() / row.durationMs).coerceIn(0f, 1f) else 0f
            row.uri to Known(row.completed, if (row.completed) 0f else fraction)
        }
    }

    companion object {
        /** Folders before files, then natural order ("Episode 2" before "Episode 10"). */
        fun sort(entries: List<BrowseEntry>): List<BrowseEntry> =
            entries.sortedWith(compareBy<BrowseEntry> { !it.isDirectory }.thenComparator { a, b -> NaturalOrder.compare(a.name, b.name) })
    }
}
