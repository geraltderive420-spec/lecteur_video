package com.lecteur.feature.scanner.work

import androidx.work.Data
import androidx.work.workDataOf
import com.lecteur.core.data.scan.ScanPhase
import com.lecteur.core.data.scan.ScanProgress
import com.lecteur.core.data.scan.ScanSummary

/** Keys and encoding of what travels between the workers and the UI through WorkManager's [Data]. */
object ScanData {
    const val FOLDER_ID = "folder_id"
    const val FORCE = "force"
    const val ALL_FOLDERS = -1L

    private const val P_PHASE = "phase"
    private const val P_FOLDER_ID = "p_folder_id"
    private const val P_FOLDER = "folder"
    private const val P_PROCESSED = "processed"
    private const val P_TOTAL = "total"
    private const val P_CURRENT = "current"

    const val IDENTIFY_DONE = "identify_done"

    const val R_ADDED = "added"
    const val R_UPDATED = "updated"
    const val R_MOVED = "moved"
    const val R_UNAVAILABLE = "unavailable"
    const val R_FAILED = "failed"
    const val R_UNREACHABLE = "unreachable"

    fun progress(progress: ScanProgress): Data = workDataOf(
        P_PHASE to progress.phase.name,
        P_FOLDER_ID to progress.folderId,
        P_FOLDER to progress.folderName,
        P_PROCESSED to progress.processed,
        P_TOTAL to progress.total,
        P_CURRENT to progress.currentFile
    )

    fun progressOf(data: Data): ScanProgress? {
        val phase = data.getString(P_PHASE)?.let { runCatching { ScanPhase.valueOf(it) }.getOrNull() } ?: return null
        return ScanProgress(
            folderId = data.getLong(P_FOLDER_ID, 0),
            folderName = data.getString(P_FOLDER).orEmpty(),
            phase = phase,
            processed = data.getInt(P_PROCESSED, 0),
            total = data.getInt(P_TOTAL, 0),
            currentFile = data.getString(P_CURRENT)
        )
    }

    fun result(summary: ScanSummary): Data = workDataOf(
        R_ADDED to summary.added, R_UPDATED to summary.updated, R_MOVED to summary.moved,
        R_UNAVAILABLE to summary.markedUnavailable, R_FAILED to summary.failed, R_UNREACHABLE to summary.folderUnreachable
    )
}

/** Wording of the notification and of the progress card. Pure, so it is testable and shared by both. */
object JobText {

    fun scanTitle(progress: ScanProgress?): String = when (progress?.phase) {
        null, ScanPhase.LISTING -> "Analyse de la bibliothèque"
        ScanPhase.ANALYZING, ScanPhase.FINISHING -> "Analyse de « ${progress.folderName} »"
    }

    fun scanDetail(progress: ScanProgress?): String = when {
        progress == null -> "Préparation…"
        progress.phase == ScanPhase.LISTING -> "Recherche des fichiers dans « ${progress.folderName} »…"
        progress.phase == ScanPhase.FINISHING -> "Finalisation…"
        progress.total == 0 -> "Aucun nouveau fichier"
        else -> buildString {
            append("${progress.processed} / ${progress.total} fichiers")
            progress.currentFile?.let { append(" · ").append(it) }
        }
    }

    fun identifyDetail(done: Int): String = if (done == 0) "Recherche des métadonnées…" else "Métadonnées trouvées pour $done titre${if (done > 1) "s" else ""}"

    /** Fraction of the folder done, or null while it is not measurable (listing, nothing to do). */
    fun fraction(progress: ScanProgress?): Float? =
        if (progress != null && progress.phase == ScanPhase.ANALYZING && progress.total > 0) progress.processed.toFloat() / progress.total else null

    fun summaryLine(added: Int, updated: Int, moved: Int, unavailable: Int, failed: Int): String {
        val parts = buildList {
            if (added > 0) add("$added ajouté${if (added > 1) "s" else ""}")
            if (updated > 0) add("$updated mis à jour")
            if (moved > 0) add("$moved déplacé${if (moved > 1) "s" else ""}")
            if (unavailable > 0) add("$unavailable indisponible${if (unavailable > 1) "s" else ""}")
            if (failed > 0) add("$failed en erreur")
        }
        return if (parts.isEmpty()) "Bibliothèque à jour" else parts.joinToString(" · ")
    }
}
