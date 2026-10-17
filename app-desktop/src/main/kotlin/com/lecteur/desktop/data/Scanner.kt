package com.lecteur.desktop.data

import com.lecteur.core.common.file.FileFingerprint
import com.lecteur.core.common.match.CategoryFit
import com.lecteur.core.common.match.TitleSimilarity
import com.lecteur.core.common.nfo.NfoData
import com.lecteur.core.common.nfo.NfoParser
import com.lecteur.core.common.parser.FilenameParser
import com.lecteur.core.common.scan.EpisodeNumbering
import com.lecteur.core.common.scan.FoundFile
import com.lecteur.core.common.scan.KnownFile
import com.lecteur.core.common.scan.MoveResolver
import com.lecteur.core.common.scan.ScanPlanner
import com.lecteur.core.common.scan.SeriesKey
import com.lecteur.core.common.scan.VideoFiles
import com.lecteur.core.model.LibraryFolder
import com.lecteur.core.model.MatchState
import com.lecteur.core.model.MediaCategory
import com.lecteur.core.model.MediaFile
import com.lecteur.core.model.Movie
import com.lecteur.core.model.ParsedMediaInfo
import com.lecteur.core.model.Series
import com.lecteur.desktop.player.MediaProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

data class ScanStatus(
    val running: Boolean = false,
    val folderName: String? = null,
    val processed: Int = 0,
    val total: Int = 0,
    val currentFile: String? = null,
    /** Online identification after the scan. */
    val identifying: Boolean = false,
    val identified: Int = 0,
    val lastMessage: String? = null
)

/**
 * Brings the database in line with the watched folders: new files are fingerprinted, probed and filed under a movie or an
 * episode; moved or renamed files keep their row (and their progress); files deleted from a reachable folder leave the
 * library; an unreachable folder (unplugged drive) only marks its files unavailable. Then the identification pass runs.
 */
class Scanner(private val store: LibraryStore, private val identifier: Identifier) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _status = MutableStateFlow(ScanStatus())
    val status: StateFlow<ScanStatus> = _status.asStateFlow()

    /** Scans [folderIds] (all enabled folders when null), then identifies what is new. Ignored while a scan runs. */
    fun start(folderIds: List<Long>? = null, force: Boolean = false) {
        if (job?.isActive == true) return
        job = scope.launch {
            var message: String
            try {
                val folders = store.folders().filter { it.enabled && (folderIds == null || it.id in folderIds) }
                var added = 0
                for (folder in folders) added += scanFolder(folder, force)
                message = if (folders.isEmpty()) "Aucun dossier à analyser." else "Analyse terminée : $added nouveau(x) fichier(s)."
                _status.value = _status.value.copy(running = true, identifying = true, currentFile = null, lastMessage = message)
                val result = identifier.identifyPending { done -> _status.value = _status.value.copy(identified = done) }
                if (result.notConfigured) message += " Clé TMDB absente : pas d'affiches ni de résumés."
                else if (result.offline) message += " Pas de réseau : l'identification reprendra plus tard."
                else if (result.processed > 0) message += " ${result.identified} titre(s) identifié(s), ${result.toVerify} à vérifier."
            } catch (e: Exception) {
                message = "Erreur pendant l'analyse : ${e.message}"
            }
            store.refreshNow()
            _status.value = ScanStatus(lastMessage = message)
        }
    }

    fun cancel() {
        job?.cancel()
        _status.value = ScanStatus(lastMessage = "Analyse interrompue.")
    }

    /** Re-runs the online identification only (after a TMDB key or language change). */
    fun identifyOnly() {
        if (job?.isActive == true) return
        job = scope.launch {
            _status.value = ScanStatus(running = true, identifying = true)
            val result = identifier.identifyPending { done -> _status.value = _status.value.copy(identified = done) }
            store.refreshNow()
            _status.value = ScanStatus(
                lastMessage = when {
                    result.notConfigured -> "Clé TMDB absente ou refusée."
                    result.offline -> "Pas de réseau."
                    else -> "${result.identified} titre(s) identifié(s), ${result.toVerify} à vérifier."
                }
            )
        }
    }

    // region one folder

    private class Walk(val reachable: Boolean, val complete: Boolean, val videos: List<FoundFile>, val directories: Map<String, List<File>>)

    private suspend fun scanFolder(folder: LibraryFolder, force: Boolean): Int = coroutineScope {
        val root = File(folder.uri)
        val name = root.name.ifBlank { folder.uri }
        _status.value = ScanStatus(running = true, folderName = name)

        val walk = walk(root)
        if (!walk.reachable) {
            store.setFolderAvailability(folder.id, false)
            store.changed()
            return@coroutineScope 0
        }

        // The same folder can be watched once per category (like Plex libraries): each file then goes to the entry that fits it
        val siblings = store.folders().filter { it.uri.equals(folder.uri, ignoreCase = true) }
        val mine = if (siblings.size <= 1) walk.videos else {
            val categories = siblings.map { it.category }
            walk.videos.filter { CategoryFit.choose(categories, parse(it), it.relativePath) == folder.category }
        }
        if (siblings.size > 1) takeOver(siblings, folder, mine)
        relinkMisfiled(folder, mine)

        val known = store.filesOfFolder(folder.id)
        val plan = ScanPlanner.plan(known.map(::toKnown), mine, force)
        val everyPath = walk.videos.mapTo(HashSet()) { it.uri.lowercase() }
        val missing = plan.missing.filter { it.uri.lowercase() !in everyPath }
        store.setAvailability(plan.unchanged.mapNotNull { it.known }.filter { !it.isAvailable }.map { it.id }, true)

        val moves = MoveResolver(missing)
        val total = plan.toProcess.size
        var processed = 0
        var added = 0
        val limit = Semaphore(PARALLELISM)
        val nfoCache = HashMap<String, NfoData?>()

        // Inspection (fingerprint + probe) in parallel, database writes one after the other in order
        plan.toProcess.chunked(PARALLELISM * 4).forEach { chunk ->
            ensureActive()
            val inspected = chunk.map { planned ->
                async(Dispatchers.IO) {
                    limit.withPermit {
                        val file = File(planned.found.uri)
                        val parsed = parse(planned.found)
                        runCatching {
                            val fp = fingerprint(file)
                            val moved = if (planned.known == null) moves.claim(fp) else null
                            val info = if (moved != null && !force) null else MediaProbe.probe(file, parsed).withExternalSubtitles(file)
                            Inspected(planned.found, planned.known, moved, fp, parsed, info)
                        }.getOrNull()
                    }
                }
            }.awaitAll()

            for (result in inspected) {
                processed++
                if (result == null) continue
                _status.value = _status.value.copy(processed = processed, total = total, currentFile = result.found.name)
                val f = result.found
                val existing = result.known ?: result.moved
                if (existing != null) {
                    store.updateFile(existing.id, f.uri, f.name, f.size, f.lastModified, result.fingerprint, result.info)
                    if (!existing.isLinked) link(existing.id, folder, result, nfoCache)
                } else {
                    val id = store.insertFile(
                        MediaFile(
                            folderId = folder.id, uri = f.uri, displayPath = f.uri, fileName = f.name, sizeBytes = f.size,
                            fingerprint = result.fingerprint, lastModified = f.lastModified
                        ),
                        result.info
                    )
                    link(id, folder, result, nfoCache)
                    added++
                }
            }
            store.changed()
        }

        if (walk.complete) store.deleteFiles(moves.unclaimed().map { it.id })
        store.removeOrphans()
        store.setLastScanned(folder.id, System.currentTimeMillis())
        store.changed()
        added
    }

    private class Inspected(
        val found: FoundFile,
        val known: KnownFile?,
        val moved: KnownFile?,
        val fingerprint: String,
        val parsed: ParsedMediaInfo,
        val info: TechInfo?
    )

    private fun toKnown(f: MediaFile) = KnownFile(
        id = f.id, uri = f.uri, fingerprint = f.fingerprint, size = f.sizeBytes, lastModified = f.lastModified,
        isAvailable = f.isAvailable, isLinked = f.movieId != null || f.episodeId != null
    )

    private val parsedCache = java.util.concurrent.ConcurrentHashMap<String, ParsedMediaInfo>()

    private fun parse(found: FoundFile): ParsedMediaInfo = parsedCache.getOrPut(found.uri + "|" + found.relativePath) { NameRules.parse(found) }

    /**
     * Files of this entry that a sibling entry of the same folder holds (it was scanned first, or the naming rules
     * improved): they change hands with their row and progress. When both entries hold a row for the same file (scans
     * before this rule existed), the rows are merged into this entry's one.
     */
    private fun takeOver(siblings: List<LibraryFolder>, folder: LibraryFolder, mine: List<FoundFile>) {
        val others = siblings.filter { it.id != folder.id }.flatMap { store.filesOfFolder(it.id) }.groupBy { it.uri.lowercase() }
        if (others.isEmpty()) return
        val own = store.filesOfFolder(folder.id).associateBy { it.uri.lowercase() }
        for (file in mine) {
            val key = file.uri.lowercase()
            val theirs = others[key] ?: continue
            val keep = own[key]
            if (keep != null) {
                theirs.forEach { store.mergeFile(it.id, keep.id) }
            } else {
                val first = theirs.first()
                store.reassignFolder(first.id, folder.id)
                // Unlinked, so this scan files it again under this entry's category
                store.setLinks(first.id, null, null)
                theirs.drop(1).forEach { store.mergeFile(it.id, first.id) }
            }
        }
    }

    /** A file filed as a film that the rules now see as an episode (or the reverse) is unlinked, so this scan files it again. */
    private fun relinkMisfiled(folder: LibraryFolder, mine: List<FoundFile>) {
        val byPath = mine.associateBy { it.uri.lowercase() }
        val episodic = folder.category == MediaCategory.SERIES || folder.category == MediaCategory.ANIME || folder.category == MediaCategory.GENERIC
        for (row in store.filesOfFolder(folder.id)) {
            if (row.movieId == null && row.episodeId == null) continue
            val found = byPath[row.uri.lowercase()] ?: continue
            val parsed = parse(found)
            val wantEpisode = episodic && parsed.isSeries
            if ((row.episodeId != null) != wantEpisode) {
                store.setLinks(row.id, null, null)
                continue
            }
            // A film named wrongly by older rules ("Wall" for Wall-E) and not confirmed by anyone: filed again under its real name
            val movie = row.movieId?.let(store::movie) ?: continue
            val title = parsed.cleanTitle
            if (!movie.matchLocked && movie.matchState != com.lecteur.core.model.MatchState.IDENTIFIED && title.isNotBlank() &&
                LibraryStore.sortKey(title) != LibraryStore.sortKey(movie.originalTitle ?: movie.title) &&
                LibraryStore.sortKey(title) != LibraryStore.sortKey(movie.title) && parsed.year != null && parsed.year != movie.year
            ) store.setLinks(row.id, null, null)
        }
    }

    private fun walk(root: File): Walk {
        if (!root.isDirectory) return Walk(false, false, emptyList(), emptyMap())
        val videos = ArrayList<FoundFile>()
        val directories = HashMap<String, MutableList<File>>()
        var complete = true
        val rootPath = root.toPath()
        Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val n = dir.fileName?.toString().orEmpty()
                if (dir != rootPath && (n.startsWith('.') || n.startsWith('$') || n.equals("System Volume Information", true) || n.equals("@eaDir", true))) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val f = file.toFile()
                val rel = rootPath.relativize(file).toString().replace('\\', '/')
                directories.getOrPut(rel.substringBeforeLast('/', "")) { ArrayList() } += f
                if (attrs.isRegularFile && VideoFiles.isVideo(f.name) && !isSample(f, attrs.size())) {
                    videos += FoundFile(f.absolutePath, f.name, attrs.size(), attrs.lastModifiedTime().toMillis(), rel)
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                complete = false
                return FileVisitResult.CONTINUE
            }
        })
        return Walk(true, complete, videos, directories)
    }

    /** Release "sample" clips are not part of the library. */
    private fun isSample(f: File, size: Long): Boolean =
        size < 80L * 1024 * 1024 && Regex("(?i)(^|[._ -])sample([._ -]|$)").containsMatchIn(f.nameWithoutExtension)

    private fun fingerprint(file: File): String = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            FileFingerprint.compute(raf.length()) { offset, length ->
                val buffer = ByteArray(length)
                raf.seek(offset)
                var read = 0
                while (read < length) {
                    val n = raf.read(buffer, read, length - read)
                    if (n < 0) break
                    read += n
                }
                if (read == length) buffer else buffer.copyOf(read)
            }
        }
    }.getOrElse { FileFingerprint.fromLocation(file.absolutePath, file.length()) }

    private fun TechInfo.withExternalSubtitles(video: File): TechInfo {
        val base = video.nameWithoutExtension
        val siblings = video.parentFile?.listFiles()?.toList().orEmpty()
        val external = siblings.filter { it.extension.lowercase() in SUBTITLE_EXTENSIONS && it.name.startsWith(base, ignoreCase = true) }
            .sortedBy { it.name }
            .mapIndexed { i, f ->
                val tags = f.nameWithoutExtension.removePrefix(base).split('.', '_', ' ', '-').map { it.lowercase() }.filter { it.isNotBlank() }
                SubtitleTrackRow(
                    index = 1000 + i,
                    language = tags.firstOrNull { it.length in 2..3 && it.all(Char::isLetter) && it != "sdh" },
                    codec = f.extension.lowercase(),
                    externalPath = f.absolutePath,
                    title = f.name,
                    isForced = "forced" in tags || "force" in tags
                )
            }
        return copy(subtitles = subtitles + external)
    }

    // endregion

    // region linking

    private fun link(fileId: Long, folder: LibraryFolder, result: Inspected, nfoCache: HashMap<String, NfoData?>) {
        val parsed = result.parsed
        val videoFile = File(result.found.uri)
        when (folder.category) {
            MediaCategory.PERSONAL -> store.setLinks(fileId, movieFor(result, null, personal = true, videoFile), null)
            MediaCategory.MOVIES, MediaCategory.DOCUMENTARIES -> store.setLinks(fileId, movieFor(result, nfoFor(videoFile, false, nfoCache), false, videoFile), null)
            MediaCategory.SERIES, MediaCategory.ANIME, MediaCategory.GENERIC ->
                if (parsed.isSeries) store.setLinks(fileId, null, episodeFor(parsed, nfoFor(videoFile, true, nfoCache), videoFile))
                else store.setLinks(fileId, movieFor(result, nfoFor(videoFile, false, nfoCache), false, videoFile), null)
        }
    }

    private fun nfoFor(video: File, series: Boolean, cache: HashMap<String, NfoData?>): NfoData? {
        val candidates = if (series) {
            val dir = video.parentFile
            val showDir = if (dir != null && SEASON_FOLDER.matches(dir.name)) dir.parentFile else dir
            listOfNotNull(showDir?.let { File(it, "tvshow.nfo") })
        } else {
            listOf(File(video.parentFile, video.nameWithoutExtension + ".nfo"), File(video.parentFile, "movie.nfo"))
        }
        val file = candidates.firstOrNull { it.isFile } ?: return null
        return cache.getOrPut(file.absolutePath) {
            runCatching { if (file.length() < 512 * 1024) NfoParser.parse(file.readText()) else null }.getOrNull()
        }
    }

    private fun artwork(dir: File?, base: String?, names: List<String>): String? {
        val files = dir?.listFiles()?.filter { it.isFile && VideoFiles.isImage(it.name) } ?: return null
        val specific = base?.let { b -> files.firstOrNull { f -> names.any { n -> f.nameWithoutExtension.equals("$b-$n", true) } } }
        val generic = files.firstOrNull { f -> names.any { f.nameWithoutExtension.equals(it, true) } }
        return (specific ?: generic)?.toURI()?.toString()
    }

    private fun movieFor(result: Inspected, nfo: NfoData?, personal: Boolean, video: File): Long {
        nfo?.tmdbId?.let { id -> store.movieByTmdb(id)?.let { return it.id } }
        val parsed = result.parsed
        val title = (nfo?.title ?: parsed.cleanTitle).ifBlank { result.found.name.substringBeforeLast('.') }
        val singleVideo = (video.parentFile?.listFiles()?.count { VideoFiles.isVideo(it.name) } ?: 0) <= 1
        val base = video.nameWithoutExtension
        val poster = artwork(video.parentFile, base, POSTERS)?.takeIf { singleVideo || it.contains(base) }
        val backdrop = artwork(video.parentFile, base, BACKDROPS)?.takeIf { singleVideo || it.contains(base) }
        return store.insertMovie(
            Movie(
                title = title, originalTitle = nfo?.originalTitle, year = nfo?.year ?: parsed.year, overview = nfo?.overview,
                runtimeMinutes = nfo?.runtimeMinutes, rating = nfo?.rating, certification = nfo?.certification,
                tmdbId = nfo?.tmdbId, imdbId = nfo?.imdbId, posterPath = poster, backdropPath = backdrop,
                matchState = MatchState.UNIDENTIFIED, matchLocked = personal
            )
        )
    }

    private fun episodeFor(parsed: ParsedMediaInfo, nfo: NfoData?, video: File): Long {
        val seriesId = seriesFor(parsed, nfo, video)
        val season = parsed.seasonNumber ?: 1
        val number = parsed.episodeNumbers.firstOrNull() ?: parsed.absoluteEpisodeNumber ?: 1
        val airDate = if (EpisodeNumbering.isDateBased(season, number)) EpisodeNumbering.dateOf(season, number) else null
        return store.episodeId(seriesId, season, number, parsed.absoluteEpisodeNumber, airDate)
    }

    private fun seriesFor(parsed: ParsedMediaInfo, nfo: NfoData?, video: File): Long {
        val keys = SeriesKey.lookupKeys(parsed.cleanTitle, parsed.year)
        for ((index, key) in keys.withIndex()) {
            val owner = store.seriesForAlias(key) ?: continue
            val isBareTitle = keys.size > 1 && index == keys.lastIndex
            if (isBareTitle && parsed.year != null) {
                val known = store.series(owner)?.firstAirDate?.take(4)?.toIntOrNull()
                if (known != null && kotlin.math.abs(known - parsed.year!!) > 1) continue
            }
            return owner
        }
        nfo?.tmdbId?.let { id -> store.seriesByTmdb(id)?.let { register(it.id, parsed); return it.id } }

        val dir = video.parentFile
        val showDir = if (dir != null && SEASON_FOLDER.matches(dir.name)) dir.parentFile else dir
        val title = (nfo?.title ?: parsed.cleanTitle).ifBlank { "Série sans titre" }
        val id = store.insertSeries(
            Series(
                title = title,
                originalTitle = nfo?.originalTitle ?: NameRules.fileTitle(video.name, video.parentFile?.name)?.takeIf { !it.equals(title, ignoreCase = true) },
                firstAirDate = (nfo?.year ?: parsed.year)?.toString(),
                overview = nfo?.overview, rating = nfo?.rating, certification = nfo?.certification, tmdbId = nfo?.tmdbId,
                imdbId = nfo?.imdbId, posterPath = artwork(showDir, null, POSTERS), backdropPath = artwork(showDir, null, BACKDROPS)
            )
        )
        register(id, parsed)
        nfo?.title?.let { store.putAlias(TitleSimilarity.normalize(it), id, replace = false) }
        return id
    }

    private fun register(seriesId: Long, parsed: ParsedMediaInfo) {
        val keys = SeriesKey.lookupKeys(parsed.cleanTitle, parsed.year)
        keys.forEachIndexed { index, key -> store.putAlias(key, seriesId, replace = index == 0 && keys.size > 1) }
    }

    // endregion

    private companion object {
        const val PARALLELISM = 4
        val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "sub", "idx", "sup")
        val SEASON_FOLDER = Regex("(?i)^(?:season|saison|série|serie|s)[ ._-]*\\d{1,3}$|^specials?$|^extras?$")
        val POSTERS = listOf("poster", "folder", "cover")
        val BACKDROPS = listOf("fanart", "backdrop", "background")
    }
}
