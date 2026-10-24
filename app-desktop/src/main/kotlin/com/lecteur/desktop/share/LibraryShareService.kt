package com.lecteur.desktop.share

import com.lecteur.core.common.library.Catalog
import com.lecteur.core.common.library.CreditDto
import com.lecteur.core.common.library.DeviceDirectory
import com.lecteur.core.common.library.EpisodeDto
import com.lecteur.core.common.library.FileDto
import com.lecteur.core.common.library.LibraryAdvert
import com.lecteur.core.common.library.LibraryBackend
import com.lecteur.core.common.library.LibraryServer
import com.lecteur.core.common.library.MovieDto
import com.lecteur.core.common.library.PairedDevice
import com.lecteur.core.common.library.PairingWindow
import com.lecteur.core.common.library.SeasonDto
import com.lecteur.core.common.library.SeriesDto
import com.lecteur.core.common.library.ServedFile
import com.lecteur.core.common.library.SubtitleDto
import com.lecteur.core.common.library.WatchDto
import com.lecteur.core.common.library.WatchReport
import com.lecteur.core.model.HdrType
import com.lecteur.core.model.ImageUrls
import com.lecteur.core.model.VideoCodec
import com.lecteur.core.model.WatchState
import com.lecteur.desktop.cast.LocalAddress
import com.lecteur.desktop.cast.Mdns
import com.lecteur.desktop.cast.StreamHost
import com.lecteur.desktop.data.CreditRow
import com.lecteur.desktop.data.FileEntry
import com.lecteur.desktop.data.ImageCache
import com.lecteur.desktop.data.LibrarySnapshot
import com.lecteur.desktop.data.LibraryStore
import com.lecteur.desktop.data.SettingsStore
import com.lecteur.desktop.data.SharedDevice
import com.lecteur.desktop.ui.bestAudioLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class ShareUi(
    val running: Boolean = false,
    val port: Int = 0,
    val addresses: List<String> = emptyList(),
    /** The code to type on the device being paired, while the pairing window is open. */
    val pairingCode: String? = null,
    val pairingRemainingS: Int = 0,
    /** Name of the device that just paired, shown in the pairing window before it closes. */
    val justPaired: String? = null,
    val error: String? = null
)

/**
 * The PC as a library server for the phones and TVs of the house, Plex style: they browse the library, stream any file
 * (with byte ranges, the original file: their own player decodes it) and keep one shared watch state, the PC's.
 *
 * Security: off by default; a device pairs only with the 6-digit code shown here while the user has the pairing window
 * open; every request after that is signed with the device's secret ([com.lecteur.core.common.library.LibrarySignature]);
 * a device can be revoked here at any time; only addresses of the local network get an answer.
 */
class LibraryShareService(
    private val store: LibraryStore,
    private val settings: SettingsStore,
    private val mdns: Mdns,
    private val images: ImageCache
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pairing = PairingWindow()
    private var server: LibraryServer? = null

    private val _ui = MutableStateFlow(ShareUi())
    val ui: StateFlow<ShareUi> = _ui.asStateFlow()

    /** Last time each device was seen, kept in memory and written to the settings at most every few minutes. */
    private val seen = ConcurrentHashMap<String, Pair<Long, String>>()

    @Synchronized
    fun start() {
        if (server != null) return
        val created = LibraryServer(backend, devices, pairing, onPaired = ::paired)
        try {
            val port = created.start()
            server = created
            val s = settings.value
            mdns.advertiseLibrary(LibraryAdvert(s.receiverName, s.serverId, port))
            _ui.value = ShareUi(running = true, port = port, addresses = LocalAddress.candidates().map { it.hostAddress })
        } catch (e: IOException) {
            _ui.value = ShareUi(error = "Partage impossible : ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        pairing.close()
        mdns.unadvertiseLibrary()
        server?.stop()
        server = null
        flushSeen()
        _ui.value = ShareUi()
    }

    fun restart() {
        stop()
        start()
    }

    /** Shows a fresh code for [PairingWindow.DEFAULT_TTL_MS]; the countdown keeps the window's state in the UI. */
    fun openPairing() {
        val code = pairing.open()
        _ui.update { it.copy(pairingCode = code, justPaired = null, pairingRemainingS = (pairing.remainingMs() / 1000).toInt()) }
        scope.launch {
            while (isActive && pairing.isOpen && _ui.value.pairingCode == code) {
                _ui.update { it.copy(pairingRemainingS = (pairing.remainingMs() / 1000).toInt()) }
                delay(1_000)
            }
            _ui.update { if (it.pairingCode == code) it.copy(pairingCode = null, pairingRemainingS = 0) else it }
        }
    }

    fun closePairing() {
        pairing.close()
        _ui.update { it.copy(pairingCode = null, justPaired = null, pairingRemainingS = 0) }
    }

    fun revoke(deviceId: String) {
        seen.remove(deviceId)
        settings.update { s -> s.copy(sharedDevices = s.sharedDevices.filterNot { it.id == deviceId }) }
    }

    fun rename(deviceId: String, name: String) {
        settings.update { s -> s.copy(sharedDevices = s.sharedDevices.map { if (it.id == deviceId) it.copy(name = name.take(60)) else it }) }
    }

    /** When each device was last seen (in memory first: more recent than the settings file). */
    fun lastSeen(device: SharedDevice): Long = seen[device.id]?.first ?: device.lastSeenAt

    private fun paired(device: PairedDevice) {
        _ui.update { it.copy(pairingCode = null, pairingRemainingS = 0, justPaired = device.name) }
    }

    private fun flushSeen() {
        if (seen.isEmpty()) return
        settings.update { s ->
            s.copy(sharedDevices = s.sharedDevices.map { d -> seen[d.id]?.let { (at, address) -> d.copy(lastSeenAt = at, lastAddress = address) } ?: d })
        }
    }

    // region devices

    private val devices = object : DeviceDirectory {
        override fun find(deviceId: String): PairedDevice? = settings.value.sharedDevices.firstOrNull { it.id == deviceId }
            ?.let { PairedDevice(it.id, it.name, it.secret, it.platform, it.pairedAt) }

        override fun add(device: PairedDevice) {
            val entry = SharedDevice(device.id, device.name, device.secret, device.platform, device.pairedAt, device.pairedAt)
            settings.update { s -> s.copy(sharedDevices = s.sharedDevices + entry) }
        }

        override fun seen(device: PairedDevice, address: String) {
            val now = System.currentTimeMillis()
            val previous = seen.put(device.id, now to address)
            val stored = settings.value.sharedDevices.firstOrNull { it.id == device.id }?.lastSeenAt ?: 0
            if (previous == null || now - stored > SEEN_WRITE_INTERVAL_MS) flushSeen()
        }
    }

    // endregion

    // region backend

    private val backend = object : LibraryBackend {
        override val serverId: String get() = settings.value.serverId
        override val serverName: String get() = settings.value.receiverName

        @Volatile private var cached: Pair<LibrarySnapshot, ByteArray>? = null

        override fun catalogJson(): ByteArray {
            val snapshot = store.snapshot.value
            cached?.takeIf { it.first === snapshot }?.let { return it.second }
            val bytes = LibraryServer.encodeCatalog(catalog(snapshot))
            cached = snapshot to bytes
            return bytes
        }

        override fun watch(fileId: Long): WatchDto? = store.watchState(fileId)?.let(::watchDto)

        override fun saveWatch(device: PairedDevice, report: WatchReport): Boolean {
            if (store.mediaFile(report.fileId) == null) return false
            val existing = store.watchState(report.fileId)
            val finishedNow = report.completed && existing?.isCompleted != true
            store.saveWatch(
                WatchState(
                    id = existing?.id ?: 0,
                    mediaFileId = report.fileId,
                    positionMs = report.positionMs.coerceAtLeast(0),
                    durationMs = report.durationMs.coerceAtLeast(0),
                    isCompleted = report.completed,
                    playCount = (existing?.playCount ?: 0) + if (finishedNow) 1 else 0,
                    lastWatchedAt = System.currentTimeMillis(),
                    selectedAudioTrackIndex = report.audioIndex,
                    selectedSubtitleTrackIndex = report.subtitleIndex,
                    audioDelayMs = report.audioDelayMs,
                    subtitleDelayMs = report.subtitleDelayMs,
                    displayMode = existing?.displayMode
                )
            )
            return true
        }

        override fun stream(fileId: Long): ServedFile? {
            val file = store.mediaFile(fileId)?.takeIf { it.isAvailable } ?: return null
            return served(File(file.uri), StreamHost.mimeFor(file.fileName))
        }

        override fun subtitle(fileId: Long, index: Int): ServedFile? {
            val path = store.subtitleTracks(fileId).firstOrNull { it.index == index }?.externalPath ?: return null
            return served(File(path), StreamHost.mimeFor(path))
        }

        override fun art(key: String): ServedFile? {
            // Keys come from the catalog: after a restart of the PC, a device may ask before reading it again
            val url = artKeys[key] ?: artKeys.also { catalogJson() }[key] ?: return null
            // Downloaded once from TMDB into the PC's cache: devices get artwork even when the PC is the only one online
            val bytes = runBlocking { images.encoded(url) } ?: return null
            return ServedFile("art", sniffImage(bytes), bytes.size.toLong()) { offset -> bytes.inputStream(offset.toInt(), bytes.size - offset.toInt()) }
        }
    }

    /** Opaque artwork keys of the current catalog → the URL (TMDB) or local file they stand for. Never a path from a device. */
    private val artKeys = ConcurrentHashMap<String, String>()

    private fun art(path: String?, kind: Char): String? {
        val url = when (kind) {
            'p' -> ImageUrls.poster(path)
            'b' -> ImageUrls.backdrop(path)
            'r' -> ImageUrls.profile(path)
            else -> ImageUrls.still(path)
        } ?: return null
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val key = kind + digest.take(24)
        artKeys[key] = url
        return key
    }

    private fun served(file: File, mime: String): ServedFile? {
        if (!file.isFile) return null
        return ServedFile(file.name, mime, file.length()) { offset ->
            FileInputStream(file).also { it.channel.position(offset) }
        }
    }

    private fun catalog(snapshot: LibrarySnapshot): Catalog {
        val s = settings.value
        val subtitles = store.subtitleTracksByFile()
        val audio = store.audioTracksByFile()
        val credits = store.allCredits()
        val movieCast = credits.filter { it.first != null }.groupBy({ it.first!! }, { it.third })
        val seriesCast = credits.filter { it.second != null }.groupBy({ it.second!! }, { it.third })
        fun cast(rows: List<CreditRow>?) = rows.orEmpty().take(MAX_CAST).map { CreditDto(it.personId, it.name, it.role, art(it.profile, 'r'), it.isDirector) }
        fun languages(codes: List<String?>) = codes.mapNotNull { it?.let(LibrarySnapshot::normaliseLanguage) }.distinct()
        fun file(entry: FileEntry): FileDto {
            val f = entry.file
            val tracks = audio[f.id].orEmpty()
            val subs = subtitles[f.id].orEmpty()
            return FileDto(
                id = f.id,
                fileName = f.fileName,
                sizeBytes = f.sizeBytes,
                durationMs = f.durationMs,
                width = f.width,
                height = f.height,
                hdr = f.hdrType.takeIf { it != HdrType.NONE }?.name,
                videoCodec = f.videoCodec.takeIf { it != VideoCodec.UNKNOWN }?.name,
                available = f.isAvailable,
                watch = entry.watch?.let(::watchDto),
                subtitles = subs.filter { it.externalPath != null && File(it.externalPath).isFile }.map {
                    SubtitleDto(it.index, it.language, it.title, it.isForced, StreamHost.mimeFor(it.externalPath!!))
                },
                container = f.container,
                audio = bestAudioLabel(tracks),
                audioLanguages = languages(tracks.map { it.language }),
                subtitleLanguages = languages(subs.map { it.language })
            )
        }

        val movies = snapshot.movies.values.map { e ->
            val m = e.movie
            MovieDto(
                id = m.id, title = m.title, year = m.year, overview = m.overview, poster = art(m.posterPath, 'p'),
                backdrop = art(m.backdropPath, 'b'), rating = m.rating, runtimeMinutes = m.runtimeMinutes,
                genres = e.genreIds.mapNotNull { snapshot.genres[it] }, addedAt = m.addedAt, files = e.files.map(::file),
                originalTitle = m.originalTitle?.takeIf { it != m.title }, releaseDate = m.releaseDate, certification = m.certification,
                tmdbId = m.tmdbId, imdbId = m.imdbId, trailerKey = m.trailerKey, collection = m.collectionId?.let { snapshot.collections[it] },
                cast = cast(movieCast[m.id])
            )
        }.sortedBy { it.title.lowercase() }

        val series = snapshot.series.values.map { e ->
            val sr = e.series
            SeriesDto(
                id = sr.id, title = sr.title, year = e.year, overview = sr.overview, poster = art(sr.posterPath, 'p'),
                backdrop = art(sr.backdropPath, 'b'), rating = sr.rating, genres = e.genreIds.mapNotNull { snapshot.genres[it] },
                addedAt = e.files.maxOfOrNull { it.file.addedAt } ?: sr.addedAt,
                seasons = e.seasons.map { SeasonDto(it.seasonNumber, it.name, art(it.posterPath, 'p')) },
                episodes = e.owned.map { ep ->
                    EpisodeDto(
                        id = ep.episode.id, season = ep.episode.seasonNumber, number = ep.episode.episodeNumber, title = ep.episode.title,
                        overview = ep.episode.overview, still = art(ep.episode.stillPath, 's'), files = ep.files.map(::file),
                        airDate = ep.episode.airDate, runtimeMinutes = ep.episode.runtimeMinutes
                    )
                },
                originalTitle = sr.originalTitle?.takeIf { it != sr.title }, firstAirDate = sr.firstAirDate, status = sr.status,
                certification = sr.certification, tmdbId = sr.tmdbId, imdbId = sr.imdbId, cast = cast(seriesCast[sr.id])
            )
        }.sortedBy { it.title.lowercase() }

        return Catalog(s.serverId, s.receiverName, System.currentTimeMillis(), movies, series)
    }

    private fun watchDto(w: WatchState) = WatchDto(
        positionMs = w.positionMs, durationMs = w.durationMs, completed = w.isCompleted, lastWatchedAt = w.lastWatchedAt,
        audioIndex = w.selectedAudioTrackIndex, subtitleIndex = w.selectedSubtitleTrackIndex,
        audioDelayMs = w.audioDelayMs, subtitleDelayMs = w.subtitleDelayMs
    )

    private fun sniffImage(bytes: ByteArray): String = when {
        bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
        bytes.size > 11 && bytes.copyOfRange(8, 12).decodeToString() == "WEBP" -> "image/webp"
        else -> "image/jpeg"
    }

    // endregion

    private companion object {
        const val SEEN_WRITE_INTERVAL_MS = 10L * 60 * 1000
        /** The detail pages show the first actors; the rest would only weigh down the catalog. */
        const val MAX_CAST = 20
    }
}
