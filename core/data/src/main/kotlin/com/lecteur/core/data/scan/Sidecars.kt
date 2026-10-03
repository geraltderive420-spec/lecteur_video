package com.lecteur.core.data.scan

import android.content.Context
import android.net.Uri
import com.lecteur.core.common.nfo.NfoData
import com.lecteur.core.common.scan.VideoFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Files sitting next to a video that describe it: `.nfo`, poster, fan art. */
data class SidecarRefs(val nfo: WalkedEntry?, val poster: WalkedEntry?, val backdrop: WalkedEntry?) {
    companion object {
        val NONE = SidecarRefs(null, null, null)
    }
}

/** What the sidecars said, ready for the linker. Image URIs are content URIs, used as is by the image loader. */
data class LocalMetadata(val nfo: NfoData?, val posterUri: String?, val backdropUri: String?) {
    companion object {
        val NONE = LocalMetadata(null, null, null)
    }
}

object SidecarResolver {

    private val SEASON_FOLDER = Regex("(?i)^(?:season|saison|série|serie|s)[ ._-]*\\d{1,3}$|^specials?$|^extras?$")

    fun isSeasonFolder(name: String): Boolean = SEASON_FOLDER.matches(name.trim())

    /**
     * Movie sidecars live in the video's own directory. `<name>.nfo` and `<name>-poster.jpg` belong to that video;
     * the generic `movie.nfo` / `poster.jpg` only to a directory holding a single video (otherwise they are ambiguous).
     */
    fun forMovie(directory: List<WalkedEntry>, videoName: String): SidecarRefs {
        val base = videoName.substringBeforeLast('.')
        val singleVideo = directory.count { VideoFiles.isVideo(it.name) } <= 1

        val nfo = directory.firstOrNull { VideoFiles.isNfo(it.name) && it.name.substringBeforeLast('.').equals(base, ignoreCase = true) }
            ?: directory.firstOrNull { VideoFiles.isNfo(it.name) && it.name.equals("movie.nfo", ignoreCase = true) && singleVideo }
        val poster = directory.firstOrNull { it.name.isSpecificArtwork(base) && VideoFiles.isPosterFor(it.name, base) }
            ?: directory.firstOrNull { singleVideo && VideoFiles.isPosterFor(it.name, null) }
        val backdrop = directory.firstOrNull { it.name.isSpecificArtwork(base) && VideoFiles.isBackdropFor(it.name, base) }
            ?: directory.firstOrNull { singleVideo && VideoFiles.isBackdropFor(it.name, null) }
        return SidecarRefs(nfo, poster, backdrop)
    }

    /** Series sidecars live in the series folder, i.e. the video's directory unless that one is a season folder. */
    fun forSeries(directories: Map<String, List<WalkedEntry>>, videoDirectory: String): SidecarRefs {
        val parent = videoDirectory.substringBeforeLast('/', missingDelimiterValue = "")
        val seriesDirectory = if (videoDirectory.isNotEmpty() && isSeasonFolder(videoDirectory.substringAfterLast('/'))) parent else videoDirectory
        val entries = directories[seriesDirectory].orEmpty()

        val nfo = entries.firstOrNull { it.name.equals("tvshow.nfo", ignoreCase = true) }
        val poster = entries.firstOrNull { VideoFiles.isPosterFor(it.name, null) }
        val backdrop = entries.firstOrNull { VideoFiles.isBackdropFor(it.name, null) }
        return SidecarRefs(nfo, poster, backdrop)
    }

    // "poster.jpg"-style names are generic; "<video>-poster.jpg" is tied to one video
    private fun String.isSpecificArtwork(videoBase: String): Boolean = startsWith(videoBase, ignoreCase = true)
}

interface SidecarReader {
    suspend fun readText(uri: String, maxBytes: Int = MAX_NFO_BYTES): String?

    companion object {
        const val MAX_NFO_BYTES = 256 * 1024
    }
}

class ContentResolverSidecarReader @Inject constructor(
    @ApplicationContext private val context: Context
) : SidecarReader {

    override suspend fun readText(uri: String, maxBytes: Int): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                val buffer = ByteArray(maxBytes)
                var total = 0
                while (total < maxBytes) {
                    val read = input.read(buffer, total, maxBytes - total)
                    if (read < 0) break
                    total += read
                }
                String(buffer, 0, total, Charsets.UTF_8)
            }
        }.getOrNull()
    }
}
