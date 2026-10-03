package com.lecteur.core.data.playback

import java.net.URLDecoder

/** Turns picker/document URIs into file system paths when the storage layout makes that possible. */
object StoragePaths {

    /**
     * `content://com.android.externalstorage.documents/.../document/primary%3AMovies%2FFilm.mkv` ->
     * `/storage/emulated/0/Movies/Film.mkv`; SD cards and USB drives map to `/storage/<volume-id>/...`.
     * Returns null for providers that do not expose a path (downloads, cloud, MediaStore...).
     */
    fun fromUri(uri: String): String? {
        if (uri.startsWith("file://")) return URLDecoder.decode(uri.removePrefix("file://"), "UTF-8")
        if (!uri.startsWith("content://com.android.externalstorage.documents/")) return null

        // A document URI ends with /document/<id>; a bare tree URI (a folder picked with OpenDocumentTree) with /tree/<id>
        val documentId = (uri.substringAfterLast("/document/", missingDelimiterValue = "")
            .ifEmpty { uri.substringAfter("/tree/", missingDelimiterValue = "").substringBefore('/') })
            .takeIf { it.isNotEmpty() }
            ?.let { URLDecoder.decode(it, "UTF-8") }
            ?: return null

        val volume = documentId.substringBefore(':', missingDelimiterValue = "")
        val relative = documentId.substringAfter(':', missingDelimiterValue = "")
        if (volume.isEmpty() || relative.isEmpty()) return null

        val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        return "$root/$relative"
    }

    fun parentOf(path: String): String? = path.substringBeforeLast('/', missingDelimiterValue = "").takeIf { it.isNotEmpty() }

    fun nameOf(path: String): String = path.substringAfterLast('/')
}
