package com.lecteur.core.common.library

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class LibrarySecurityTest {

    @Test fun `pairing code works once`() {
        val window = PairingWindow()
        val code = window.open()
        assertThat(window.attempt(code)).isEqualTo(PairingWindow.Outcome.Accepted)
        assertThat(window.attempt(code)).isEqualTo(PairingWindow.Outcome.Closed)
    }

    @Test fun `no pairing while the window is closed`() {
        assertThat(PairingWindow().attempt("123456")).isEqualTo(PairingWindow.Outcome.Closed)
    }

    @Test fun `pairing code expires`() {
        var now = 0L
        val window = PairingWindow(ttlMs = 1_000, clock = { now })
        val code = window.open()
        now = 1_001
        assertThat(window.attempt(code)).isEqualTo(PairingWindow.Outcome.Closed)
    }

    @Test fun `too many wrong codes close the window`() {
        val window = PairingWindow(maxFailures = 3)
        val code = window.open()
        val wrong = if (code == "000000") "111111" else "000000"
        repeat(3) { assertThat(window.attempt(wrong)).isEqualTo(PairingWindow.Outcome.WrongCode) }
        assertThat(window.attempt(code)).isEqualTo(PairingWindow.Outcome.Closed)
    }

    @Test fun `failing address is turned away then forgiven`() {
        var now = 0L
        val limiter = FailureLimiter(maxFailures = 3, windowMs = 1_000, banMs = 5_000, clock = { now })
        repeat(3) { limiter.failed("10.0.0.2") }
        assertThat(limiter.isBlocked("10.0.0.2")).isTrue()
        assertThat(limiter.isBlocked("10.0.0.3")).isFalse()
        now = 5_001
        assertThat(limiter.isBlocked("10.0.0.2")).isFalse()
    }

    @Test fun `replayed signature is refused`() {
        val guard = ReplayGuard()
        assertThat(guard.firstUse("abc")).isTrue()
        assertThat(guard.firstUse("abc")).isFalse()
        assertThat(guard.firstUse("abd")).isTrue()
    }

    @Test fun `only the local network is allowed`() {
        fun allows(ip: String) = LanOnly.allows(InetAddress.getByName(ip))
        listOf("192.168.1.20", "10.0.0.5", "172.16.3.4", "127.0.0.1", "169.254.1.1", "fd12::1", "fe80::1", "::ffff:192.168.1.2").forEach {
            assertThat(allows(it)).isTrue()
        }
        listOf("8.8.8.8", "100.64.0.1", "2001:4860::1", "::ffff:8.8.8.8").forEach { assertThat(allows(it)).isFalse() }
    }

    @Test fun `resource signature depends on the secret and the resource`() {
        val a = LibrarySignature.resource("s1", "stream/1")
        assertThat(a).hasLength(32)
        assertThat(LibrarySignature.resource("s2", "stream/1")).isNotEqualTo(a)
        assertThat(LibrarySignature.resource("s1", "stream/2")).isNotEqualTo(a)
    }

    @Test fun `stream url round trip`() {
        val creds = DeviceCredentials(LibrarySignature.newId(), LibrarySignature.newSecret())
        val url = LibraryUrls.stream("http://192.168.1.10:47810/", creds, 42, "Alien (1979) 1080p.mkv")
        assertThat(url).endsWith("/stream/42/Alien%20%281979%29%201080p.mkv")
        assertThat(LibraryUrls.parseStream(url)).isEqualTo(LibraryUrls.StreamRef("http://192.168.1.10:47810", creds.deviceId, 42))
        assertThat(LibraryUrls.parseStream("content://media/external/video/42")).isNull()
        assertThat(LibraryUrls.parseStream("http://192.168.1.10:8080/stream/abc/file.mkv")).isNull()
    }

    @Test fun `transcoded urls are recognised as the same file`() {
        val creds = DeviceCredentials(LibrarySignature.newId(), LibrarySignature.newSecret())
        val url = LibraryUrls.hls("http://192.168.1.10:47810", creds, 42, 720)
        assertThat(url).endsWith("/hls/42-720/index.m3u8")
        assertThat(LibraryUrls.parseStream(url)).isEqualTo(LibraryUrls.StreamRef("http://192.168.1.10:47810", creds.deviceId, 42, 720))
        // A segment shares the playlist's signed prefix
        val segment = url.replace("index.m3u8", "seg12.ts")
        assertThat(LibraryUrls.parse(java.net.URI(segment).rawPath)?.name).isEqualTo("seg12.ts")
        assertThat(LibraryUrls.splitTranscoded("42-0")).isNull()
        assertThat(LibraryUrls.splitTranscoded("42")).isNull()
    }

    @Test fun `advert round trip`() {
        val advert = LibraryAdvert("PC Salon", "abc123", 47810)
        assertThat(LibraryAdvert.parse(advert.serviceName(), 47810, advert.toTxt())).isEqualTo(advert)
        assertThat(LibraryAdvert.parse("x", 47810, mapOf("v" to "1"))).isNull()
    }
}

class LibraryServerTest {
    private val movieBytes = ByteArray(10_000) { (it % 251).toByte() }
    private val devices = object : DeviceDirectory {
        val map = ConcurrentHashMap<String, PairedDevice>()
        override fun find(deviceId: String) = map[deviceId]
        override fun add(device: PairedDevice) { map[device.id] = device }
    }
    private val reports = mutableListOf<WatchReport>()
    private val backend = object : LibraryBackend {
        override val serverId = "server-1"
        override val serverName = "PC Test"
        override fun catalogJson() = LibraryServer.encodeCatalog(
            Catalog(
                serverId, serverName, 1,
                movies = listOf(MovieDto(7, "Alien", 1979, poster = "p1", files = listOf(FileDto(70, "alien.mkv", movieBytes.size.toLong(), subtitles = listOf(SubtitleDto(0, "fr", mimeType = "application/x-subrip")))))),
                series = List(200) { SeriesDto(100L + it, "Série $it", overview = "x".repeat(200)) }
            )
        )
        override fun watch(fileId: Long) = if (fileId == 70L) WatchDto(positionMs = 1234, durationMs = 5000) else null
        override fun saveWatch(device: PairedDevice, report: WatchReport): Boolean = synchronized(reports) { reports += report; report.fileId == 70L }
        override fun stream(fileId: Long) = if (fileId == 70L) ServedFile("alien.mkv", "video/x-matroska", movieBytes.size.toLong()) { off ->
            ByteArrayInputStream(movieBytes, off.toInt(), movieBytes.size - off.toInt())
        } else null
        override fun subtitle(fileId: Long, index: Int) = if (fileId == 70L && index == 0) ServedFile("a.srt", "application/x-subrip", 3) { ByteArrayInputStream("abc".toByteArray()) } else null
        override fun art(key: String) = if (key == "p1") ServedFile("p.jpg", "image/jpeg", 2) { ByteArrayInputStream(byteArrayOf(1, 2)) } else null
        override fun hls(device: PairedDevice, fileId: Long, height: Int, name: String) =
            if (fileId == 70L) ServedFile(name, "application/vnd.apple.mpegurl", name.length.toLong()) { ByteArrayInputStream(name.toByteArray()) } else null
        var downloadState: Transcoded? = Transcoded.Preparing(TranscodeProgressDto(0.5f))
        override fun download(fileId: Long, height: Int) = if (fileId == 70L) downloadState else null
    }
    private val pairing = PairingWindow()
    private lateinit var server: LibraryServer
    private lateinit var base: String

    @Before fun setUp() {
        server = LibraryServer(backend, devices, pairing, requestedPort = 0, bindAddress = InetAddress.getLoopbackAddress())
        base = "http://127.0.0.1:${server.start()}"
    }

    @After fun tearDown() = server.stop()

    private fun paired(): LibraryClient {
        val code = pairing.open()
        val response = LibraryClient(base).pair(code, "Téléphone", "phone")
        assertThat(response.serverId).isEqualTo("server-1")
        return LibraryClient(base, DeviceCredentials(response.deviceId, response.secret))
    }

    @Test fun `info is public`() {
        val info = LibraryClient(base).info()
        assertThat(info.name).isEqualTo("PC Test")
        assertThat(info.pairingOpen).isFalse()
    }

    @Test fun `pair then read the catalog and the watch state`() {
        val client = paired()
        val catalog = client.catalog()
        assertThat(catalog.movies.single().title).isEqualTo("Alien")
        assertThat(catalog.series).hasSize(200)
        assertThat(client.watch(70).positionMs).isEqualTo(1234)
        assertThat(devices.map.values.single().name).isEqualTo("Téléphone")
    }

    @Test fun `pairing needs the code shown on the PC`() {
        val closed = assertThrows(LibraryException::class.java) { LibraryClient(base).pair("123456", "x", "phone") }
        assertThat(closed.kind).isEqualTo(LibraryException.Kind.PAIRING_CLOSED)
        val code = pairing.open()
        val wrong = if (code == "000000") "111111" else "000000"
        val bad = assertThrows(LibraryException::class.java) { LibraryClient(base).pair(wrong, "x", "phone") }
        assertThat(bad.kind).isEqualTo(LibraryException.Kind.BAD_CODE)
        assertThat(devices.map).isEmpty()
    }

    @Test fun `catalog refuses unknown, forged and revoked devices`() {
        val unknown = LibraryClient(base, DeviceCredentials(LibrarySignature.newId(), LibrarySignature.newSecret()))
        assertThat(assertThrows(LibraryException::class.java) { unknown.catalog() }.kind).isEqualTo(LibraryException.Kind.UNAUTHORIZED)

        val client = paired()
        val id = devices.map.keys.single()
        val forged = LibraryClient(base, DeviceCredentials(id, LibrarySignature.newSecret()))
        assertThat(assertThrows(LibraryException::class.java) { forged.catalog() }.kind).isEqualTo(LibraryException.Kind.UNAUTHORIZED)

        devices.map.clear()
        assertThat(assertThrows(LibraryException::class.java) { client.catalog() }.kind).isEqualTo(LibraryException.Kind.UNAUTHORIZED)
    }

    @Test fun `watch reports reach the backend`() {
        val client = paired()
        client.report(WatchReport(70, 3000, 5000, completed = false, audioIndex = 1))
        assertThat(reports.single().positionMs).isEqualTo(3000)
    }

    @Test fun `a phone with a wrong clock still signs valid requests`() {
        val response = LibraryClient(base).pair(pairing.open(), "x", "phone")
        // 10 minutes late, beyond the accepted skew: the client learns the server's time before signing
        val late = LibraryClient(base, DeviceCredentials(response.deviceId, response.secret), clock = { System.currentTimeMillis() - 600_000 })
        assertThat(late.catalog().movies).hasSize(1)
    }

    @Test fun `signatures out of the time window are refused`() {
        val response = LibraryClient(base).pair(pairing.open(), "x", "phone")
        val connection = (URL("$base/api/v1/catalog").openConnection() as HttpURLConnection).apply {
            val time = System.currentTimeMillis() - 600_000
            setRequestProperty(LibrarySignature.HEADER_DEVICE, response.deviceId)
            setRequestProperty(LibrarySignature.HEADER_TIME, time.toString())
            setRequestProperty(LibrarySignature.HEADER_SIGNATURE, LibrarySignature.request(response.secret, "GET", "/api/v1/catalog", time))
        }
        assertThat(connection.responseCode).isEqualTo(401)
    }

    @Test fun `stream with ranges, only with a valid signature`() {
        val client = paired()
        val file = client.catalog().movies.single().files.single()
        val url = client.streamUrl(file)

        val full = URL(url).openConnection() as HttpURLConnection
        assertThat(full.responseCode).isEqualTo(200)
        assertThat(full.inputStream.readBytes()).isEqualTo(movieBytes)

        val partial = (URL(url).openConnection() as HttpURLConnection).apply { setRequestProperty("Range", "bytes=100-199") }
        assertThat(partial.responseCode).isEqualTo(206)
        assertThat(partial.getHeaderField("Content-Range")).isEqualTo("bytes 100-199/10000")
        assertThat(partial.inputStream.readBytes()).isEqualTo(movieBytes.copyOfRange(100, 200))

        // Another file id with the same signature
        val tampered = url.replace("/stream/70/", "/stream/71/")
        assertThat((URL(tampered).openConnection() as HttpURLConnection).responseCode).isEqualTo(403)

        assertThat((URL(client.subtitleUrl(file, file.subtitles.single())).openConnection() as HttpURLConnection).inputStream.readBytes().decodeToString()).isEqualTo("abc")
        assertThat((URL(client.artUrl("p1")!!).openConnection() as HttpURLConnection).responseCode).isEqualTo(200)

        devices.map.clear()
        assertThat((URL(url).openConnection() as HttpURLConnection).responseCode).isEqualTo(403)
    }

    @Test fun `transcoded stream and download`() {
        val client = paired()
        val file = client.catalog().movies.single().files.single()
        val playlist = client.hlsUrl(file, 720)
        assertThat((URL(playlist).openConnection() as HttpURLConnection).inputStream.readBytes().decodeToString()).isEqualTo("index.m3u8")
        assertThat((URL(playlist.replace("index.m3u8", "seg3.ts")).openConnection() as HttpURLConnection).inputStream.readBytes().decodeToString()).isEqualTo("seg3.ts")
        // The signature names the height too
        assertThat((URL(playlist.replace("/70-720/", "/70-1080/")).openConnection() as HttpURLConnection).responseCode).isEqualTo(403)

        val download = client.downloadUrl(file, 480, "alien [480p].mkv")
        val waiting = URL(download).openConnection() as HttpURLConnection
        assertThat(waiting.responseCode).isEqualTo(202)
        assertThat(LibraryServer.wire.decodeFromString(TranscodeProgressDto.serializer(), waiting.inputStream.readBytes().decodeToString()).progress).isEqualTo(0.5f)
        backend.downloadState = Transcoded.Ready(backend.stream(70)!!)
        val ready = (URL(download).openConnection() as HttpURLConnection).apply { setRequestProperty("Range", "bytes=10-") }
        assertThat(ready.responseCode).isEqualTo(206)
        assertThat(ready.inputStream.readBytes()).isEqualTo(movieBytes.copyOfRange(10, movieBytes.size))
    }

    @Test fun `addresses outside the local network get no answer`() {
        server.stop()
        val strict = LibraryServer(backend, devices, pairing, requestedPort = 0, bindAddress = InetAddress.getLoopbackAddress(), allowAddress = { false })
        val port = strict.start()
        try {
            assertThat(assertThrows(LibraryException::class.java) { LibraryClient("http://127.0.0.1:$port").info() }.kind)
                .isEqualTo(LibraryException.Kind.UNREACHABLE)
        } finally {
            strict.stop()
        }
    }
}
