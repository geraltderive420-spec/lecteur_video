package com.lecteur.core.data.shared

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lecteur.core.data.library.AddFolderResult
import com.lecteur.core.data.library.FolderRepository
import com.lecteur.core.model.LibraryFolder
import com.lecteur.core.model.MediaCategory
import com.lecteur.core.model.MediaKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** A file of a PC and where it lands under the phone's library folder. [directories] are relative to that folder. */
data class CopySource(
    val url: String,
    val kind: MediaKind,
    /** "Title (year)" or "Series · S01E02 · Episode": the notification's text. */
    val title: String,
    val directories: List<String>,
    val fileName: String,
    val sizeBytes: Long,
    val subtitles: List<Subtitle>
) {
    data class Subtitle(val url: String, val fileName: String)
}

/** A folder of the phone a copy writes into: the tree it writes in, and the library folder to scan afterwards. */
data class CopyTarget(val treeUri: String, val folderId: Long, val label: String)

/** Runs the copies in the background, one after the other (implemented with WorkManager by the scanner module). */
interface PhoneCopyQueue {
    fun enqueue(serverId: String, fileIds: List<Long>, target: CopyTarget)
}

object CopyPaths {
    private val FORBIDDEN = Regex("""[\\/:*?"<>|\u0000-\u001f]""")

    /** A name every file system takes: no separators or reserved characters, no trailing dot or space (Windows, FAT). */
    fun safeName(name: String): String = name.replace(FORBIDDEN, " ").replace(Regex(" {2,}"), " ").trim().trimEnd('.', ' ').ifEmpty { "Sans titre" }.take(120)
}

/**
 * Copies of a PC's files onto the phone, into the library folder of the right kind ("Films" for a film, "Séries" for an
 * episode), so that they join the phone's library at the next scan and play without the PC.
 *
 * Library folders are read-only grants until a copy needs one: the first copy of a kind asks the user to pick (or confirm)
 * the folder, which then gets a lasting write grant.
 */
@Singleton
class PhoneCopies @Inject constructor(
    @ApplicationContext private val context: Context,
    private val folders: FolderRepository,
    private val queue: PhoneCopyQueue
) {
    /** The folder the files of [kind] go to, when the app may write in it; null asks for [pickHint] first. */
    suspend fun target(kind: MediaKind): CopyTarget? {
        val writable = context.contentResolver.persistedUriPermissions.filter { it.isWritePermission }.map { it.uri.toString() }
        candidates(kind).forEach { folder ->
            // The library folder itself, or a folder inside it picked for an earlier copy
            val tree = writable.firstOrNull { it == folder.uri } ?: writable.firstOrNull { isInside(it, folder.uri) }
            if (tree != null) return CopyTarget(tree, folder.id, folder.displayPath)
        }
        return null
    }

    /** The folder to open the picker on: the library folder of that kind, whose write grant is missing. */
    suspend fun pickHint(kind: MediaKind): String? = candidates(kind).firstOrNull()?.uri

    /**
     * The folder the user picked for [kind]: kept with a write grant, and added to the library when it is not in it yet.
     * Returns the target, or a message saying why that folder cannot be used.
     */
    suspend fun adopt(treeUri: String, kind: MediaKind): Result<CopyTarget> {
        val uri = Uri.parse(treeUri)
        val granted = runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.isSuccess
        if (!granted) return Result.failure(IllegalStateException("Ce dossier n'accepte pas l'écriture. Choisissez-en un autre."))

        val existing = folders.folders.first().map { it.folder }
        existing.firstOrNull { it.uri == treeUri && suits(it.category, kind) }?.let { return Result.success(CopyTarget(it.uri, it.id, it.displayPath)) }
        return when (val added = folders.add(treeUri, category(kind))) {
            is AddFolderResult.Added -> Result.success(CopyTarget(treeUri, added.folderId, label(treeUri, added.folderId)))
            is AddFolderResult.AlreadyAdded -> Result.success(CopyTarget(treeUri, added.existing.id, added.existing.displayPath))
            // Inside a folder of the library already: write there, the scan of the enclosing folder finds the copy
            is AddFolderResult.Overlaps ->
                if (suits(added.existing.category, kind) && isInside(treeUri, added.existing.uri)) Result.success(CopyTarget(treeUri, added.existing.id, added.existing.displayPath))
                else Result.failure(IllegalStateException("Ce dossier chevauche « ${added.existing.displayPath} » de la bibliothèque. Choisissez ce dossier-là ou un dossier à part."))
        }
    }

    fun enqueue(serverId: String, fileIds: List<Long>, target: CopyTarget) {
        if (fileIds.isNotEmpty()) queue.enqueue(serverId, fileIds, target)
    }

    /** Folders of that kind first, then the "Mixte" ones, which take everything. */
    private suspend fun candidates(kind: MediaKind): List<LibraryFolder> {
        val all = folders.folders.first().map { it.folder }.filter { it.enabled && suits(it.category, kind) }
        return all.sortedBy { if (it.category == MediaCategory.GENERIC) 1 else 0 }
    }

    private fun canWrite(uri: String): Boolean = context.contentResolver.persistedUriPermissions.any { it.uri.toString() == uri && it.isWritePermission }

    private suspend fun label(uri: String, id: Long): String = folders.folders.first().firstOrNull { it.folder.id == id }?.folder?.displayPath ?: uri

    private fun isInside(child: String, parent: String): Boolean {
        val c = Uri.decode(child.substringAfter("/tree/", ""))
        val p = Uri.decode(parent.substringAfter("/tree/", "")).trimEnd('/')
        return child.substringBefore("/tree/") == parent.substringBefore("/tree/") && (c == p || c.startsWith("$p/"))
    }

    private companion object {
        fun category(kind: MediaKind) = if (kind == MediaKind.MOVIE) MediaCategory.MOVIES else MediaCategory.SERIES

        fun suits(category: MediaCategory, kind: MediaKind) = when (kind) {
            MediaKind.MOVIE -> category == MediaCategory.MOVIES || category == MediaCategory.GENERIC
            else -> category == MediaCategory.SERIES || category == MediaCategory.ANIME || category == MediaCategory.GENERIC
        }
    }
}
