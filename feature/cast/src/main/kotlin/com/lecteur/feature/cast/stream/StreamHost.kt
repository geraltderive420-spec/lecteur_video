package com.lecteur.feature.cast.stream

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.lecteur.core.common.cast.http.AutoStopPolicy
import com.lecteur.core.common.cast.http.MediaHttpServer
import com.lecteur.core.common.cast.http.StreamRegistry
import com.lecteur.core.common.cast.http.StreamSource
import com.lecteur.core.data.playback.UriFileInfoReader
import com.lecteur.core.database.dao.MediaFileDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** A file made available to a TV or Chromecast: the URL to hand over, and the token to revoke when done. */
data class SharedStream(val url: String, val token: String)

sealed class ShareFailure(message: String) : Exception(message) {
    data object NoLocalNetwork : ShareFailure("Pas de réseau Wi-Fi local.")
    data object FileUnknown : ShareFailure("Fichier introuvable dans la bibliothèque.")
}

/**
 * The phone's streaming endpoint. Owns the registry (what is shared, behind which token) and the HTTP server, and
 * keeps a foreground service running while the server is up so the system does not freeze the process mid-movie.
 * The server shuts down on its own when nothing has been requested for a while ([AutoStopPolicy]).
 */
@Singleton
class StreamHost @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaFiles: MediaFileDao,
    private val fileInfo: UriFileInfoReader
) {
    private val registry = StreamRegistry()
    private val lock = Any()
    private var server: MediaHttpServer? = null

    /** Shares a library file and returns the URL a device on the same network can stream it from. */
    suspend fun share(mediaFileId: Long): SharedStream = withContext(Dispatchers.IO) {
        val file = mediaFiles.getMediaFileById(mediaFileId) ?: throw ShareFailure.FileUnknown
        val host = LocalAddress.find(context) ?: throw ShareFailure.NoLocalNetwork
        val port = ensureRunning()
        val token = registry.register(
            StreamSource(
                id = file.uri,
                mimeType = MimeTypes.forContainer(file.container, file.fileName),
                sizeBytes = file.size,
                fileName = file.fileName
            )
        )
        SharedStream("http://$host:$port${MediaHttpServer.pathFor(token, file.fileName)}", token)
    }

    /** Shares a side file (an external subtitle) that is not a library entry. */
    suspend fun shareSideFile(uri: String, mimeType: String): SharedStream = withContext(Dispatchers.IO) {
        val host = LocalAddress.find(context) ?: throw ShareFailure.NoLocalNetwork
        val port = ensureRunning()
        val info = fileInfo.read(uri)
        val token = registry.register(StreamSource(uri, mimeType, info.sizeBytes, info.displayName))
        SharedStream("http://$host:$port${MediaHttpServer.pathFor(token, info.displayName)}", token)
    }

    fun revoke(token: String) = registry.revoke(token)

    /** Stops serving now (the user disconnected from the TV). */
    fun shutdown() {
        synchronized(lock) {
            server?.stop()
            server = null
        }
        registry.clear()
        context.stopService(Intent(context, StreamingService::class.java))
    }

    private fun ensureRunning(): Int = synchronized(lock) {
        server?.takeIf { it.isRunning }?.let { return it.port }
        val created = MediaHttpServer(
            registry = registry,
            opener = ContentResolverStreamOpener(context),
            autoStop = AutoStopPolicy(),
            onIdle = { shutdown() }
        )
        val port = created.start()
        server = created
        ContextCompat.startForegroundService(context, Intent(context, StreamingService::class.java))
        port
    }
}

/** Content types the receivers expect. Unknown containers fall back to a generic video type. */
object MimeTypes {
    fun forContainer(container: String?, fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (container?.uppercase() ?: ext.uppercase()) {
            "MP4", "M4V" -> "video/mp4"
            "MOV" -> "video/quicktime"
            "WEBM" -> "video/webm"
            "MKV" -> "video/x-matroska"
            "AVI" -> "video/x-msvideo"
            "TS", "M2TS" -> "video/mp2t"
            else -> "video/*"
        }
    }
}
