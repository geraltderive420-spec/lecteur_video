package com.lecteur.feature.scanner.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import java.util.UUID

/** The progress notification of library jobs: low importance (no sound), with a cancel action. */
internal object ScanNotifications {

    private const val CHANNEL_ID = "library_jobs"
    const val SCAN_NOTIFICATION_ID = 4101
    const val IDENTIFY_NOTIFICATION_ID = 4102

    fun foregroundInfo(
        context: Context,
        workId: UUID,
        notificationId: Int,
        title: String,
        detail: String,
        fraction: Float?
    ): ForegroundInfo {
        ensureChannel(context)
        val notification = build(context, workId, title, detail, fraction)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun build(context: Context, workId: UUID, title: String, detail: String, fraction: Float?): Notification {
        val cancel = WorkManager.getInstance(context).createCancelPendingIntent(workId)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(detail)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Annuler", cancel)
            .build()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Analyse de la bibliothèque", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progression de l'analyse des dossiers et de la recherche des métadonnées"
                    setShowBadge(false)
                }
            )
        }
    }
}
