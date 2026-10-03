package com.lecteur.core.data.scan

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.lecteur.core.common.scan.FoundFile
import com.lecteur.core.common.scan.VideoFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class WalkedEntry(val uri: String, val name: String, val size: Long, val lastModified: Long)

/** One directory of the walk; [relativePath] is "" for the root and uses '/' otherwise. */
data class WalkedDirectory(val relativePath: String, val entries: List<WalkedEntry>)

data class WalkResult(
    /** False when the root itself could not be listed (drive unplugged, permission revoked). */
    val reachable: Boolean,
    /** False when some sub-directory could not be listed: absent files must not be declared gone. */
    val complete: Boolean,
    val directories: List<WalkedDirectory>,
    /** Name of the root folder, the first segment of every [FoundFile.relativePath]. */
    val rootName: String
) {
    val videoFiles: List<FoundFile> by lazy {
        directories.flatMap { directory ->
            directory.entries.filter { VideoFiles.isVideo(it.name) }.map {
                val path = if (directory.relativePath.isEmpty()) "$rootName/${it.name}" else "$rootName/${directory.relativePath}/${it.name}"
                FoundFile(it.uri, it.name, it.size, it.lastModified, path)
            }
        }
    }

    companion object {
        fun unreachable(rootName: String) = WalkResult(false, false, emptyList(), rootName)
    }
}

class TreeChild(val documentId: String, val name: String, val isDirectory: Boolean, val size: Long, val lastModified: Long)

/** Depth-first walk of a document tree down to its leaves, whatever the provider. Pure: the listing is injected. */
object TreeTraversal {

    private class Pending(val documentId: String, val relativePath: String)

    suspend fun walk(
        rootId: String,
        rootName: String,
        list: suspend (documentId: String) -> List<TreeChild>?,
        uriOf: (documentId: String) -> String
    ): WalkResult {
        val directories = ArrayList<WalkedDirectory>()
        val stack = ArrayDeque<Pending>().apply { addLast(Pending(rootId, "")) }
        var complete = true

        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val current = stack.removeLast()
            val listing = list(current.documentId)

            if (listing == null) {
                if (current.relativePath.isEmpty()) return WalkResult.unreachable(rootName)
                complete = false
                continue
            }

            val files = ArrayList<WalkedEntry>()
            for (child in listing) {
                if (child.isDirectory) {
                    if (!DocumentsContractWalker.isIgnoredDirectory(child.name)) {
                        val path = if (current.relativePath.isEmpty()) child.name else "${current.relativePath}/${child.name}"
                        stack.addLast(Pending(child.documentId, path))
                    }
                } else {
                    files += WalkedEntry(uriOf(child.documentId), child.name, child.size, child.lastModified)
                }
            }
            directories += WalkedDirectory(current.relativePath, files)
        }
        return WalkResult(reachable = true, complete = complete, directories = directories, rootName = rootName)
    }
}

/** Lists a watched folder. The only implementation reads Storage Access Framework trees; SMB/WebDAV would be others (version 2). */
interface FolderWalker {
    suspend fun walk(treeUri: String, rootName: String): WalkResult
}

/**
 * Walks a SAF tree with direct `DocumentsContract` child queries: one cursor per directory carrying name, type,
 * size and date in the same round trip. `DocumentFile.listFiles()` does one query per attribute per file, which is
 * what makes it unusable on thousands of files.
 */
class DocumentsContractWalker @Inject constructor(
    @ApplicationContext private val context: Context
) : FolderWalker {

    private val lister = DocumentsContractLister(context)

    override suspend fun walk(treeUri: String, rootName: String): WalkResult = withContext(Dispatchers.IO) {
        val tree = Uri.parse(treeUri)
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
            ?: return@withContext WalkResult.unreachable(rootName)

        TreeTraversal.walk(
            rootId = rootId,
            rootName = rootName,
            list = { documentId -> lister.list(tree, documentId) },
            uriOf = { documentId -> DocumentsContract.buildDocumentUriUsingTree(tree, documentId).toString() }
        )
    }

    companion object {
        /** Hidden folders and the clutter NAS boxes and Windows leave behind. */
        fun isIgnoredDirectory(name: String): Boolean =
            name.startsWith(".") || name.startsWith("@") || name.startsWith("$") || name.equals("lost+found", ignoreCase = true)
    }
}

/** One directory level of a SAF tree, read with a single cursor; shared by the scanner's walk and the folder explorer. */
class DocumentsContractLister(private val context: Context) {

    /** Null when the directory cannot be read at all. */
    fun list(tree: Uri, documentId: String): List<TreeChild>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        return try {
            context.contentResolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
                val result = ArrayList<TreeChild>(cursor.count.coerceAtLeast(0))
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1) ?: continue
                    result += TreeChild(
                        documentId = cursor.getString(0) ?: continue,
                        name = name,
                        isDirectory = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                        size = if (cursor.isNull(3)) 0L else cursor.getLong(3),
                        lastModified = if (cursor.isNull(4)) 0L else cursor.getLong(4)
                    )
                }
                result
            }
        } catch (e: Exception) {
            // SecurityException (permission revoked), IllegalArgumentException (volume gone), provider crashes...
            null
        }
    }

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
    }
}
