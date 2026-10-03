package com.lecteur.core.player.session

import android.app.PendingIntent
import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.lecteur.core.player.engine.EngineConfig
import com.lecteur.core.player.engine.Media3PlayerEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the single player of the process. The playback screen and the MediaSession service
 * (notification, lock screen, Bluetooth buttons) share the same engine through it.
 * Main thread only.
 */
@UnstableApi
@Singleton
class PlayerHolder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _engine = MutableStateFlow<Media3PlayerEngine?>(null)
    val engine: StateFlow<Media3PlayerEngine?> = _engine.asStateFlow()

    /** Where a tap on the media notification leads; set by the playback screen. */
    @Volatile
    var sessionActivity: PendingIntent? = null

    /**
     * Returns the engine, building a new one when the build-time settings (decoder mode, passthrough, tunneling)
     * differ from the existing instance. Playback state of a replaced engine is lost, which is why this is
     * called when opening media, not while one is playing.
     */
    fun acquire(config: EngineConfig): Media3PlayerEngine {
        val existing = _engine.value
        if (existing != null && existing.config == config) return existing
        existing?.release()
        return Media3PlayerEngine(context, config).also { _engine.value = it }
    }

    fun release() {
        _engine.value?.release()
        _engine.value = null
    }
}
