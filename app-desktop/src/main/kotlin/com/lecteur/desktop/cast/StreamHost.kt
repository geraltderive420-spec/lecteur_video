package com.lecteur.desktop.cast

import com.lecteur.core.common.cast.http.AutoStopPolicy
import com.lecteur.core.common.cast.http.MediaHttpServer
import com.lecteur.core.common.cast.http.StreamOpener
import com.lecteur.core.common.cast.http.StreamRegistry
import com.lecteur.core.common.cast.http.StreamSource
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

data class SharedStream(val url: String, val token: String)

class ShareFailure(message: String) : Exception(message)

/** Serves library files to a TV over HTTP (byte ranges, 128-bit token in the URL): core:common's server, files from disk. */
class StreamHost {
    private val registry = StreamRegistry()
    private var server: MediaHttpServer? = null

    private val opener = StreamOpener { source, offset ->
        val file = File(source.id)
        if (!file.isFile) throw IOException("missing ${source.id}")
        FileInputStream(file).also { input -> skipFully(input, offset) }
    }

    @Synchronized
    fun share(file: File, mimeType: String, peerHost: String?): SharedStream {
        if (!file.isFile) throw ShareFailure("Fichier introuvable : ${file.name}")
        val host = LocalAddress.towards(peerHost) ?: throw ShareFailure("Pas de réseau local.")
        val port = ensureRunning()
        val token = registry.register(StreamSource(file.absolutePath, mimeType, file.length(), file.name))
        return SharedStream("http://$host:$port${MediaHttpServer.pathFor(token, file.name)}", token)
    }

    fun revoke(token: String) = registry.revoke(token)

    @Synchronized
    fun shutdown() {
        server?.stop()
        server = null
        registry.clear()
    }

    private fun ensureRunning(): Int {
        server?.takeIf { it.isRunning }?.let { return it.port }
        val created = MediaHttpServer(registry, opener, AutoStopPolicy(), onIdle = { synchronized(this) { server = null } })
        val port = created.start()
        server = created
        return port
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val n = input.skip(left)
            if (n <= 0) throw IOException("cannot seek")
            left -= n
        }
    }

    companion object {
        fun mimeFor(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "ts", "m2ts" -> "video/mp2t"
            "srt" -> "application/x-subrip"
            "vtt" -> "text/vtt"
            "ass", "ssa" -> "text/x-ssa"
            else -> "video/*"
        }
    }
}
