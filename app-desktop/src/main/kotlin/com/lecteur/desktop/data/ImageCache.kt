package com.lecteur.desktop.data

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.skia.Image
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Posters, backdrops and portraits: downloaded once into %LOCALAPPDATA%\LecteurMedia\images (the library stays browsable
 * offline), decoded off the UI thread, and kept in a memory LRU so scrolling a grid back does not decode again.
 */
class ImageCache(private val dir: File = AppDirs.images) {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloads = Semaphore(6)

    private val memory = object : LinkedHashMap<String, ImageBitmap>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > MAX_IN_MEMORY
    }

    fun cached(url: String): ImageBitmap? = synchronized(memory) { memory[url] }

    suspend fun load(url: String): ImageBitmap? {
        cached(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            val bytes = bytes(url) ?: return@withContext null
            val bitmap = runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull() ?: return@withContext null
            synchronized(memory) { memory[url] = bitmap }
            bitmap
        }
    }

    /** Downloads in the background so the screens show artwork at once later. */
    fun prefetch(urls: List<String>) {
        urls.filter { it.startsWith("http") }.forEach { url -> scope.launch { runCatching { bytes(url) } } }
    }

    private suspend fun bytes(url: String): ByteArray? {
        if (url.startsWith("file:")) return runCatching { File(URI(url)).readBytes() }.getOrNull()
        if (!url.startsWith("http")) return runCatching { File(url).takeIf { it.isFile }?.readBytes() }.getOrNull()
        val file = File(dir, key(url))
        if (file.isFile && file.length() > 0) return runCatching { file.readBytes() }.getOrNull()
        return downloads.withPermit {
            runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.bytes() ?: return@use null
                    val tmp = File(dir, file.name + ".part")
                    tmp.writeBytes(body)
                    tmp.renameTo(file)
                    body
                }
            }.getOrNull()
        }
    }

    fun diskUsageBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun clear() {
        synchronized(memory) { memory.clear() }
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun key(url: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) } + "." + url.substringAfterLast('.', "img").take(4).filter(Char::isLetterOrDigit)
    }

    private companion object {
        // Decoded bitmaps live in native memory: about 0.7 MB per poster, 3.7 MB per backdrop
        const val MAX_IN_MEMORY = 160
    }
}
