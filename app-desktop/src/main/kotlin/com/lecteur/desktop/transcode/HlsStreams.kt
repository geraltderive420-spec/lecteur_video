package com.lecteur.desktop.transcode

import com.lecteur.core.common.library.LibraryUrls
import com.lecteur.core.common.library.ServedFile
import com.lecteur.core.model.HdrType
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/** What a stream is made from: the file on disk and what the library knows of it. */
data class TranscodeSource(val file: File, val durationMs: Long, val hdr: HdrType, val audio: Int?)

/**
 * Files brought down to a smaller height while a device watches them, as HLS: the playlist lists every segment of the
 * film from the start (so the player can seek anywhere), and ffmpeg encodes them in order from where the player is.
 * A request for a segment far from where ffmpeg stands (a seek) restarts it there.
 *
 * One stream per device and file; streams nobody asks anything of for [IDLE_MS] stop and their segments are deleted.
 */
class HlsStreams(private val root: File, private val encoder: () -> VideoEncoder?) {
    private data class Key(val device: String, val fileId: Long, val height: Int)

    private val sessions = ConcurrentHashMap<Key, Session>()
    private val reaper = Executors.newSingleThreadScheduledExecutor { Thread(it, "hls-reaper").apply { isDaemon = true } }

    init {
        root.deleteRecursively()
        root.mkdirs()
        reaper.scheduleWithFixedDelay(::reap, 15, 15, TimeUnit.SECONDS)
    }

    fun playlist(source: TranscodeSource): ServedFile {
        val count = segmentCount(source.durationMs)
        val text = buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n")
            append("#EXT-X-TARGETDURATION:").append(TranscodeArgs.SEGMENT_SECONDS + 1).append('\n')
            append("#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-INDEPENDENT-SEGMENTS\n")
            for (i in 0 until count) {
                val length = if (i < count - 1) TranscodeArgs.SEGMENT_SECONDS.toDouble()
                else source.durationMs / 1000.0 - i * TranscodeArgs.SEGMENT_SECONDS
                append("#EXTINF:").append("%.3f".format(java.util.Locale.ROOT, length.coerceAtLeast(0.1))).append(",\n")
                append("seg").append(i).append(".ts\n")
            }
            append("#EXT-X-ENDLIST\n")
        }.toByteArray()
        return ServedFile(LibraryUrls.HLS_PLAYLIST, "application/vnd.apple.mpegurl", text.size.toLong()) { offset -> text.inputStream(offset.toInt(), text.size - offset.toInt()) }
    }

    /** Segment [index], once encoded (waiting for it up to [WAIT_MS]); null when it cannot be made. */
    fun segment(device: String, fileId: Long, height: Int, source: TranscodeSource, index: Int): ServedFile? {
        if (index < 0 || index >= segmentCount(source.durationMs)) return null
        val key = Key(device, fileId, height)
        val session = sessions.computeIfAbsent(key) {
            evictBeyondLimit()
            Session(File(root, "${device.take(8)}-$fileId-$height"), source, height)
        }
        return session.segment(index, encoder() ?: return null)
    }

    fun stopAll() {
        sessions.values.forEach { it.stop(delete = true) }
        sessions.clear()
    }

    private fun segmentCount(durationMs: Long): Int = ceil(durationMs / 1000.0 / TranscodeArgs.SEGMENT_SECONDS).toInt().coerceAtLeast(1)

    private fun reap() {
        val now = System.currentTimeMillis()
        sessions.entries.filter { now - it.value.lastAccess > IDLE_MS }.forEach { (key, session) ->
            sessions.remove(key, session)
            session.stop(delete = true)
        }
    }

    private fun evictBeyondLimit() {
        while (sessions.size >= MAX_SESSIONS) {
            val oldest = sessions.entries.minByOrNull { it.value.lastAccess } ?: return
            sessions.remove(oldest.key)
            oldest.value.stop(delete = true)
        }
    }

    private class Session(val dir: File, val source: TranscodeSource, val height: Int) {
        @Volatile var lastAccess = System.currentTimeMillis()
        private var process: Process? = null
        private var startSegment = 0
        private var hwDecode = true
        /** Segments ffmpeg finished in this run (its own playlist lists a segment once it is closed). */
        private val done = HashSet<Int>()

        fun segment(index: Int, encoder: VideoEncoder): ServedFile? {
            lastAccess = System.currentTimeMillis()
            synchronized(this) {
                refresh()
                if (index !in done) {
                    val p = process
                    val working = p != null && (p.isAlive || p.exitValue() == 0)
                    val last = done.maxOrNull() ?: (startSegment - 1)
                    // The next segments come by themselves; anything else is a seek
                    if (!working || index < startSegment || index > last + LOOKAHEAD) start(index, encoder)
                }
            }
            val deadline = System.currentTimeMillis() + WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                synchronized(this) {
                    refresh()
                    if (index in done) return served(index)
                    val p = process ?: return null
                    if (!p.isAlive) {
                        when {
                            // Finished: a segment the playlist counts but the film does not have (a duration rounded up)
                            p.exitValue() == 0 && index >= startSegment -> return empty(index)
                            // Hardware decoding refused (a codec the chip does not decode, a driver issue): once more on the processor
                            hwDecode && done.isEmpty() -> {
                                hwDecode = false
                                start(index, encoder)
                            }
                            else -> return null
                        }
                    }
                }
                Thread.sleep(POLL_MS)
                lastAccess = System.currentTimeMillis()
            }
            return null
        }

        private fun start(index: Int, encoder: VideoEncoder) {
            stop(delete = false)
            dir.listFiles()?.forEach { it.delete() }
            dir.mkdirs()
            done.clear()
            startSegment = index
            val args = TranscodeArgs.hls(source.file, dir, index, height, source.hdr, source.audio, encoder, hwDecode)
            process = ProcessBuilder(Ffmpeg.command(args))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(File(dir, "ffmpeg.log"))
                .start()
        }

        private fun refresh() {
            val playlist = File(dir, "out.m3u8")
            if (!playlist.isFile) return
            // ffmpeg rewrites it as it goes: a read that catches it half-written is simply tried again later
            runCatching { playlist.readLines() }.getOrNull()?.forEach { line ->
                if (line.startsWith("seg") && line.endsWith(".ts")) line.removePrefix("seg").removeSuffix(".ts").toIntOrNull()?.let(done::add)
            }
        }

        private fun served(index: Int): ServedFile? {
            val file = File(dir, "seg$index.ts").takeIf { it.isFile } ?: return null
            return ServedFile(file.name, "video/mp2t", file.length()) { offset -> FileInputStream(file).also { it.channel.position(offset) } }
        }

        private fun empty(index: Int) = ServedFile("seg$index.ts", "video/mp2t", 0) { java.io.ByteArrayInputStream(ByteArray(0)) }

        fun stop(delete: Boolean) {
            synchronized(this) {
                process?.let { p ->
                    p.destroy()
                    if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
                }
                process = null
                if (delete) dir.deleteRecursively()
            }
        }
    }

    private companion object {
        /** Players buffer about a minute ahead and then ask nothing while paused: a long pause restarts ffmpeg, that is all. */
        const val IDLE_MS = 3 * 60_000L
        const val WAIT_MS = 25_000L
        const val POLL_MS = 100L
        /** A segment this far past the last one encoded is a seek, not the next one: ffmpeg restarts there. */
        const val LOOKAHEAD = 4
        const val MAX_SESSIONS = 3
    }
}
