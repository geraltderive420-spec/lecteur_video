package com.lecteur.feature.scanner.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.lecteur.core.data.scan.ScanProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** What the UI shows about the background jobs. */
data class LibraryJobState(
    val scanRunning: Boolean = false,
    val scanQueued: Boolean = false,
    val scanProgress: ScanProgress? = null,
    val identifyRunning: Boolean = false,
    /** Waiting for a connection (or for its turn). */
    val identifyQueued: Boolean = false,
    val identifiedSoFar: Int = 0
) {
    val isBusy: Boolean get() = scanRunning || scanQueued || identifyRunning
}

@Singleton
class ScanScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val workManager get() = WorkManager.getInstance(context)

    /** Scans every active folder. A scan already running is left alone; [force] restarts it and re-inspects every file. */
    fun scanAll(force: Boolean = false) = enqueueScan(WORK_SCAN_ALL, ScanData.ALL_FOLDERS, force, replace = force, expedited = true)

    fun scanFolder(folderId: Long, force: Boolean = false) =
        enqueueScan("$WORK_SCAN_FOLDER$folderId", folderId, force, replace = force, expedited = true)

    /** Called at launch, when the setting asks for it. Quiet: no expedited foreground start while the app is just opening. */
    fun scanOnLaunch() = enqueueScan(WORK_SCAN_ALL, ScanData.ALL_FOLDERS, force = false, replace = false, expedited = false)

    /** @param hours 0 switches the periodic scan off. */
    fun schedulePeriodic(hours: Int) {
        if (hours <= 0) {
            workManager.cancelUniqueWork(WORK_SCAN_PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<ScanWorker>(hours.toLong(), TimeUnit.HOURS)
            .setInputData(workDataOf(ScanData.FOLDER_ID to ScanData.ALL_FOLDERS))
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .addTag(TAG_SCAN)
            .build()
        // UPDATE keeps the running schedule when nothing changed, and applies a new interval otherwise
        workManager.enqueueUniquePeriodicWork(WORK_SCAN_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Matches new titles online as soon as a connection allows; harmless to call when there is nothing to match. */
    fun identify() {
        val request = OneTimeWorkRequestBuilder<IdentifyWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .addTag(TAG_IDENTIFY)
            .build()
        workManager.enqueueUniqueWork(WORK_IDENTIFY, ExistingWorkPolicy.KEEP, request)
    }

    fun cancelAll() {
        workManager.cancelAllWorkByTag(TAG_SCAN)
        workManager.cancelAllWorkByTag(TAG_IDENTIFY)
    }

    /** Built on demand: WorkManager must not be touched before the application has set its configuration up. */
    val jobState: Flow<LibraryJobState> get() = combine(
        workManager.getWorkInfosByTagFlow(TAG_SCAN),
        workManager.getWorkInfosByTagFlow(TAG_IDENTIFY)
    ) { scans, identifies ->
        val runningScan = scans.firstOrNull { it.state == WorkInfo.State.RUNNING }
        val runningIdentify = identifies.firstOrNull { it.state == WorkInfo.State.RUNNING }
        LibraryJobState(
            scanRunning = runningScan != null,
            scanQueued = runningScan == null && scans.any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 0 && !isPeriodic(it) },
            scanProgress = runningScan?.progress?.let(ScanData::progressOf),
            identifyRunning = runningIdentify != null,
            identifyQueued = runningIdentify == null && identifies.any { it.state == WorkInfo.State.ENQUEUED },
            identifiedSoFar = runningIdentify?.progress?.getInt(ScanData.IDENTIFY_DONE, 0) ?: 0
        )
    }

    private fun isPeriodic(info: WorkInfo) = info.periodicityInfo != null

    private fun enqueueScan(name: String, folderId: Long, force: Boolean, replace: Boolean, expedited: Boolean) {
        val request = OneTimeWorkRequestBuilder<ScanWorker>()
            .setInputData(workDataOf(ScanData.FOLDER_ID to folderId, ScanData.FORCE to force))
            .apply { if (expedited) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
            .addTag(TAG_SCAN)
            .build()
        workManager.enqueueUniqueWork(name, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    private companion object {
        const val TAG_SCAN = "library-scan"
        const val TAG_IDENTIFY = "library-identify"
        const val WORK_SCAN_ALL = "library-scan-all"
        const val WORK_SCAN_FOLDER = "library-scan-folder-"
        const val WORK_SCAN_PERIODIC = "library-scan-periodic"
        const val WORK_IDENTIFY = "library-identify"
    }
}
