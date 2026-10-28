package com.lecteur.desktop.transcode

import com.lecteur.core.common.library.ServedFile
import com.lecteur.core.common.library.TranscodeProgressDto
import com.lecteur.core.common.library.Transcoded
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Smaller copies of files for devices to keep, made one at a time in the background and kept a while in [root], so a
 * download cut short resumes on the same file. The device asks again every few seconds until the copy is ready.
 */
class TranscodedDownloads(private val root: File, private val encoder: () -> VideoEncoder?) {
    private data class Key(val fileId: Long, val height: Int)

    private class Job {
        @Volatile var progress: Float? = null
        @Volatile var started = false
        @Volatile var failure: String? = null
        @Volatile var process: Process? = null
    }

    private val jobs = ConcurrentHashMap<Key, Job>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "transcode-downloads").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    init {
        root.mkdirs()
        root.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
        cleanUp()
    }

    fun request(fileId: Long, height: Int, source: TranscodeSource): Transcoded {
        val key = Key(fileId, height)
        val output = output(key)
        if (output.isFile && output.lastModified() >= source.file.lastModified()) {
            jobs.remove(key)
            // Kept from the last request on: a copy someone downloads does not get cleaned up under them
            output.setLastModified(System.currentTimeMillis())
            return Transcoded.Ready(ServedFile(output.name, "video/x-matroska", output.length()) { offset -> FileInputStream(output).also { it.channel.position(offset) } })
        }
        jobs[key]?.let { job ->
            job.failure?.let {
                // Reported once; asking again tries again
                jobs.remove(key)
                return Transcoded.Failed(it)
            }
            return Transcoded.Preparing(TranscodeProgressDto(job.progress, queued = !job.started))
        }
        val enc = encoder() ?: return Transcoded.Failed("Le transcodage n'est pas disponible sur ce PC.")
        val job = Job()
        jobs[key] = job
        worker.execute { run(key, job, source, enc) }
        return Transcoded.Preparing(TranscodeProgressDto(null, queued = true))
    }

    fun stopAll() {
        jobs.values.forEach { it.process?.destroy() }
        worker.shutdownNow()
    }

    private fun run(key: Key, job: Job, source: TranscodeSource, encoder: VideoEncoder) {
        job.started = true
        cleanUp()
        val output = output(key)
        val part = File(root, output.name + ".part")
        // Hardware decoding first, then on the processor; subtitles Matroska refuses are dropped last
        val attempts = listOf(true to true, false to true, false to false)
        for ((hw, subtitles) in attempts) {
            part.delete()
            val args = TranscodeArgs.download(source.file, part, key.height, source.hdr, encoder, hw, subtitles)
            val process = ProcessBuilder(Ffmpeg.command(args)).redirectError(File(root, "${output.nameWithoutExtension}.log")).start()
            job.process = process
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    // -progress: "out_time_us=123456789" every half second
                    if (line.startsWith("out_time_us=") && source.durationMs > 0) {
                        line.substringAfter('=').toLongOrNull()?.let { job.progress = (it / 1000f / source.durationMs).coerceIn(0f, 0.99f) }
                    }
                }
            }
            val ok = process.waitFor() == 0 && part.isFile && part.length() > 0
            job.process = null
            if (Thread.currentThread().isInterrupted) return
            if (ok) {
                output.delete()
                if (part.renameTo(output)) {
                    job.progress = 1f
                    return
                }
            }
        }
        part.delete()
        job.failure = "Le PC n'a pas pu convertir ce fichier."
    }

    private fun output(key: Key) = File(root, "${key.fileId}-${key.height}p.mkv")

    /** Copies nobody asked for in [KEEP_MS] go, then the oldest ones while the folder is over [MAX_BYTES]. */
    private fun cleanUp() {
        val now = System.currentTimeMillis()
        val files = root.listFiles { f -> f.extension == "mkv" }?.toMutableList() ?: return
        files.filter { now - it.lastModified() > KEEP_MS }.forEach { it.delete(); files.remove(it) }
        var total = files.sumOf { it.length() }
        files.sortedBy { it.lastModified() }.forEach {
            if (total <= MAX_BYTES) return
            total -= it.length()
            it.delete()
        }
        root.listFiles { f -> f.extension == "log" && now - f.lastModified() > KEEP_MS }?.forEach { it.delete() }
    }

    private companion object {
        const val KEEP_MS = 3L * 24 * 3600 * 1000
        const val MAX_BYTES = 40L * 1024 * 1024 * 1024
    }
}
