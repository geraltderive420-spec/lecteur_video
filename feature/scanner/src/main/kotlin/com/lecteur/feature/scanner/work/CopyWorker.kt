package com.lecteur.feature.scanner.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lecteur.core.common.library.LibraryClient
import com.lecteur.core.data.shared.CopySource
import com.lecteur.core.data.shared.CopyTarget
import com.lecteur.core.data.shared.PhoneCopyQueue
import com.lecteur.core.data.shared.SharedLibraryRepository
import com.lecteur.core.model.Formatters
import dagger.Binds
import dagger.Module
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Copies of a PC's files onto the phone, one work per file in a single queue: they run one after the other, survive the
 * app being closed, and resume where they stopped (the partial file is kept as "name.part" until it is complete).
 */
@Singleton
class CopyScheduler @Inject constructor(@ApplicationContext private val context: Context) : PhoneCopyQueue {

    override fun enqueue(serverId: String, fileIds: List<Long>, target: CopyTarget, height: Int?) {
        val requests: List<OneTimeWorkRequest> = fileIds.map { fileId ->
            OneTimeWorkRequestBuilder<CopyWorker>()
                .setInputData(
                    workDataOf(
                        CopyWorker.SERVER to serverId, CopyWorker.FILE to fileId,
                        CopyWorker.TREE to target.treeUri, CopyWorker.FOLDER to target.folderId, CopyWorker.HEIGHT to (height ?: 0)
                    )
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
        }
        if (requests.isEmpty()) return
        // A copy never fails the queue (it reports and steps aside), so appending keeps the order the user asked for
        WorkManager.getInstance(context).beginUniqueWork(QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, requests).enqueue()
    }

    private companion object {
        const val QUEUE = "phone-copies"
        const val TAG = "phone-copy"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CopyModule {
    @Binds abstract fun phoneCopyQueue(impl: CopyScheduler): PhoneCopyQueue
}

@HiltWorker
class CopyWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val shared: SharedLibraryRepository,
    private val scheduler: ScanScheduler
) : CoroutineWorker(context, params) {

    private var title = "Copie sur le téléphone"

    override suspend fun getForegroundInfo(): ForegroundInfo = CopyNotifications.progress(applicationContext, id, title, "En attente…", null)

    override suspend fun doWork(): Result {
        val serverId = inputData.getString(SERVER) ?: return Result.success()
        val fileId = inputData.getLong(FILE, -1)
        val tree = inputData.getString(TREE)?.let(Uri::parse) ?: return Result.success()
        val folderId = inputData.getLong(FOLDER, -1)
        val height = inputData.getInt(HEIGHT, 0).takeIf { it > 0 }

        val source = shared.copySource(serverId, fileId, height)
            ?: return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else failed("Le PC ne répond pas ou ce fichier n'y est plus.")
        title = source.title
        runCatching { setForeground(CopyNotifications.progress(applicationContext, id, title, "Préparation…", null)) }

        return try {
            withContext(Dispatchers.IO) { copy(tree, source) }
            if (folderId > 0) scheduler.scanFolder(folderId)
            CopyNotifications.done(applicationContext, title, "Copié dans ${(source.directories + source.fileName).joinToString("/")}")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            failed("Le téléphone refuse l'écriture dans ce dossier. Relancez la copie pour choisir le dossier à nouveau.")
        } catch (e: ConversionFailed) {
            failed(e.message ?: "Le PC n'a pas pu convertir ce fichier.")
        } catch (e: IOException) {
            // The network dropped, the PC went to sleep: the next attempt resumes from the partial file
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else failed(e.message ?: "La copie a échoué.")
        }
    }

    private suspend fun copy(treeUri: Uri, source: CopySource) {
        val tree = Tree(applicationContext.contentResolver, treeUri)
        val dir = source.directories.fold(tree.rootId) { parent, name -> tree.directory(parent, name) }

        val existing = tree.child(dir, source.fileName)
        // Copied already (a smaller version has no size known in advance: it is there once renamed from .part)
        if (existing != null && (existing.size == source.sizeBytes || (source.sizeBytes < 0 && existing.size > 0))) return copySubtitles(tree, dir, source)

        val partName = source.fileName + ".part"
        val part = tree.child(dir, partName) ?: Tree.Entry(tree.create(dir, partName), 0)
        download(tree, part, source)

        existing?.let { tree.delete(it.id) }
        tree.rename(part.id, source.fileName)
        copySubtitles(tree, dir, source)
    }

    private suspend fun download(tree: Tree, part: Tree.Entry, source: CopySource) {
        var offset = if (source.sizeBytes >= 0) part.size.coerceAtMost(source.sizeBytes) else part.size
        val connection = connectWhenReady(source, offset)
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) throw IOException("Le PC a répondu $code.")
            if (code == HttpURLConnection.HTTP_OK) offset = 0 // range ignored: start over
            // A smaller version's size is learnt here, once the PC has made it
            val total = if (source.sizeBytes >= 0) source.sizeBytes
            else connection.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: connection.contentLengthLong.takeIf { it >= 0 }?.plus(offset) ?: -1
            val mode = if (offset > 0) "wa" else "wt"
            val output = tree.resolver.openOutputStream(tree.uri(part.id), mode) ?: throw IOException("Impossible d'écrire le fichier.")
            output.use { out ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER)
                    var written = offset
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        val now = System.currentTimeMillis()
                        if (now - lastReport > REPORT_INTERVAL_MS) {
                            lastReport = now
                            report(written, total)
                        }
                    }
                    if (total > 0 && written < total) throw IOException("Copie interrompue.")
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The connection to read the file from. For a smaller version, the PC answers 202 while it converts the file: the
     * notification shows how far it got, and the question is asked again every few seconds.
     */
    private suspend fun connectWhenReady(source: CopySource, offset: Long): HttpURLConnection {
        while (true) {
            val connection = (URL(source.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }
            when (connection.responseCode) {
                HttpURLConnection.HTTP_ACCEPTED -> {
                    val progress = runCatching { connection.inputStream.use { it.readBytes() }.decodeToString() }.getOrNull()?.let(LibraryClient::progressOf)
                    connection.disconnect()
                    val detail = when {
                        progress?.progress != null -> "Conversion en ${source.height}p sur le PC : ${(progress.progress!! * 100).toInt()} %"
                        progress?.queued == true -> "En attente du PC (une autre conversion d'abord)…"
                        else -> "Conversion en ${source.height}p sur le PC…"
                    }
                    runCatching { setForeground(CopyNotifications.progress(applicationContext, id, title, detail, progress?.progress)) }
                    delay(CONVERSION_POLL_MS)
                }
                HttpURLConnection.HTTP_INTERNAL_ERROR -> {
                    val message = runCatching { connection.errorStream.use { it.readBytes() }.decodeToString() }.getOrNull()?.let(LibraryClient::errorMessageOf)
                    connection.disconnect()
                    throw ConversionFailed(message ?: "Le PC n'a pas pu convertir ce fichier.")
                }
                else -> return connection
            }
        }
    }

    private class ConversionFailed(message: String) : Exception(message)

    private suspend fun report(written: Long, total: Long) {
        val fraction = if (total > 0) (written.toFloat() / total).coerceIn(0f, 1f) else null
        val detail = if (total > 0) "${(fraction!! * 100).toInt()} % · ${Formatters.size(written)} sur ${Formatters.size(total)}" else Formatters.size(written)
        runCatching { setForeground(CopyNotifications.progress(applicationContext, id, title, detail, fraction)) }
    }

    /** Subtitles are small and optional: one that fails does not fail the copy. */
    private fun copySubtitles(tree: Tree, dir: String, source: CopySource) {
        source.subtitles.forEach { sub ->
            if (tree.child(dir, sub.fileName) != null) return@forEach
            runCatching {
                val bytes = URL(sub.url).openStream().use { it.readBytes() }
                val id = tree.create(dir, sub.fileName)
                tree.resolver.openOutputStream(tree.uri(id), "wt")?.use { it.write(bytes) }
            }
        }
    }

    private fun failed(message: String): Result {
        CopyNotifications.done(applicationContext, "Copie impossible : $title", message)
        // Success for the queue: the copies after this one still run
        return Result.success()
    }

    /** The few Storage Access Framework calls a copy needs, on the folder the user granted. */
    private class Tree(val resolver: ContentResolver, private val tree: Uri) {
        data class Entry(val id: String, val size: Long)

        val rootId: String = DocumentsContract.getTreeDocumentId(tree)

        fun uri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

        fun child(parent: String, name: String): Entry? {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE)
            resolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == name) return Entry(c.getString(0), if (c.isNull(2)) 0 else c.getLong(2))
                }
            }
            return null
        }

        fun directory(parent: String, name: String): String =
            child(parent, name)?.id ?: create(parent, name, DocumentsContract.Document.MIME_TYPE_DIR)

        /** "application/octet-stream" keeps the name as given: a provider adds no extension of its own. */
        fun create(parent: String, name: String, mime: String = "application/octet-stream"): String {
            val created = DocumentsContract.createDocument(resolver, uri(parent), mime, name) ?: throw IOException("Impossible de créer « $name ».")
            return DocumentsContract.getDocumentId(created)
        }

        fun rename(id: String, name: String) {
            DocumentsContract.renameDocument(resolver, uri(id), name)
        }

        fun delete(id: String) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri(id)) }
        }
    }

    companion object {
        const val SERVER = "server"
        const val FILE = "file"
        const val TREE = "tree"
        const val FOLDER = "folder"
        const val HEIGHT = "height"
        private const val CONVERSION_POLL_MS = 5_000L
        private const val MAX_ATTEMPTS = 5
        private const val BUFFER = 256 * 1024
        private const val REPORT_INTERVAL_MS = 1_000L
    }
}

/** The copies' own notification channel: progress while copying, one line when a copy ends. */
internal object CopyNotifications {
    private const val CHANNEL_ID = "phone_copies"
    private const val PROGRESS_ID = 4103
    private var nextDoneId = 4200

    fun progress(context: Context, workId: UUID, title: String, detail: String, fraction: Float?): ForegroundInfo {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(detail)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Annuler", WorkManager.getInstance(context).createCancelPendingIntent(workId))
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ForegroundInfo(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(PROGRESS_ID, notification)
    }

    fun done(context: Context, title: String, detail: String) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(nextDoneId++, notification) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Copies sur le téléphone", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progression des films et épisodes copiés depuis un PC"
                    setShowBadge(false)
                }
            )
        }
    }
}
