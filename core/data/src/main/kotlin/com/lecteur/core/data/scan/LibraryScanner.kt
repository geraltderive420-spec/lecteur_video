package com.lecteur.core.data.scan

import com.lecteur.core.common.nfo.NfoData
import com.lecteur.core.common.nfo.NfoParser
import com.lecteur.core.common.match.CategoryFit
import com.lecteur.core.common.parser.FilenameParser
import com.lecteur.core.common.scan.FoundFile
import com.lecteur.core.common.scan.KnownFile
import com.lecteur.core.common.scan.MoveResolver
import com.lecteur.core.common.scan.PlannedFile
import com.lecteur.core.common.scan.ScanPlanner
import com.lecteur.core.data.library.LibraryWriteLock
import com.lecteur.core.data.playback.StoragePaths
import com.lecteur.core.database.dao.LibraryFolderDao
import com.lecteur.core.database.dao.MediaFileDao
import com.lecteur.core.database.entity.AudioTrackInfoEntity
import com.lecteur.core.database.entity.LibraryFolderEntity
import com.lecteur.core.database.entity.MediaFileEntity
import com.lecteur.core.database.entity.SubtitleTrackInfoEntity
import com.lecteur.core.model.HdrType
import com.lecteur.core.model.VideoCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

enum class ScanPhase { LISTING, ANALYZING, FINISHING }

data class ScanProgress(
    val folderId: Long,
    val folderName: String,
    val phase: ScanPhase,
    val processed: Int = 0,
    val total: Int = 0,
    val currentFile: String? = null
)

data class ScanSummary(
    val added: Int = 0,
    val updated: Int = 0,
    val moved: Int = 0,
    val unchanged: Int = 0,
    val markedUnavailable: Int = 0,
    /** Files recorded without technical details because they could not be read. */
    val unreadable: Int = 0,
    val failed: Int = 0,
    /** The folder itself could not be listed (drive unplugged, access revoked): its files were marked unavailable. */
    val folderUnreachable: Boolean = false
) {
    operator fun plus(other: ScanSummary) = ScanSummary(
        added + other.added, updated + other.updated, moved + other.moved, unchanged + other.unchanged,
        markedUnavailable + other.markedUnavailable, unreadable + other.unreadable, failed + other.failed,
        folderUnreachable || other.folderUnreachable
    )
}

/**
 * Brings the database in line with one watched folder: new files are fingerprinted, inspected and filed under a movie or an
 * episode; moved or renamed files keep their row (and their progress); vanished files are marked unavailable, never deleted.
 * Files are inspected a few at a time, but every database write happens on this coroutine, one file after the other,
 * so the library fills up as the scan goes and two files of the same show cannot race to create the series.
 */
class LibraryScanner @Inject constructor(
    private val folderDao: LibraryFolderDao,
    private val mediaFileDao: MediaFileDao,
    private val walker: FolderWalker,
    private val inspector: FileInspector,
    private val sidecarReader: SidecarReader,
    private val linker: LibraryLinker,
    private val writeLock: LibraryWriteLock
) {

    suspend fun scan(
        folderId: Long,
        force: Boolean = false,
        now: () -> Long = System::currentTimeMillis,
        onProgress: (ScanProgress) -> Unit = {}
    ): ScanSummary {
        val folder = folderDao.getFolderById(folderId) ?: return ScanSummary()
        val name = folderName(folder)

        onProgress(ScanProgress(folder.id, name, ScanPhase.LISTING))
        val walk = walker.walk(folder.uri, name)
        if (!walk.reachable) {
            // Unplugged drive or revoked access: everything is unavailable, nothing is forgotten
            mediaFileDao.setFolderAvailability(folder.id, false)
            return ScanSummary(folderUnreachable = true)
        }

        // The same folder can be watched once per category (like Plex libraries): each file then goes to the entry that fits it
        val entries = folderDao.getFoldersByUri(folder.uri)
        val allFiles = walk.videoFiles
        val mine = if (entries.size <= 1) allFiles else {
            val categories = entries.map { it.category }
            allFiles.filter { CategoryFit.choose(categories, parse(it), it.relativePath) == folder.category }
        }
        val takenOver = takeOver(entries, folder, mine)

        val known = mediaFileDao.getMediaFilesByFolder(folder.id)
        val plan = ScanPlanner.plan(known.map(::toKnown), mine, force)
        // A file of this entry that now belongs to a sibling entry is still on disk: not missing
        val everyUri = allFiles.mapTo(HashSet()) { it.uri }
        val missing = plan.missing.filter { it.uri !in everyUri }

        val backOnline = plan.unchanged.mapNotNull { it.known }.filter { !it.isAvailable }.map { it.id }
        backOnline.chunked(SQL_CHUNK).forEach { mediaFileDao.setAvailability(it, true) }

        val moves = MoveResolver(missing)
        val context = ScanContext(folder, walk, name)
        val total = plan.toProcess.size
        var summary = ScanSummary(unchanged = plan.unchanged.size)

        if (total > 0) {
            summary += analyze(context, plan.toProcess, moves, now) { processed, current ->
                onProgress(ScanProgress(folder.id, name, ScanPhase.ANALYZING, processed, total, current))
            }
        }

        onProgress(ScanProgress(folder.id, name, ScanPhase.FINISHING, total, total))
        var gone = 0
        // With part of the tree unreadable, an absent file may simply be in the part we could not see
        if (walk.complete) {
            val vanished = moves.unclaimed().map { it.id }
            vanished.chunked(SQL_CHUNK).forEach { mediaFileDao.setAvailability(it, false) }
            gone = vanished.size
        }

        if (takenOver > 0) writeLock.withLock { linker.removeOrphans() }
        folderDao.setLastScanned(folder.id, now())
        return summary + ScanSummary(markedUnavailable = gone)
    }

    /** Files that a sibling entry of the same folder holds but that this entry's category fits better change hands, keeping their row and progress. */
    private suspend fun takeOver(entries: List<LibraryFolderEntity>, folder: LibraryFolderEntity, mine: List<FoundFile>): Int {
        if (entries.size <= 1) return 0
        val owned = entries.filter { it.id != folder.id }.flatMap { mediaFileDao.getMediaFilesByFolder(it.id) }.associateBy { it.uri }
        var count = 0
        for (file in mine) {
            val row = owned[file.uri] ?: continue
            writeLock.withLock {
                mediaFileDao.reassignFolder(row.id, folder.id)
                // Unlinked, so this scan files it again under the new category's rules
                mediaFileDao.setLinks(row.id, null, null)
            }
            count++
        }
        return count
    }

    private class ScanContext(val folder: LibraryFolderEntity, val walk: WalkResult, val rootName: String) {
        val directories: Map<String, List<WalkedEntry>> = walk.directories.associate { it.relativePath to it.entries }
        val nfoCache = HashMap<String, NfoData?>()
    }

    private sealed interface Outcome {
        val planned: PlannedFile

        class Moved(override val planned: PlannedFile, val from: KnownFile) : Outcome
        class Inspected(override val planned: PlannedFile, val fingerprint: String, val info: TechnicalInfo?) : Outcome
        class Failed(override val planned: PlannedFile, val error: Throwable) : Outcome
    }

    private suspend fun analyze(
        context: ScanContext,
        work: List<PlannedFile>,
        moves: MoveResolver,
        now: () -> Long,
        onStep: (processed: Int, current: String?) -> Unit
    ): ScanSummary = coroutineScope {
        val limit = Semaphore(INSPECTION_PARALLELISM)
        val results = Channel<Outcome>(Channel.BUFFERED)

        val producers = work.map { planned ->
            async {
                limit.withPermit {
                    results.send(
                        try {
                            inspect(planned, moves)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Outcome.Failed(planned, e)
                        }
                    )
                }
            }
        }

        var summary = ScanSummary()
        try {
            repeat(work.size) { index ->
                ensureActive()
                val outcome = results.receive()
                summary += try {
                    writeLock.withLock { commit(context, outcome, now) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ScanSummary(failed = 1)
                }
                onStep(index + 1, outcome.planned.found.name)
            }
        } finally {
            producers.forEach { it.cancel() }
            results.close()
        }
        summary
    }

    private suspend fun inspect(planned: PlannedFile, moves: MoveResolver): Outcome {
        val found = planned.found
        val fingerprint = inspector.fingerprint(found.uri, found.size)

        // Same content under a new name or location: the old row follows it, no need to read the file again
        if (planned.known == null) {
            moves.claim(fingerprint)?.let { return Outcome.Moved(planned, it) }
        }

        val parsed = parse(found)
        return Outcome.Inspected(planned, fingerprint, inspector.inspect(found.uri, parsed.hdrType, parsed.extension))
    }

    private suspend fun commit(context: ScanContext, outcome: Outcome, now: () -> Long): ScanSummary {
        val found = outcome.planned.found
        val folder = context.folder

        return when (outcome) {
            is Outcome.Failed -> ScanSummary(failed = 1)

            is Outcome.Moved -> {
                mediaFileDao.relocate(
                    id = outcome.from.id,
                    uri = found.uri,
                    displayPath = displayPath(folder, found),
                    fileName = found.name,
                    lastModified = found.lastModified,
                    size = found.size
                )
                ScanSummary(moved = 1)
            }

            is Outcome.Inspected -> {
                val info = outcome.info
                val existing = outcome.planned.known?.let { mediaFileDao.getMediaFileById(it.id) }

                val entity = MediaFileEntity(
                    id = existing?.id ?: 0,
                    folderId = folder.id,
                    uri = found.uri,
                    displayPath = displayPath(folder, found),
                    fileName = found.name,
                    size = found.size,
                    fingerprint = outcome.fingerprint,
                    lastModified = found.lastModified,
                    durationMs = info?.durationMs,
                    container = info?.container,
                    videoCodec = info?.videoCodec ?: VideoCodec.UNKNOWN,
                    width = info?.width,
                    height = info?.height,
                    hdrType = info?.hdrType ?: HdrType.NONE,
                    movieId = existing?.movieId,
                    episodeId = existing?.episodeId,
                    addedAt = existing?.addedAt ?: now(),
                    isAvailable = true
                )

                val id = mediaFileDao.saveScannedFile(entity)
                storeTracks(id, info)

                if (entity.movieId == null && entity.episodeId == null) {
                    val parsed = parse(found)
                    val asSeries = LinkPolicy.decide(folder.category, parsed) == LinkTarget.SERIES
                    linker.link(id, found, parsed, folder.category, localMetadata(context, found, asSeries))
                }

                ScanSummary(
                    added = if (existing == null) 1 else 0,
                    updated = if (existing != null) 1 else 0,
                    unreadable = if (info == null) 1 else 0
                )
            }
        }
    }

    private suspend fun storeTracks(mediaFileId: Long, info: TechnicalInfo?) {
        mediaFileDao.clearAudioTracks(mediaFileId)
        mediaFileDao.clearSubtitleTracks(mediaFileId)
        if (info == null) return
        mediaFileDao.insertAudioTracks(info.audioTracks.map {
            AudioTrackInfoEntity(
                mediaFileId = mediaFileId, trackIndex = it.index, language = it.language, codec = it.codec,
                channels = it.channels, title = it.title, isDefault = it.isDefault, isForced = it.isForced
            )
        })
        mediaFileDao.insertSubtitleTracks(info.subtitleTracks.map {
            SubtitleTrackInfoEntity(
                mediaFileId = mediaFileId, trackIndex = it.index, language = it.language, codec = it.codec,
                title = it.title, isDefault = it.isDefault, isForced = it.isForced
            )
        })
    }

    private suspend fun localMetadata(context: ScanContext, found: FoundFile, treatAsSeries: Boolean): LocalMetadata {
        val directory = found.relativePath.removePrefix(context.rootName + "/").substringBeforeLast('/', missingDelimiterValue = "")
        val refs = if (treatAsSeries) {
            SidecarResolver.forSeries(context.directories, directory)
        } else {
            SidecarResolver.forMovie(context.directories[directory].orEmpty(), found.name)
        }
        if (refs == SidecarRefs.NONE) return LocalMetadata.NONE

        val nfo = refs.nfo?.let { entry ->
            context.nfoCache.getOrPut(entry.uri) { sidecarReader.readText(entry.uri)?.let(NfoParser::parse) }
        }
        return LocalMetadata(nfo, refs.poster?.uri, refs.backdrop?.uri)
    }

    private fun parse(found: FoundFile) = FilenameParser.parse(found.name, found.parentName, found.grandparentName)

    private fun toKnown(file: MediaFileEntity) = KnownFile(
        id = file.id, uri = file.uri, fingerprint = file.fingerprint, size = file.size, lastModified = file.lastModified,
        isAvailable = file.isAvailable, isLinked = file.movieId != null || file.episodeId != null
    )

    private fun displayPath(folder: LibraryFolderEntity, found: FoundFile): String =
        StoragePaths.fromUri(found.uri) ?: (folder.displayPath.trimEnd('/') + "/" + found.relativePath.substringAfter('/'))

    private fun folderName(folder: LibraryFolderEntity): String =
        folder.displayPath.trimEnd('/').substringAfterLast('/').ifEmpty { folder.displayPath }

    private companion object {
        // Probing is CPU-bound (FFmpeg) and the files may share one slow USB drive: a few at a time is the sweet spot
        const val INSPECTION_PARALLELISM = 3
        // SQLite caps the number of bound variables per statement
        const val SQL_CHUNK = 500
    }
}
