package com.lecteur.feature.scanner.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.lecteur.core.data.library.FolderRepository
import com.lecteur.core.data.scan.LibraryScanner
import com.lecteur.core.data.scan.ScanProgress
import com.lecteur.core.data.scan.ScanSummary
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Scans one watched folder or all of them. Everything it commits is visible at once (the library fills as the scan goes);
 * cancelling it, or the system stopping it, loses nothing already filed: the next scan only handles what is left.
 */
@OptIn(FlowPreview::class)
@HiltWorker
class ScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scanner: LibraryScanner,
    private val folderRepository: FolderRepository,
    private val scheduler: ScanScheduler
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(null)

    override suspend fun doWork(): Result {
        val folderId = inputData.getLong(ScanData.FOLDER_ID, ScanData.ALL_FOLDERS)
        val force = inputData.getBoolean(ScanData.FORCE, false)

        val folders = folderRepository.scanTargets(folderId.takeIf { it != ScanData.ALL_FOLDERS })
        if (folders.isEmpty()) return Result.success()

        // The system may refuse a foreground start from the background (periodic scans): the scan then just runs without the notification
        runCatching { setForeground(foreground(null)) }

        val latest = MutableStateFlow<ScanProgress?>(null)
        var total = ScanSummary()

        try {
            scanLock.withLock {
                coroutineScope {
                    val reporter = launch {
                        // One update every 400 ms is plenty for the eye and spares the notification manager
                        latest.filterNotNull().sample(PROGRESS_INTERVAL_MS).collect { progress ->
                            setProgress(ScanData.progress(progress))
                            runCatching { setForeground(foreground(progress)) }
                        }
                    }
                    try {
                        for (folder in folders) {
                            total += scanner.scan(folder, force) { latest.value = it }
                        }
                    } finally {
                        reporter.cancel()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never loop on a crash: one more try, then give up until the next scheduled scan
            return if (runAttemptCount < 2) Result.retry() else Result.failure()
        }

        // New files may have no metadata yet; the identification worker waits for the network on its own
        scheduler.identify()
        return Result.success(ScanData.result(total))
    }

    private fun foreground(progress: ScanProgress?) = ScanNotifications.foregroundInfo(
        applicationContext, id, ScanNotifications.SCAN_NOTIFICATION_ID,
        JobText.scanTitle(progress), JobText.scanDetail(progress), JobText.fraction(progress)
    )

    private companion object {
        const val PROGRESS_INTERVAL_MS = 400L

        // Two workers scanning the same folder would file the same movie twice
        val scanLock = Mutex()
    }
}
