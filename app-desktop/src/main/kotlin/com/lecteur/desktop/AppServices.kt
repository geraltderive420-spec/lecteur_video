package com.lecteur.desktop

import com.lecteur.desktop.cast.Mdns
import com.lecteur.desktop.cast.ReceiverService
import com.lecteur.desktop.cast.RemoteSession
import com.lecteur.desktop.cast.StreamHost
import com.lecteur.desktop.data.AppDirs
import com.lecteur.desktop.data.Db
import com.lecteur.desktop.data.Identifier
import com.lecteur.desktop.data.ImageCache
import com.lecteur.desktop.data.LibraryStore
import com.lecteur.desktop.data.Scanner
import com.lecteur.desktop.data.SettingsStore
import com.lecteur.desktop.data.TmdbProvider
import com.lecteur.desktop.player.PlaybackController
import com.lecteur.desktop.player.Vlc
import com.lecteur.desktop.player.VlcEngine
import com.lecteur.desktop.share.LibraryShareService
import com.lecteur.desktop.transcode.Transcoder

/** The app's object graph, built once at start (what Hilt does on Android). */
class AppServices {
    val settings = SettingsStore()
    val db = Db(AppDirs.database)
    val store = LibraryStore(db)
    val images = ImageCache()
    val tmdb = TmdbProvider(settings)
    val identifier = Identifier(store, tmdb, images)
    val scanner = Scanner(store, identifier)

    init {
        // libVLC options are read once: passthrough and the subtitle look apply from the next start
        val s = settings.value
        Vlc.subtitleOptions = listOfNotNull(
            "--freetype-rel-fontsize=${s.subtitleSize.vlcRelative}",
            "--freetype-color=${s.subtitleColor.rgb}",
            "--freetype-outline-thickness=4",
            if (s.subtitleBackground) "--freetype-background-opacity=160" else null,
            if (s.subtitleBackground) "--freetype-background-color=0" else null,
            if (s.subtitleMargin > 0) "--sub-margin=${s.subtitleMargin}" else null
        )
        Vlc.factory(passthrough = s.passthrough)
    }

    val engine = VlcEngine()
    val playback = PlaybackController(engine, store, settings)
    val mdns = Mdns()
    val streams = StreamHost()
    val receiver = ReceiverService(playback, settings, mdns)
    val remote = RemoteSession(store, settings, streams)
    val transcoder = Transcoder(store, settings)
    val share = LibraryShareService(store, settings, mdns, images, transcoder)

    fun shutdown() {
        runCatching { playback.close() }
        runCatching { remote.disconnect() }
        runCatching { receiver.stop() }
        runCatching { share.stop() }
        runCatching { transcoder.shutdown() }
        runCatching { mdns.close() }
        runCatching { streams.shutdown() }
        runCatching { engine.release() }
        runCatching { Vlc.release() }
    }
}
