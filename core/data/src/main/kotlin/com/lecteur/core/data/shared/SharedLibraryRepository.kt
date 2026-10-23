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
import com.lecteur.core.common.library.WatchDto
import com.lecteur.core.common.library.WatchReport
import com.lecteur.core.model.WatchState
import com.lecteur.core.player.engine.ExternalSubtitleSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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

/** A catalog being loaded, loaded, or failed (with a message the screen shows as is). */
sealed interface CatalogState {
    data object Loading : CatalogState
    data class Loaded(val catalog: Catalog, val refreshing: Boolean = false) : CatalogState
    data class Failed(val message: String, val kind: LibraryException.Kind, val previous: Catalog? = null) : CatalogState
}

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

    fun catalog(serverId: String): Catalog? = when (val s = _catalogs.value[serverId]) {
        is CatalogState.Loaded -> s.catalog
        is CatalogState.Failed -> s.previous
        else -> null
    }

    fun streamUrl(serverId: String, file: FileDto): String? = server(serverId)?.let { client(it).streamUrl(file) }

    fun artUrl(serverId: String, key: String?): String? = server(serverId)?.let { client(it).artUrl(key) }

    // region playback (called by the player through PlaybackRequestFactory and the watch store)

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
        const val WATCH_TIMEOUT_MS = 2_500L
        const val REPORT_INTERVAL_MS = 10_000L
    }
}
