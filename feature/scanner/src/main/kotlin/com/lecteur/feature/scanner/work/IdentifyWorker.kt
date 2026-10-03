package com.lecteur.feature.scanner.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lecteur.core.data.identify.MetadataRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Looks up the movies and series that have no match yet. Runs only with a network (the scheduler's constraint);
 * if the connection drops mid-way it asks to be retried with a growing delay, and what was already matched stays matched.
 */
@OptIn(FlowPreview::class)
@HiltWorker
class IdentifyWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: MetadataRepository
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(0)

    override suspend fun doWork(): Result {
        runCatching { setForeground(foreground(0)) }

        val done = MutableStateFlow(0)
        val result = try {
            coroutineScope {
                val reporter = launch {
                    done.drop(1).sample(PROGRESS_INTERVAL_MS).collect { count ->
                        setProgress(workDataOf(ScanData.IDENTIFY_DONE to count))
                        runCatching { setForeground(foreground(count)) }
                    }
                }
                try {
                    repository.identifyPending(onProgress = { done.value = it })
                } finally {
                    reporter.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        }

        return when {
            result.notConfigured -> Result.success(workDataOf("not_configured" to true))
            result.offline -> Result.retry()
            else -> Result.success(workDataOf(ScanData.IDENTIFY_DONE to result.identified + result.toVerify))
        }
    }

    private fun foreground(done: Int) = ScanNotifications.foregroundInfo(
        applicationContext, id, ScanNotifications.IDENTIFY_NOTIFICATION_ID,
        "Métadonnées de la bibliothèque", JobText.identifyDetail(done), fraction = null
    )

    private companion object {
        const val PROGRESS_INTERVAL_MS = 500L
    }
}
