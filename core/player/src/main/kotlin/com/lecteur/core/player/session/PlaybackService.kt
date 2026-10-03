package com.lecteur.core.player.session

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * MediaSession for the shared engine: media notification, lock-screen controls, Bluetooth headset and
 * hardware media keys. Playing in the background (audio only) goes through the same service.
 */
@UnstableApi
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject
    lateinit var holder: PlayerHolder

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            holder.engine.collect { engine ->
                val current = session
                when {
                    engine == null -> {
                        current?.let { removeSession(it); it.release() }
                        session = null
                    }
                    current == null -> {
                        val builder = MediaSession.Builder(this@PlaybackService, engine.player)
                        holder.sessionActivity?.let(builder::setSessionActivity)
                        session = builder.build().also { addSession(it) }
                    }
                    else -> current.player = engine.player
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // Swiped away from recents: keep playing only if something is actually playing
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.release()
        session = null
        super.onDestroy()
    }
}

/** Keeps a MediaController connected so the service stays alive and shows its notification. */
@UnstableApi
class PlaybackServiceConnection(private val context: Context) {
    private var controller: ListenableFuture<MediaController>? = null

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controller = MediaController.Builder(context, token).buildAsync()
    }

    fun disconnect() {
        controller?.let { MediaController.releaseFuture(it) }
        controller = null
    }
}
