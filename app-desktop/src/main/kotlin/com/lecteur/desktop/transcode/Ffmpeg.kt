package com.lecteur.desktop.transcode

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The ffmpeg program: shipped next to the app (like libVLC), else LECTEUR_FFMPEG, else one on the PATH (a build run
 * from the sources).
 */
object Ffmpeg {
    val executable: File? by lazy {
        val bundled = System.getProperty("compose.application.resources.dir")?.let { File(it, "ffmpeg/ffmpeg.exe") }
        val fromEnv = System.getenv("LECTEUR_FFMPEG")?.takeIf { it.isNotBlank() }?.let(::File)
        val onPath = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }.map { File(it, "ffmpeg.exe") }
        (listOfNotNull(bundled, fromEnv) + onPath).firstOrNull { it.isFile }
    }

    fun command(args: List<String>): List<String> = listOf(executable!!.absolutePath) + args

    /** The first encoder of [VideoEncoder] order this PC can run, or null without ffmpeg. A few seconds: call off the UI. */
    fun detectEncoder(): VideoEncoder? {
        if (executable == null) return null
        return VideoEncoder.entries.firstOrNull { runs(TranscodeArgs.probe(it)) }
    }

    /** Duration of [file] as ffmpeg reads it ("Duration: 01:52:03.44"), for files the library has none for. */
    fun durationMs(file: File): Long? {
        if (executable == null) return null
        val process = ProcessBuilder(command(listOf("-hide_banner", "-nostdin", "-i", file.absolutePath))).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(10, TimeUnit.SECONDS)
        val match = Regex("""Duration: (\d+):(\d{2}):(\d{2})\.(\d{2})""").find(text) ?: return null
        val (h, m, s, cs) = match.destructured
        return ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + cs.toLong() * 10
    }

    private fun runs(args: List<String>): Boolean = runCatching {
        val process = ProcessBuilder(command(args)).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            false
        } else process.exitValue() == 0
    }.getOrDefault(false)
}
