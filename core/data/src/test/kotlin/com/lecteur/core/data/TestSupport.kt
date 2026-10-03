package com.lecteur.core.data

import com.lecteur.core.data.identify.ImagePrefetcher
import com.lecteur.core.data.scan.AudioTrackFacts
import com.lecteur.core.data.scan.FileInspector
import com.lecteur.core.data.scan.FolderWalker
import com.lecteur.core.data.scan.SidecarReader
import com.lecteur.core.data.scan.SubtitleTrackFacts
import com.lecteur.core.data.scan.TechnicalInfo
import com.lecteur.core.data.scan.WalkResult
import com.lecteur.core.data.scan.WalkedDirectory
import com.lecteur.core.data.scan.WalkedEntry
import com.lecteur.core.model.AudioCodec
import com.lecteur.core.model.HdrType
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.MetadataException
import com.lecteur.core.model.MetadataProvider
import com.lecteur.core.model.MetadataSearchHit
import com.lecteur.core.model.MovieMetadata
import com.lecteur.core.model.SeriesMetadata
import com.lecteur.core.model.VideoCodec
import java.io.IOException

/** A folder on a fake disk: directory path ("" = root) to its files. */
class FakeWalker(var reachable: Boolean = true, var complete: Boolean = true) : FolderWalker {
    val directories = LinkedHashMap<String, MutableList<WalkedEntry>>()

    fun put(directory: String, name: String, size: Long = 1_000, modified: Long = 1, uri: String = "content://disk/$directory/$name") {
        val list = directories.getOrPut(directory) { mutableListOf() }
        list.removeAll { it.uri == uri }
        list += WalkedEntry(uri, name, size, modified)
    }

    fun remove(uri: String) {
        directories.values.forEach { list -> list.removeAll { it.uri == uri } }
    }

    override suspend fun walk(treeUri: String, rootName: String): WalkResult =
        if (!reachable) WalkResult.unreachable(rootName)
        else WalkResult(true, complete, directories.map { (path, entries) -> WalkedDirectory(path, entries.toList()) }, rootName)
}

class FakeInspector : FileInspector {
    /** Content identity per uri: files sharing an id share a fingerprint, which is how a "move" is simulated. */
    val contentIds = HashMap<String, String>()
    val unreadable = HashSet<String>()
    var inspections = 0
    var fingerprints = 0
    var info = defaultInfo()

    override suspend fun fingerprint(uri: String, size: Long): String {
        fingerprints++
        return "fp-" + (contentIds[uri] ?: uri) + "-$size"
    }

    override suspend fun inspect(uri: String, nameHdr: HdrType, extension: String?): TechnicalInfo? {
        inspections++
        return if (uri in unreadable) null else info.copy(hdrType = nameHdr)
    }

    companion object {
        fun defaultInfo() = TechnicalInfo(
            durationMs = 7_200_000, container = "MKV", videoCodec = VideoCodec.HEVC, width = 3840, height = 2160, hdrType = HdrType.NONE,
            audioTracks = listOf(
                AudioTrackFacts(1, "fre", AudioCodec.AC3, 6, "VF", isDefault = true, isForced = false),
                AudioTrackFacts(2, "eng", AudioCodec.TRUEHD, 8, "VO", isDefault = false, isForced = false)
            ),
            subtitleTracks = listOf(SubtitleTrackFacts(3, "fre", "SRT", null, isDefault = false, isForced = true))
        )
    }
}

class FakeSidecars : SidecarReader {
    val files = HashMap<String, String>()
    override suspend fun readText(uri: String, maxBytes: Int): String? = files[uri]
}

class RecordingPrefetcher : ImagePrefetcher {
    val urls = mutableListOf<String>()
    override fun prefetch(urls: List<String>) { this.urls += urls }
}

class FakeProvider : MetadataProvider {
    override var isConfigured = true
    var offline = false

    var searchHandler: (MediaKind, String, Int?) -> List<MetadataSearchHit> = { _, _, _ -> emptyList() }
    val searches = mutableListOf<Triple<MediaKind, String, Int?>>()
    val movies = HashMap<Long, MovieMetadata>()
    val series = HashMap<Long, SeriesMetadata>()
    var detailCalls = 0

    override suspend fun search(kind: MediaKind, query: String, year: Int?): List<MetadataSearchHit> {
        if (offline) throw MetadataException.Offline(IOException("no network"))
        searches += Triple(kind, query, year)
        return searchHandler(kind, query, year)
    }

    override suspend fun movieDetails(tmdbId: Long): MovieMetadata {
        if (offline) throw MetadataException.Offline(IOException("no network"))
        detailCalls++
        return movies[tmdbId] ?: throw MetadataException.NotFound(tmdbId)
    }

    override suspend fun seriesDetails(tmdbId: Long, seasonNumbers: Set<Int>?): SeriesMetadata {
        if (offline) throw MetadataException.Offline(IOException("no network"))
        detailCalls++
        return series[tmdbId] ?: throw MetadataException.NotFound(tmdbId)
    }
}
