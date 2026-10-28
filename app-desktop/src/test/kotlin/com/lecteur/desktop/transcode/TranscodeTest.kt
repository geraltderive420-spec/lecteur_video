package com.lecteur.desktop.transcode

import com.google.common.truth.Truth.assertThat
import com.lecteur.core.common.library.Catalog
import com.lecteur.core.common.library.DeviceCredentials
import com.lecteur.core.common.library.DeviceDirectory
import com.lecteur.core.common.library.FileDto
import com.lecteur.core.common.library.LibraryBackend
import com.lecteur.core.common.library.LibraryClient
import com.lecteur.core.common.library.LibraryServer
import com.lecteur.core.common.library.LibraryUrls
import com.lecteur.core.common.library.PairedDevice
import com.lecteur.core.common.library.PairingWindow
import com.lecteur.core.common.library.ServedFile
import com.lecteur.core.common.library.Transcoded
import com.lecteur.core.common.library.WatchDto
import com.lecteur.core.common.library.WatchReport
import com.lecteur.core.model.HdrType
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/** Real transcodes through the ffmpeg of the machine (skipped without one): HLS with a seek, then a download. */
class TranscodeTest {

    companion object {
        private lateinit var dir: File
        private lateinit var source: File
        private var encoder: VideoEncoder? = null

        @BeforeClass @JvmStatic fun makeSource() {
            assumeTrue("no ffmpeg", Ffmpeg.executable != null)
            dir = Files.createTempDirectory("transcode-test").toFile()
            source = File(dir, "source.mkv")
            val args = listOf(
                "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=s=1920x1080:r=24:d=30",
                "-f", "lavfi", "-i", "sine=f=440:d=30", "-f", "lavfi", "-i", "sine=f=220:d=30",
                "-map", "0", "-map", "1", "-map", "2", "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "ac3",
                "-metadata:s:a:1", "language=fre", source.absolutePath
            )
            assertThat(ProcessBuilder(Ffmpeg.command(args)).inheritIO().start().waitFor()).isEqualTo(0)
            encoder = Ffmpeg.detectEncoder()
            println("encoder: $encoder")
        }

        @AfterClass @JvmStatic fun cleanUp() {
            if (::dir.isInitialized) dir.deleteRecursively()
        }
    }

    private fun probeHeight(file: File): Int? {
        val process = ProcessBuilder(Ffmpeg.command(listOf("-hide_banner", "-i", file.absolutePath))).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return Regex("""Video: h264.*?, (\d+)x(\d+)""").find(text)?.groupValues?.get(2)?.toInt()
    }

    @Test fun `hls plays from the start and after a seek`() {
        val hls = HlsStreams(File(dir, "hls")) { encoder }
        try {
            val src = TranscodeSource(source, 30_000, HdrType.NONE, audio = 1)
            val playlist = hls.playlist(src).open(0).readBytes().decodeToString()
            assertThat(playlist.lines().count { it.startsWith("seg") }).isEqualTo(8)
            assertThat(playlist).contains("#EXT-X-ENDLIST")

            val started = System.currentTimeMillis()
            val first = hls.segment("dev", 1, 720, src, 0)!!
            println("first segment after ${System.currentTimeMillis() - started} ms")
            assertThat(first.sizeBytes).isGreaterThan(10_000)
            assertThat(hls.segment("dev", 1, 720, src, 1)!!.sizeBytes).isGreaterThan(10_000)

            // Far ahead: ffmpeg restarts at segment 6
            val seek = System.currentTimeMillis()
            val later = hls.segment("dev", 1, 720, src, 6)!!
            println("segment 6 after a seek: ${System.currentTimeMillis() - seek} ms")
            val copy = File(dir, "seg6.ts").also { f -> later.open(0).use { input -> f.outputStream().use { input.copyTo(it) } } }
            assertThat(probeHeight(copy)).isEqualTo(720)
            // The last one, shorter than the others
            assertThat(hls.segment("dev", 1, 720, src, 7)).isNotNull()
            assertThat(hls.segment("dev", 1, 720, src, 8)).isNull()
        } finally {
            hls.stopAll()
        }
    }

    @Test fun `a player reads and seeks the stream through the library server`() {
        val hls = HlsStreams(File(dir, "hls-http")) { encoder }
        val src = TranscodeSource(source, 30_000, HdrType.NONE, audio = null)
        val devices = object : DeviceDirectory {
            val map = ConcurrentHashMap<String, PairedDevice>()
            override fun find(deviceId: String) = map[deviceId]
            override fun add(device: PairedDevice) { map[device.id] = device }
        }
        val backend = object : LibraryBackend {
            override val serverId = "s"
            override val serverName = "PC"
            override fun catalogJson() = LibraryServer.encodeCatalog(Catalog("s", "PC", 0, transcodeHeights = listOf(720)))
            override fun watch(fileId: Long): WatchDto? = null
            override fun saveWatch(device: PairedDevice, report: WatchReport) = false
            override fun stream(fileId: Long): ServedFile? = null
            override fun subtitle(fileId: Long, index: Int): ServedFile? = null
            override fun art(key: String): ServedFile? = null
            override fun hls(device: PairedDevice, fileId: Long, height: Int, name: String): ServedFile? =
                if (name == LibraryUrls.HLS_PLAYLIST) hls.playlist(src)
                else hls.segment(device.id, fileId, height, src, name.removePrefix("seg").removeSuffix(".ts").toInt())
        }
        val pairing = PairingWindow()
        val server = LibraryServer(backend, devices, pairing, requestedPort = 0, bindAddress = InetAddress.getLoopbackAddress())
        val base = "http://127.0.0.1:${server.start()}"
        try {
            val paired = LibraryClient(base).pair(pairing.open(), "Téléphone", "phone")
            val url = LibraryClient(base, DeviceCredentials(paired.deviceId, paired.secret)).hlsUrl(FileDto(5, "film.mkv", 0), 720)
            fun play(vararg extra: String): Int = ProcessBuilder(Ffmpeg.command(listOf("-hide_banner", "-loglevel", "error") + extra.toList() + listOf("-i", url, "-t", "6", "-f", "null", "-")))
                .inheritIO().start().waitFor()
            assertThat(play()).isEqualTo(0)
            // A jump to 0:20 restarts the encoder there
            assertThat(play("-ss", "20")).isEqualTo(0)
        } finally {
            server.stop()
            hls.stopAll()
        }
    }

    @Test fun `download is made in the background then served`() {
        val downloads = TranscodedDownloads(File(dir, "downloads")) { encoder }
        try {
            val src = TranscodeSource(source, 30_000, HdrType.NONE, audio = null)
            assertThat(downloads.request(2, 480, src)).isInstanceOf(Transcoded.Preparing::class.java)
            var state: Transcoded = downloads.request(2, 480, src)
            val deadline = System.currentTimeMillis() + 120_000
            while (state is Transcoded.Preparing && System.currentTimeMillis() < deadline) {
                Thread.sleep(500)
                state = downloads.request(2, 480, src)
            }
            assertThat(state).isInstanceOf(Transcoded.Ready::class.java)
            val file = File(dir, "downloads/2-480p.mkv")
            assertThat(probeHeight(file)).isEqualTo(480)
            // Both audio tracks are kept
            val info = ProcessBuilder(Ffmpeg.command(listOf("-hide_banner", "-i", file.absolutePath))).redirectErrorStream(true).start()
                .inputStream.bufferedReader().readText()
            assertThat(Regex("Audio: aac").findAll(info).count()).isEqualTo(2)
        } finally {
            downloads.stopAll()
        }
    }
}
