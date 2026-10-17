package com.lecteur.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import androidx.compose.ui.window.application
import com.lecteur.core.common.cast.protocol.ControllerListener
import com.lecteur.core.common.cast.protocol.Open
import com.lecteur.core.common.cast.protocol.Pause
import com.lecteur.core.common.cast.protocol.RemoteControllerClient
import com.lecteur.core.common.cast.protocol.RemoteMessage
import com.lecteur.core.common.cast.protocol.RemoteProgress
import com.lecteur.core.common.cast.protocol.RemoteState
import com.lecteur.core.common.cast.protocol.SeekTo
import com.lecteur.core.common.cast.protocol.SelectAudio
import com.lecteur.core.common.cast.protocol.Stop
import com.lecteur.core.model.LibrarySection
import com.lecteur.core.model.MediaCategory
import com.lecteur.desktop.cast.StreamHost
import com.lecteur.desktop.ui.AppController
import com.lecteur.desktop.ui.LecteurApp
import com.lecteur.desktop.ui.LecteurTheme
import com.lecteur.desktop.ui.LocalApp
import com.lecteur.desktop.ui.Screen
import com.lecteur.desktop.ui.CastScreen
import com.lecteur.desktop.ui.FoldersScreen
import com.lecteur.desktop.ui.HomeScreen
import com.lecteur.desktop.ui.LibraryScreen
import com.lecteur.desktop.ui.ListsScreen
import com.lecteur.desktop.ui.MovieDetailScreen
import com.lecteur.desktop.ui.SearchScreen
import com.lecteur.desktop.ui.SeriesDetailScreen
import com.lecteur.desktop.ui.SettingsScreen
import com.lecteur.desktop.ui.player.Hud
import com.lecteur.desktop.ui.player.PlayerWindowState
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.system.exitProcess

private lateinit var out: File
private val report = StringBuilder()

private fun log(line: String) {
    println(line)
    report.appendLine(line)
    File(out, "report.txt").writeText(report.toString())
}

private fun waitUntil(timeoutMs: Long, step: Long = 200, condition: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (condition()) return true
        Thread.sleep(step)
    }
    return condition()
}

fun main(args: Array<String>) {
    val root = File(args.firstOrNull() ?: error("library folder expected"))
    if (System.getenv("SELFTEST_SCAN_ONLY") == "1") {
        // Rescans an existing library (LECTEUR_DATA_DIR) and lists what is where: checks the scan on a real copy
        out = File(root, "out").apply { mkdirs() }
        val services = AppServices()
        services.scanner.start()
        waitUntil(5_000) { services.scanner.status.value.running }
        waitUntil(900_000, 500) { !services.scanner.status.value.running }
        services.store.refreshNow()
        val snap = services.store.snapshot.value
        log("SCAN ${services.scanner.status.value.lastMessage}")
        snap.movies.values.sortedBy { it.movie.title }.forEach { log("FILM ${it.movie.title} (${it.movie.year}) ${it.movie.matchState} files=${it.files.size}") }
        snap.series.values.sortedBy { it.series.title }.forEach { s -> log("SERIE ${s.series.title} ${s.series.matchState} ${s.owned.map { "S${it.episode.seasonNumber}E${it.episode.episodeNumber}" }}") }
        val dupes = services.db.read { query("SELECT path, COUNT(*) n FROM media_files GROUP BY lower(path) HAVING n > 1") { it.str("path") } }
        log("DUPLICATE ROWS $dupes")
        exitProcess(0)
    }
    out = File(root, "out").apply { deleteRecursively(); mkdirs() }
    File(root, "data").deleteRecursively()
    try {
        run(root)
        log("SELFTEST DONE")
    } catch (e: Throwable) {
        log("SELFTEST CRASH: ${e.stackTraceToString()}")
    }
    exitProcess(0)
}

private fun run(root: File) {
    val services = AppServices()
    val app = AppController(services)
    val store = services.store

    // 1. Library scan + identification
    store.addFolder(File(root, "lib/Films").absolutePath, MediaCategory.MOVIES)
    store.addFolder(File(root, "lib/Series").absolutePath, MediaCategory.SERIES)
    val t0 = System.currentTimeMillis()
    services.scanner.start()
    waitUntil(5_000) { services.scanner.status.value.running }
    waitUntil(180_000, 500) { !services.scanner.status.value.running }
    store.refreshNow()
    log("SCAN ${System.currentTimeMillis() - t0} ms: ${services.scanner.status.value.lastMessage}")
    val snap = store.snapshot.value
    snap.movies.values.forEach { m ->
        val f = m.files.first().file
        val audio = store.audioTracks(f.id)
        val subs = store.subtitleTracks(f.id)
        log("MOVIE ${m.movie.title} (${m.movie.year}) state=${m.movie.matchState} tmdb=${m.movie.tmdbId} poster=${m.movie.posterPath} | ${f.width}x${f.height} ${f.videoCodec} ${f.durationMs}ms audio=${audio.map { "${it.language}/${it.codec}/${it.channels}" }} subs=${subs.map { "${it.language}/${it.codec}/${it.externalPath != null}" }}")
    }
    snap.series.values.forEach { s ->
        log("SERIES ${s.series.title} state=${s.series.matchState} tmdb=${s.series.tmdbId} episodes=${s.owned.map { "S${it.episode.seasonNumber}E${it.episode.episodeNumber} ${it.episode.title}" }} total=${s.episodes.size}")
    }

    // 2. Every screen rendered off-screen
    fun shot(name: String, w: Int = 1600, h: Int = 1000, content: @Composable () -> Unit) {
        try {
            ImageComposeScene(w, h, Density(1f)) {
                CompositionLocalProvider(LocalApp provides app) { LecteurTheme(services.settings.value.themeMode) { androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) { content() } } }
            }.use { scene ->
                var time = 0L
                repeat(8) {
                    scene.render(time)
                    time += 300_000_000L
                    Thread.sleep(400)
                }
                val image = scene.render(time)
                File(out, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
            log("SCREEN $name ok")
        } catch (e: Throwable) {
            log("SCREEN $name FAILED: ${e.stackTraceToString().take(1500)}")
        }
    }
    val movieId = snap.movies.keys.first()
    val seriesId = snap.series.keys.firstOrNull()
    shot("home") { HomeScreen() }
    shot("movies") { LibraryScreen(Screen.Library(LibrarySection.MOVIES)) }
    shot("series") { LibraryScreen(Screen.Library(LibrarySection.SERIES)) }
    shot("movie_detail") { MovieDetailScreen(movieId) }
    if (seriesId != null) shot("series_detail") { SeriesDetailScreen(seriesId) }
    shot("search") { SearchScreen() }
    shot("lists") { ListsScreen() }
    shot("folders") { FoldersScreen() }
    shot("cast") { CastScreen() }
    shot("settings") { SettingsScreen() }

    // 3. Real playback in the real windows
    Thread {
        application(exitProcessOnExit = false) { LecteurApp(app, null) }
    }.apply { isDaemon = true; start() }
    Thread.sleep(3_000)
    val engine = services.engine
    val movieFile = snap.movies.values.first { it.files.first().file.width ?: 0 >= 1920 }.files.first().file
    services.playback.play(movieFile.id, fromStart = true)
    val playing = waitUntil(15_000) { engine.state.value.isPlaying && engine.state.value.positionMs > 500 }
    log("PLAY local ${movieFile.fileName}: playing=$playing state=${engine.state.value.copy(audio = emptyList(), subtitles = emptyList())}")
    log("TRACKS audio=${engine.state.value.audio.map { "${it.index}:${it.language}:${it.label}:${it.isSelected}" }} subs=${engine.state.value.subtitles.map { "${it.index}:${it.language}:${it.label}:${it.isSelected}" }}")
    Thread.sleep(1_500)
    log("SNAPSHOT ${engine.snapshot(File(out, "frame1.png"))}")
    engine.seekTo(20_000)
    waitUntil(5_000) { engine.state.value.positionMs in 19_000..25_000 }
    log("SEEK -> ${engine.state.value.positionMs}")
    services.playback.selectAudio(1)
    Thread.sleep(800)
    services.playback.selectSubtitle(0)
    Thread.sleep(1_500)
    log("AFTER SELECT audio=${engine.state.value.selectedAudioIndex} sub=${engine.state.value.selectedSubtitleIndex}")
    log("SNAPSHOT2 ${engine.snapshot(File(out, "frame2.png"))}")
    // HUD rendered over a fake background
    shot("hud", 1280, 720) { Hud(PlayerWindowState()) }
    // Fullscreen and back: the video window and its controls overlay must follow
    app.player.fullscreen = true
    Thread.sleep(2_000)
    val screen = java.awt.Toolkit.getDefaultToolkit().screenSize
    log("FULLSCREEN overlay=${app.player.overlayBounds} screen=${screen.width}x${screen.height} canvas=${engine.canvas.width}x${engine.canvas.height} playing=${engine.state.value.isPlaying}")
    app.player.fullscreen = false
    Thread.sleep(1_500)
    log("WINDOWED overlay=${app.player.overlayBounds} canvas=${engine.canvas.width}x${engine.canvas.height}")
    app.player.maximized = true
    Thread.sleep(1_500)
    log("MAXIMIZED overlay=${app.player.overlayBounds} canvas=${engine.canvas.width}x${engine.canvas.height}")
    app.player.maximized = false
    Thread.sleep(1_000)
    app.player.resizeBy(-100, -50, false, false, true, true)
    Thread.sleep(800)
    log("RESIZED overlay=${app.player.overlayBounds}")
    app.player.mini = true
    Thread.sleep(1_500)
    log("MINI overlay=${app.player.overlayBounds} canvas=${engine.canvas.width}x${engine.canvas.height}")
    app.player.mini = false
    Thread.sleep(800)
    services.playback.close()
    Thread.sleep(1_000)
    log("WATCH after close: ${store.watchState(movieFile.id)}")

    // "Reprendre" from a detail page with no player open: resumes straight at the saved position
    val saved = store.watchState(movieFile.id)?.positionMs ?: 0
    services.playback.play(movieFile.id, fromStart = false)
    waitUntil(10_000) { engine.state.value.isPlaying && engine.state.value.positionMs > 0 }
    log("RESUME direct saved=$saved started=${engine.state.value.positionMs}")
    engine.togglePause()
    Thread.sleep(800)
    val pausedOk = !engine.state.value.isPlaying
    engine.togglePause()
    Thread.sleep(800)
    log("TOGGLE paused=$pausedOk resumed=${engine.state.value.isPlaying}")
    services.playback.close()
    Thread.sleep(800)
    // Asked: the question waits with no player window, the answer opens the player at the right place
    store.saveWatch(com.lecteur.core.model.WatchState(mediaFileId = movieFile.id, positionMs = 15_000, durationMs = 40_023))
    services.playback.play(movieFile.id)
    waitUntil(5_000) { services.playback.ui.value.resumeQuestion != null }
    log("RESUME question=${services.playback.ui.value.resumeQuestion} active=${services.playback.ui.value.active}")
    services.playback.answerResume(true)
    waitUntil(10_000) { engine.state.value.isPlaying && engine.state.value.positionMs > 0 }
    log("RESUME answered started=${engine.state.value.positionMs}")
    services.playback.close()
    Thread.sleep(800)

    // 4K HEVC
    snap.movies.values.firstOrNull { (it.files.first().file.width ?: 0) >= 3000 }?.let { m ->
        services.playback.play(m.files.first().file.id, fromStart = true)
        val ok = waitUntil(15_000) { engine.state.value.isPlaying && engine.state.value.positionMs > 1_000 }
        Thread.sleep(1_000)
        log("PLAY 4K ok=$ok pos=${engine.state.value.positionMs} video=${engine.state.value.videoWidth}x${engine.state.value.videoHeight} snapshot=${engine.snapshot(File(out, "frame4k.png"))}")
        services.playback.close()
        Thread.sleep(800)
    }

    // Series queue: episode 1 then "next"
    seriesId?.let { sid ->
        val s = store.snapshot.value.series[sid]!!
        app.playEpisode(sid, s.owned.first().episode.id, fromStart = true)
        waitUntil(10_000) { engine.state.value.isPlaying }
        log("SERIES session=${services.playback.ui.value.title} / ${services.playback.ui.value.subtitle} queue=${services.playback.ui.value.queue.size} hasNext=${services.playback.ui.value.hasNext}")
        services.playback.next()
        Thread.sleep(3_000)
        log("NEXT -> ${services.playback.ui.value.subtitle} playing=${engine.state.value.isPlaying}")
        services.playback.close()
        Thread.sleep(800)
    }

    // 4. Companion mode: this process plays the phone. A stream served over HTTP like the phone does, then commands.
    services.receiver.start()
    val receiver = services.receiver.ui.value
    log("RECEIVER running=${receiver.running} name=${receiver.deviceName} code=${receiver.pairingCode} port=${receiver.port} error=${receiver.error}")
    if (!receiver.running) services.receiver.start()
    val r = services.receiver.ui.value
    val events = CopyOnWriteArrayList<RemoteMessage>()
    val client = RemoteControllerClient("Téléphone de test", object : ControllerListener {
        override fun onEvent(event: RemoteMessage) {
            events += event
        }

        override fun onDisconnected(cause: IOException?) {
            log("CLIENT disconnected cause=$cause")
        }
    })
    val bad = RemoteControllerClient("Intrus", object : ControllerListener {
        override fun onEvent(event: RemoteMessage) = Unit
        override fun onDisconnected(cause: IOException?) = Unit
    }).connect("127.0.0.1", r.port, "000000")
    log("PAIRING wrong code accepted=${bad.accepted} refusal=${bad.refusal}")
    val welcome = client.connect("127.0.0.1", r.port, r.pairingCode)
    log("PAIRING good code accepted=${welcome.accepted} name=${welcome.deviceName}")
    val phoneServer = StreamHost()
    val epFile = store.snapshot.value.series.values.firstOrNull()?.owned?.first()?.files?.first()?.file ?: movieFile
    val shared = phoneServer.share(File(epFile.uri), StreamHost.mimeFor(epFile.fileName), "127.0.0.1")
    log("PHONE stream ${shared.url}")
    client.send(Open(streamUrl = shared.url, title = "Depuis le téléphone", mediaFileId = 4242, startPositionMs = 5_000))
    val remotePlaying = waitUntil(15_000) { events.any { it is RemoteProgress } }
    // Flicker hunt: every change of the playing state and every overlay show/reshape over 15 s of streaming
    run {
        val shows0 = app.player.overlayShows
        val reshapes0 = app.player.overlayReshapes
        val transitions = ArrayList<String>()
        var last = ""
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end) {
            val st = engine.state.value
            val key = "${st.status}/${st.isPlaying}"
            if (key != last) { transitions += "${st.positionMs}:$key"; last = key }
            Thread.sleep(50)
        }
        log("STREAM transitions=$transitions overlayShows=${app.player.overlayShows - shows0} overlayReshapes=${app.player.overlayReshapes - reshapes0}")
    }
    log("REMOTE playing=$remotePlaying owner=${services.playback.ui.value.owner} controller=${services.playback.ui.value.remoteController} pos=${engine.state.value.positionMs}")
    val state = events.filterIsInstance<RemoteState>().lastOrNull()
    log("REMOTE last state status=${state?.status} title=${state?.title} audio=${state?.audio?.size} subs=${state?.subtitles?.size} dur=${state?.durationMs}")
    client.send(SeekTo(15_000))
    Thread.sleep(2_000)
    log("REMOTE seek pos=${engine.state.value.positionMs}")
    client.send(SelectAudio(1))
    Thread.sleep(800)
    client.send(Pause)
    Thread.sleep(1_000)
    log("REMOTE after pause playing=${engine.state.value.isPlaying} audio=${engine.state.value.selectedAudioIndex}")
    log("SNAPSHOT remote ${engine.snapshot(File(out, "frame_remote.png"))}")
    client.send(Stop)
    Thread.sleep(1_000)
    log("REMOTE after stop active=${services.playback.ui.value.active} events=${events.groupBy { it::class.simpleName }.mapValues { it.value.size }}")
    client.close()
    phoneServer.shutdown()

    // 5. mDNS: the advert, seen by an independent resolver as the phone's NsdManager would see it
    for (address in com.lecteur.desktop.cast.LocalAddress.candidates()) {
        val probe = javax.jmdns.JmDNS.create(address, "probe")
        val found = probe.list("_lecteurmedia._tcp.local.", 6_000)
        log("MDNS via ${address.hostAddress}: ${found.map { "${it.name} port=${it.port} v=${it.getPropertyString("v")} name=${it.getPropertyString("name")} ip=${it.inet4Addresses.map { a -> a.hostAddress }}" }}")
        probe.close()
    }
    services.shutdown()
}
