package com.lecteur.core.data.playback

import com.lecteur.core.database.dao.LibraryFolderDao
import com.lecteur.core.database.dao.MediaFileDao
import com.lecteur.core.database.entity.LibraryFolderEntity
import com.lecteur.core.database.entity.MediaFileEntity
import com.lecteur.core.model.MediaCategory
import javax.inject.Inject

/**
 * Makes sure a file opened by hand (picker, "open with") has a media_files row, so that its progress,
 * track choices and delays are saved like those of a scanned file.
 *
 * Such files live in a hidden, disabled pseudo-folder: the scanner (which only walks enabled folders)
 * never touches it and the library does not list it as a source.
 */
class MediaFileRegistrar @Inject constructor(
    private val folderDao: LibraryFolderDao,
    private val mediaFileDao: MediaFileDao
) {
    /** Returns the row for [uri]; a file already known by location or by content fingerprint keeps its row (and progress). */
    suspend fun register(uri: String, info: UriFileInfo): MediaFileEntity {
        val existing = mediaFileDao.getMediaFileByUri(uri) ?: mediaFileDao.getMediaFileByFingerprint(info.fingerprint)

        val entity = existing?.copy(
            uri = uri,
            displayPath = StoragePaths.fromUri(uri) ?: existing.displayPath,
            fileName = info.displayName,
            size = info.sizeBytes,
            lastModified = info.lastModified,
            fingerprint = info.fingerprint,
            isAvailable = true
        ) ?: MediaFileEntity(
            folderId = manualFolderId(),
            uri = uri,
            displayPath = StoragePaths.fromUri(uri) ?: uri,
            fileName = info.displayName,
            size = info.sizeBytes,
            fingerprint = info.fingerprint,
            lastModified = info.lastModified
        )

        val insertedId = mediaFileDao.insertMediaFile(entity)
        return entity.copy(id = existing?.id ?: insertedId)
    }

    private suspend fun manualFolderId(): Long {
        folderDao.getFolderByUri(MANUAL_FOLDER_URI)?.let { return it.id }
        return folderDao.insertFolder(
            LibraryFolderEntity(
                uri = MANUAL_FOLDER_URI,
                displayPath = "Fichiers ouverts manuellement",
                category = MediaCategory.GENERIC,
                enabled = false
            )
        )
    }

    companion object {
        const val MANUAL_FOLDER_URI = "lecteur://manual"
    }
}
