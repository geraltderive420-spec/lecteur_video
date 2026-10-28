package com.lecteur.core.data.shared

import android.content.Context
import com.lecteur.core.common.library.Catalog
import com.lecteur.core.common.library.FileDto
import com.lecteur.core.common.library.LibraryClient
import com.lecteur.core.common.library.LibraryException
import com.lecteur.core.common.library.LibraryUrls
import com.lecteur.core.common.library.PairedServer
import com.lecteur.core.common.library.PairedServers
import com.lecteur.core.common.library.ServerInfo
import com.lecteur.core.common.library.TranscodeLevels
import com.lecteur.core.common.library.WatchDto
import com.lecteur.core.common.library.WatchReport
import com.lecteur.core.common.library.best
import com.lecteur.core.data.search.SearchResults
import com.lecteur.core.model.LibraryItem
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.PlayPlan
import com.lecteur.core.model.QueueEntry
import com.lecteur.core.model.WatchState
import com.lecteur.core.player.engine.ExternalSubtitleSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** What the player needs to know about a file of a shared library before it starts. */
data class SharedFileDescription(
    val serverName: String,
    val title: String?,
    val file: FileDto?,
    val watch: WatchDto?,
    val subtitles: List<ExternalSubtitleSource>
)

/** The qualities a PC offers for a file being played: smaller [heights] (tallest first), and the one playing (null: original). */
data class StreamQualities(val heights: List<Int>, val current: Int?)

/** A catalog being loaded, loaded, or failed (with a message the screen shows as is). */
sealed interface CatalogState {
    data object Loading : CatalogState
    data class Loaded(val catalog: Catalog, val refreshing: Boolean = false) : CatalogState
    data class Failed(val message: String, val kind: LibraryException.Kind, val previous: Catalog? = null) : CatalogState
}

/** What can be shown: the catalog read, or the last one while the PC does not answer. */
val CatalogState.catalogOrNull: Catalog? get() = (this as? CatalogState.Loaded)?.catalog ?: (this as? CatalogState.Failed)?.previous

/**
 * The PCs whose libraries this device reads (Plex style): pairing, catalogs, stream URLs, and the watch state, which
 * lives on the PC so that the phone, the TV and the PC resume at the same place.
 *
 * Credentials are kept in the app's private storage. A stream URL is signed for one file, so the player can fetch it
 * without headers; it stops working as soon as the device is removed on the PC.
 */
@Singleton
class SharedLibraryRepository @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = ConcurrentHashMap<String, LibraryClient>()
    private val pendingReports = ConcurrentHashMap<Long, Job>()
    private val lastReportAt = ConcurrentHashMap<Long, Long>()

    private val _servers = MutableStateFlow(PairedServers.decode(prefs.getString(KEY_SERVERS, null)))
    val servers: StateFlow<List<PairedServer>> = _servers.asStateFlow()

    private val _catalogs = MutableStateFlow<Map<String, CatalogState>>(emptyMap())
    val catalogs: StateFlow<Map<String, CatalogState>> = _catalogs.asStateFlow()

    fun server(serverId: String): PairedServer? = _servers.value.firstOrNull { it.serverId == serverId }

    private val _streamHeight = MutableStateFlow(prefs.getInt(KEY_STREAM_HEIGHT, 0))

    /** The height files of a PC are brought down to while they play here; 0 plays the original file. */
    val streamHeight: StateFlow<Int> = _streamHeight.asStateFlow()

    fun setStreamHeight(height: Int) {
        _streamHeight.value = height
        prefs.edit().putInt(KEY_STREAM_HEIGHT, height).apply()
    }

    /**
     * The height the PC transcodes [file] to for playback, or null for the original: the setting, when that PC can
     * transcode and the file is taller.
     */
    fun playHeight(serverId: String, file: FileDto): Int? {
        val wanted = _streamHeight.value.takeIf { it > 0 } ?: return null
        val height = catalog(serverId)?.transcodeHeights.orEmpty().filter { it <= wanted }.maxOrNull() ?: return null
        return height.takeIf { (file.height ?: Int.MAX_VALUE) > it }
    }

    /** What a copy of [fileIds] can be: the originals, and each smaller height that saves room (estimated sizes). */
    fun copyOptions(serverId: String, fileIds: List<Long>): List<CopyOption> {
        val files = fileIds.mapNotNull { file(serverId, it) }
        val original = CopyOption(null, files.sumOf { it.sizeBytes })
        val tallest = files.maxOfOrNull { it.height ?: 0 } ?: 0
        val smaller = catalog(serverId)?.transcodeHeights.orEmpty().filter { it < tallest }.sortedDescending().mapNotNull { h ->
            val bytes = files.sumOf { f ->
                val duration = f.durationMs
                if ((f.height ?: 0) > h && duration != null) TranscodeLevels.estimatedBytes(h, duration) else f.sizeBytes
            }
            // A "smaller" version barely smaller than the original (a lean HEVC file) is not worth the wait
            CopyOption(h, bytes).takeIf { bytes < original.bytes * 0.8 }
        }
        return listOf(original) + smaller
    }

    /** The public info of a PC at [baseUrl], to check an address typed by hand before asking for the code. */
    suspend fun probe(baseUrl: String): ServerInfo = withContext(Dispatchers.IO) { LibraryClient(baseUrl).info() }

    /** Pairs with the code shown on the PC; the PC remembers this device until it is removed there. */
    suspend fun pair(baseUrl: String, code: String, deviceName: String, platform: String): PairedServer = withContext(Dispatchers.IO) {
        val response = LibraryClient(baseUrl).pair(code, deviceName, platform)
        val server = PairedServer(response.serverId, response.serverName, baseUrl.trimEnd('/'), response.deviceId, response.secret, System.currentTimeMillis())
        save(_servers.value.filterNot { it.serverId == server.serverId } + server)
        server
    }

    fun forget(serverId: String) {
        save(_servers.value.filterNot { it.serverId == serverId })
        _catalogs.update { it - serverId }
    }

    /** The PC was seen on the network at another address (DHCP): follow it. */
    fun updateAddress(serverId: String, baseUrl: String, name: String? = null) {
        val current = server(serverId) ?: return
        val url = baseUrl.trimEnd('/')
        if (current.baseUrl == url && (name == null || name == current.name)) return
        save(_servers.value.map { if (it.serverId == serverId) it.copy(baseUrl = url, name = name ?: it.name) else it })
    }

    /** Loads (or reloads) a catalog; the previous one stays on screen while refreshing. */
    fun refresh(serverId: String) {
        val server = server(serverId) ?: return
        val previous = (_catalogs.value[serverId] as? CatalogState.Loaded)?.catalog ?: (_catalogs.value[serverId] as? CatalogState.Failed)?.previous
        _catalogs.update { it + (serverId to (previous?.let { c -> CatalogState.Loaded(c, refreshing = true) } ?: CatalogState.Loading)) }
        scope.launch {
            val state = try {
                CatalogState.Loaded(client(server).catalog())
            } catch (e: LibraryException) {
                CatalogState.Failed(e.message ?: "Erreur", e.kind, previous)
            }
            _catalogs.update { it + (serverId to state) }
        }
    }

    /** Every paired PC (the TV mixes their libraries with its own). */
    fun refreshAll() = _servers.value.forEach { refresh(it.serverId) }

    /** A file of a PC's catalog, by its id on that PC. */
    fun file(serverId: String, fileId: Long): FileDto? = catalog(serverId)?.let { c ->
        c.movies.firstNotNullOfOrNull { m -> m.files.firstOrNull { it.id == fileId } }
            ?: c.series.firstNotNullOfOrNull { s -> s.episodes.firstNotNullOfOrNull { e -> e.files.firstOrNull { it.id == fileId } } }
    }

    /** Marks files watched (at their end) or not watched (from the start) on the PC. */
    fun setWatched(serverId: String, files: List<FileDto>, watched: Boolean) {
        val server = server(serverId) ?: return
        scope.launch {
            files.forEach { f ->
                val duration = f.durationMs ?: f.watch?.durationMs ?: 0
                val report = WatchReport(f.id, positionMs = if (watched) duration else 0, durationMs = duration, completed = watched)
                runCatching { client(server).report(report) }
                updateCachedWatch(serverId, f.id, report)
            }
        }
    }

    fun catalog(serverId: String): Catalog? = when (val s = _catalogs.value[serverId]) {
        is CatalogState.Loaded -> s.catalog
        is CatalogState.Failed -> s.previous
        else -> null
    }

    fun streamUrl(serverId: String, file: FileDto): String? = server(serverId)?.let { server ->
        val client = client(server)
        playHeight(serverId, file)?.let { client.hlsUrl(file, it) } ?: client.streamUrl(file)
    }

    fun artUrl(serverId: String, key: String?): String? = server(serverId)?.let { client(it).artUrl(key) }

    /** The PC's catalog in the app's own models, its artwork keys turned into URLs. */
    fun mapping(serverId: String): SharedMapping = SharedMapping { key -> artUrl(serverId, key) }

    /** The search screen's results in a PC's catalog (empty until it is read). */
    fun search(serverId: String, text: String): Flow<SearchResults> =
        observe(serverId).map { state -> state.catalogOrNull?.let { SharedQueries.search(it, { key -> artUrl(serverId, key) }, text) } ?: SearchResults(text) }

    /** A PC's catalog as the screens follow it: loaded on first use, kept on screen while it reloads or if the PC stops answering. */
    fun observe(serverId: String): Flow<CatalogState> {
        if (_catalogs.value[serverId] == null) refresh(serverId)
        return _catalogs.map { it[serverId] ?: CatalogState.Loading }.distinctUntilChanged()
    }

    /** The catalog already read, else read now (a background copy started after the app was closed). */
    suspend fun loadCatalog(serverId: String): Catalog? {
        catalog(serverId)?.let { return it }
        val server = server(serverId) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { client(server).catalog() }.getOrNull()?.also { c -> _catalogs.update { it + (serverId to CatalogState.Loaded(c)) } }
        }
    }

    // region play plans: the same "Lire" as the phone's own library, streamed from the PC

    /** [fileId] picks a version; null plays the one being watched, else the best available. */
    fun planForMovie(serverId: String, movieId: Long, fileId: Long? = null): PlayPlan? {
        val movie = catalog(serverId)?.movies?.firstOrNull { it.id == movieId } ?: return null
        val available = movie.files.filter { it.available }
        val file = available.firstOrNull { it.id == fileId }
            ?: available.filter { with(SharedShelves) { it.isStarted() } }.maxByOrNull { it.watch!!.lastWatchedAt }
            ?: available.best()
            ?: return null
        val url = streamUrl(serverId, file) ?: return null
        return PlayPlan(listOf(QueueEntry(url, movie.year?.let { "${movie.title} ($it)" } ?: movie.title)))
    }

    /** The episode and the following ones, so the player moves on by itself; [fileId] picks the episode's version. */
    fun planForEpisode(serverId: String, seriesId: Long, episodeId: Long, fileId: Long? = null): PlayPlan? {
        val series = catalog(serverId)?.series?.firstOrNull { it.id == seriesId } ?: return null
        val episode = series.episodes.firstOrNull { it.id == episodeId } ?: return null
        val entries = SharedShelves.queueFrom(series, episode).mapIndexedNotNull { index, (e, best) ->
            val file = if (index == 0) e.files.firstOrNull { it.id == fileId && it.available } ?: best else best
            streamUrl(serverId, file)?.let { QueueEntry(it, "${series.title} · ${SharedShelves.episodeLabel(e)}") }
        }
        return if (entries.isEmpty()) null else PlayPlan(entries, label = series.title)
    }

    /** What the series' "Lire" stands for: the episode in progress, the next one, else the first. */
    fun planForSeries(serverId: String, seriesId: Long): PlayPlan? {
        val series = catalog(serverId)?.series?.firstOrNull { it.id == seriesId } ?: return null
        return SharedShelves.seriesTarget(series)?.let { planForEpisode(serverId, seriesId, it.id) }
    }

    /** A poster's "Lire": the film, or where the series stands. */
    fun planForItem(serverId: String, item: LibraryItem): PlayPlan? =
        if (item.kind == MediaKind.MOVIE) planForMovie(serverId, item.id) else planForSeries(serverId, item.id)

    /** A poster's "Marquer comme vu": every file of the film, or of every episode of the series. */
    fun setWatched(serverId: String, item: LibraryItem, watched: Boolean) {
        val catalog = catalog(serverId) ?: return
        val files = if (item.kind == MediaKind.MOVIE) catalog.movies.firstOrNull { it.id == item.id }?.files.orEmpty()
        else catalog.series.firstOrNull { it.id == item.id }?.episodes?.flatMap { it.files }.orEmpty()
        setWatched(serverId, files, watched)
    }

    /** One file on its own, or its episode and the following ones. */
    fun planForFile(serverId: String, fileId: Long): PlayPlan? {
        val catalog = catalog(serverId) ?: return null
        catalog.movies.firstOrNull { m -> m.files.any { it.id == fileId } }?.let { return planForMovie(serverId, it.id, fileId) }
        catalog.series.forEach { s ->
            s.episodes.firstOrNull { e -> e.files.any { it.id == fileId } }?.let { return planForEpisode(serverId, s.id, it.id, fileId) }
        }
        return null
    }

    // endregion

    /**
     * Where a file of the PC goes on the phone, under the library folder of its kind: "Title (year)/file.mkv" for a film,
     * "Series/Saison 01/file.mkv" for an episode, the PC's subtitle files next to it under the video's name.
     */
    suspend fun copySource(serverId: String, fileId: Long, height: Int? = null): CopySource? {
        val catalog = loadCatalog(serverId) ?: return null
        val server = server(serverId) ?: return null
        val client = client(server)
        fun source(file: FileDto, kind: MediaKind, title: String, directories: List<String>): CopySource {
            // A smaller version only for a file taller than that, and from a PC that makes them
            val smaller = height?.takeIf { it in catalog.transcodeHeights && (file.height ?: Int.MAX_VALUE) > it }
            val base = file.fileName.substringBeforeLast('.') + (smaller?.let { " [${it}p]" } ?: "")
            val name = CopyPaths.safeName(if (smaller != null) "$base.mkv" else file.fileName)
            val subtitles = file.subtitles.map { s ->
                val url = client.subtitleUrl(file, s)
                val tag = listOfNotNull(s.language, if (s.forced) "forced" else null).joinToString(".").ifEmpty { "${s.index}" }
                CopySource.Subtitle(url, CopyPaths.safeName("$base.$tag.${url.substringAfterLast('.')}"))
            }
            val url = if (smaller != null) client.downloadUrl(file, smaller, name) else client.streamUrl(file)
            return CopySource(url, kind, title, directories.map(CopyPaths::safeName), name, if (smaller != null) -1 else file.sizeBytes, subtitles, smaller)
        }
        catalog.movies.forEach { m ->
            m.files.firstOrNull { it.id == fileId }?.let {
                val title = m.year?.let { y -> "${m.title} ($y)" } ?: m.title
                return source(it, MediaKind.MOVIE, title, listOf(title))
            }
        }
        catalog.series.forEach { s ->
            s.episodes.forEach { e ->
                e.files.firstOrNull { it.id == fileId }?.let {
                    val season = if (e.season == 0) "Spéciaux" else "Saison %02d".format(e.season)
                    return source(it, MediaKind.SERIES, "${s.title} · ${SharedShelves.episodeLabel(e)}", listOf(s.title, season))
                }
            }
        }
        return null
    }

    // region playback (called by the player through PlaybackRequestFactory and the watch store)

    /** The player's "Qualité" choices for [uri]; null when it is not a PC file, or the PC cannot make it smaller. */
    fun qualities(uri: String): StreamQualities? {
        val ref = LibraryUrls.parseStream(uri) ?: return null
        val server = serverFor(ref) ?: return null
        val file = file(server.serverId, ref.fileId) ?: return null
        val heights = catalog(server.serverId)?.transcodeHeights.orEmpty().filter { (file.height ?: Int.MAX_VALUE) > it }.sortedDescending()
        return if (heights.isEmpty()) null else StreamQualities(heights, ref.height)
    }

    /**
     * The PC file behind [uri] at another quality: [height] null for the original, or the original anyway when the file
     * is not taller than that. Null when [uri] is not a PC file.
     */
    fun streamUrlAt(uri: String, height: Int?): String? {
        val ref = LibraryUrls.parseStream(uri) ?: return null
        val server = serverFor(ref) ?: return null
        val file = file(server.serverId, ref.fileId) ?: return null
        val client = client(server)
        val smaller = height?.takeIf { it in catalog(server.serverId)?.transcodeHeights.orEmpty() && (file.height ?: Int.MAX_VALUE) > it }
        return smaller?.let { client.hlsUrl(file, it) } ?: client.streamUrl(file)
    }

    fun isShared(uri: String): Boolean = LibraryUrls.parseStream(uri)?.let { ref -> serverFor(ref) != null } == true

    /** Title, subtitles and the PC's watch state for a stream URL; null when it is not one of ours. */
    suspend fun describe(uri: String): SharedFileDescription? = withContext(Dispatchers.IO) {
        val ref = LibraryUrls.parseStream(uri) ?: return@withContext null
        val server = serverFor(ref) ?: return@withContext null
        val client = client(server)
        val located = locate(server.serverId, ref.fileId)
        // The PC's watch state is the reference; a slow network must not hold the player for long
        val watch = withTimeoutOrNull(WATCH_TIMEOUT_MS) { runCatching { client.watch(ref.fileId) }.getOrNull() } ?: located?.second?.watch
        val file = located?.second
        SharedFileDescription(
            serverName = server.name,
            title = located?.first,
            file = file,
            watch = watch,
            subtitles = file?.subtitles.orEmpty().map { ExternalSubtitleSource(client.subtitleUrl(file!!, it), it.mimeType, it.language, it.label, it.forced) }
        )
    }

    /**
     * Sends where playback stands to the PC. The player saves every few seconds: reports go out at most every
     * [REPORT_INTERVAL_MS] per file, the last one always (a trailing send), so stopping the player never loses the position.
     */
    fun report(uri: String, state: WatchState) {
        val ref = LibraryUrls.parseStream(uri) ?: return
        val server = serverFor(ref) ?: return
        val report = WatchReport(
            fileId = ref.fileId, positionMs = state.positionMs, durationMs = state.durationMs, completed = state.isCompleted,
            audioIndex = state.selectedAudioTrackIndex, subtitleIndex = state.selectedSubtitleTrackIndex,
            audioDelayMs = state.audioDelayMs, subtitleDelayMs = state.subtitleDelayMs
        )
        val key = ref.fileId
        pendingReports.remove(key)?.cancel()
        val wait = (REPORT_INTERVAL_MS - (System.currentTimeMillis() - (lastReportAt[key] ?: 0L))).coerceAtLeast(0)
        pendingReports[key] = scope.launch {
            if (wait > 0 && !state.isCompleted) delay(wait)
            lastReportAt[key] = System.currentTimeMillis()
            runCatching { client(server).report(report) }
            updateCachedWatch(server.serverId, key, report)
        }
    }

    // endregion

    private fun serverFor(ref: LibraryUrls.StreamRef): PairedServer? = _servers.value.firstOrNull { it.deviceId == ref.deviceId }

    private fun client(server: PairedServer): LibraryClient {
        val existing = clients[server.serverId]
        if (existing != null && existing.baseUrl == server.baseUrl.trimEnd('/')) return existing
        return LibraryClient(server.baseUrl, server.credentials).also { clients[server.serverId] = it }
    }

    /** "Title (year)" or "Series · S01E02 · Title", and the file, from the catalog already read. */
    private fun locate(serverId: String, fileId: Long): Pair<String, FileDto>? {
        val catalog = catalog(serverId) ?: return null
        catalog.movies.forEach { m ->
            m.files.firstOrNull { it.id == fileId }?.let { return (m.year?.let { y -> "${m.title} ($y)" } ?: m.title) to it }
        }
        catalog.series.forEach { s ->
            s.episodes.forEach { e ->
                e.files.firstOrNull { it.id == fileId }?.let {
                    return ("%s · S%02dE%02d".format(s.title, e.season, e.number) + e.title?.let { t -> " · $t" }.orEmpty()) to it
                }
            }
        }
        return null
    }

    /** The screens show progress from the catalog: keep it in step without reloading it whole. */
    private fun updateCachedWatch(serverId: String, fileId: Long, report: WatchReport) {
        val watch = WatchDto(report.positionMs, report.durationMs, report.completed, System.currentTimeMillis(), report.audioIndex, report.subtitleIndex, report.audioDelayMs, report.subtitleDelayMs)
        fun List<FileDto>.patched() = map { if (it.id == fileId) it.copy(watch = watch) else it }
        _catalogs.update { map ->
            val state = map[serverId] as? CatalogState.Loaded ?: return@update map
            val c = state.catalog
            val patched = c.copy(
                movies = c.movies.map { m -> if (m.files.any { it.id == fileId }) m.copy(files = m.files.patched()) else m },
                series = c.series.map { s ->
                    if (s.episodes.none { e -> e.files.any { it.id == fileId } }) s
                    else s.copy(episodes = s.episodes.map { e -> e.copy(files = e.files.patched()) })
                }
            )
            map + (serverId to state.copy(catalog = patched))
        }
    }

    private fun save(list: List<PairedServer>) {
        _servers.value = list
        prefs.edit().putString(KEY_SERVERS, PairedServers.encode(list)).apply()
    }

    private companion object {
        const val PREFS = "shared_libraries"
        const val KEY_SERVERS = "servers"
        const val KEY_STREAM_HEIGHT = "stream_height"
        const val WATCH_TIMEOUT_MS = 2_500L
        const val REPORT_INTERVAL_MS = 10_000L
    }
}
