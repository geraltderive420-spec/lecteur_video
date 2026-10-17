package com.lecteur.desktop.ui.player

import com.lecteur.desktop.player.PlaybackOwner
import com.lecteur.desktop.ui.AppController
import java.awt.event.KeyEvent

/** Keyboard shortcuts of the player, close to VLC's and to the web players'. */
object PlayerKeys {
    fun handle(e: KeyEvent, app: AppController, ui: PlayerWindowState): Boolean {
        val playback = app.services.playback
        val engine = playback.engine
        val state = engine.state.value
        val step = app.services.settings.value.seekStepSeconds * 1000L
        ui.poke()
        when (e.keyCode) {
            KeyEvent.VK_SPACE, KeyEvent.VK_K -> engine.togglePause()
            KeyEvent.VK_LEFT -> engine.seekBy(if (e.isControlDown) -60_000 else if (e.isShiftDown) -3_000 else -step)
            KeyEvent.VK_RIGHT -> engine.seekBy(if (e.isControlDown) 60_000 else if (e.isShiftDown) 3_000 else step)
            KeyEvent.VK_J -> engine.seekBy(-10_000)
            KeyEvent.VK_L -> engine.seekBy(10_000)
            KeyEvent.VK_UP -> engine.setVolume((state.volume + 5).coerceAtMost(maxVolume(app)))
            KeyEvent.VK_DOWN -> engine.setVolume(state.volume - 5)
            KeyEvent.VK_M -> engine.setMuted(!state.muted)
            KeyEvent.VK_F, KeyEvent.VK_F11 -> ui.fullscreen = !ui.fullscreen
            KeyEvent.VK_ENTER -> if (e.isAltDown) ui.fullscreen = !ui.fullscreen else return false
            KeyEvent.VK_ESCAPE -> when {
                ui.menuOpen -> ui.menuOpen = false
                ui.fullscreen -> ui.fullscreen = false
                ui.mini -> ui.mini = false
                ui.maximized -> ui.maximized = false
                playback.ui.value.owner == PlaybackOwner.REMOTE -> playback.endRemote()
                else -> playback.close()
            }
            KeyEvent.VK_N -> playback.next()
            KeyEvent.VK_P -> playback.previous()
            KeyEvent.VK_HOME -> engine.seekTo(0)
            KeyEvent.VK_S -> cycleSubtitle(app)
            KeyEvent.VK_A -> cycleAudio(app)
            KeyEvent.VK_G -> engine.setSubtitleDelay(state.subtitleDelayMs - 100)
            KeyEvent.VK_H -> engine.setSubtitleDelay(state.subtitleDelayMs + 100)
            KeyEvent.VK_OPEN_BRACKET, KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT -> engine.setSpeed((state.speed - 0.25f).coerceAtLeast(0.25f))
            KeyEvent.VK_CLOSE_BRACKET, KeyEvent.VK_PLUS, KeyEvent.VK_ADD, KeyEvent.VK_EQUALS -> engine.setSpeed((state.speed + 0.25f).coerceAtMost(4f))
            KeyEvent.VK_BACK_SPACE -> engine.setSpeed(1f)
            KeyEvent.VK_Z -> engine.setDisplayMode(state.displayMode.next())
            KeyEvent.VK_PAGE_UP -> engine.nextChapter()
            KeyEvent.VK_PAGE_DOWN -> engine.previousChapter()
            in KeyEvent.VK_0..KeyEvent.VK_9 -> if (state.durationMs > 0) engine.seekTo(state.durationMs * (e.keyCode - KeyEvent.VK_0) / 10) else return false
            in KeyEvent.VK_NUMPAD0..KeyEvent.VK_NUMPAD9 -> if (state.durationMs > 0) engine.seekTo(state.durationMs * (e.keyCode - KeyEvent.VK_NUMPAD0) / 10) else return false
            else -> return false
        }
        return true
    }

    fun maxVolume(app: AppController): Int = if (app.services.settings.value.volumeBoost) 200 else 100

    private fun cycleSubtitle(app: AppController) {
        val s = app.services.engine.state.value
        if (s.subtitles.isEmpty()) return
        val current = s.selectedSubtitleIndex ?: -1
        val next = if (current + 1 >= s.subtitles.size) -1 else current + 1
        app.services.playback.selectSubtitle(next)
        app.say(if (next < 0) "Sous-titres désactivés" else "Sous-titres : ${s.subtitles[next].label ?: s.subtitles[next].language}")
    }

    private fun cycleAudio(app: AppController) {
        val s = app.services.engine.state.value
        if (s.audio.size < 2) return
        val next = ((s.selectedAudioIndex ?: 0) + 1) % s.audio.size
        app.services.playback.selectAudio(next)
    }
}
