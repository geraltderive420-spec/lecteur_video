package com.lecteur.core.data.playback

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.lecteur.core.common.file.FileFingerprint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import javax.inject.Inject

data class UriFileInfo(
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val fingerprint: String
)

/** Reads name, size and fingerprint of a file picked through the system file picker or opened from another app. */
class UriFileInfoReader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun read(uriString: String): UriFileInfo = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        var name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "video"
        var size = -1L
        var modified = 0L

        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }
                        ?.let { cursor.getString(it)?.let { n -> name = n } }
                    cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }
                        ?.let { if (!cursor.isNull(it)) size = cursor.getLong(it) }
                    cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 }
                        ?.let { if (!cursor.isNull(it)) modified = cursor.getLong(it) }
                }
            }
        }

        if (uri.scheme == "file") {
            // file:// URIs carry no OpenableColumns
            uri.path?.let(::File)?.takeIf { it.isFile }?.let { file ->
                name = file.name
                size = file.length()
                modified = file.lastModified()
            }
        }

        val fingerprint = fingerprint(uri, size)
        UriFileInfo(name, size.coerceAtLeast(0), modified, fingerprint ?: FileFingerprint.fromLocation(uriString, size))
    }

    /** Content fingerprint of a file already known by size (the scanner got it from the directory listing). */
    suspend fun fingerprintOf(uriString: String, size: Long): String = withContext(Dispatchers.IO) {
        fingerprint(Uri.parse(uriString), size) ?: FileFingerprint.fromLocation(uriString, size)
    }

    private fun fingerprint(uri: Uri, size: Long): String? {
        if (size <= 0) return null
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { stream ->
                    val channel = stream.channel
                    FileFingerprint.compute(size) { offset, length ->
                        val buffer = ByteBuffer.allocate(length)
                        var position = offset
                        while (buffer.hasRemaining()) {
                            val read = channel.read(buffer, position)
                            if (read <= 0) break
                            position += read
                        }
                        buffer.array().copyOf(buffer.position())
                    }
                }
            }
        }.getOrNull()
    }
}
