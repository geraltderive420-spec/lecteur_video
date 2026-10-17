package com.lecteur.desktop.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.lecteur.desktop.player.PlaybackOwner
import com.lecteur.desktop.ui.AppController
import com.lecteur.desktop.ui.LecteurTheme
import com.lecteur.desktop.ui.LocalApp
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.delay
import java.awt.Cursor
import java.awt.Dialog
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage

/** State of the player window shared by the video window and its controls overlay. */
class PlayerWindowState {
    var fullscreen by mutableStateOf(false)
    var alwaysOnTop by mutableStateOf(false)
    var mini by mutableStateOf(false)
    var lastActivity by mutableLongStateOf(System.currentTimeMillis())
    var menuOpen by mutableStateOf(false)
    var overlayBounds by mutableStateOf<Rectangle?>(null)
    var maximized by mutableStateOf(false)

    /** Diagnostics: how often the overlay was shown, and how often its window was really moved or resized. */
    var overlayShows = 0
    var overlayReshapes = 0

    /** The video window (borderless: its title bar and resize edges are drawn by the controls overlay). */
    var frame: java.awt.Frame? = null
        internal set

    /** Where the window sits when it is neither fullscreen, maximised nor mini. */
    internal var normalBounds: Rectangle? = null

    val isFree: Boolean get() = !fullscreen && !maximized && !mini

    fun poke() {
        lastActivity = System.currentTimeMillis()
    }

    fun minimize() {
        frame?.extendedState = java.awt.Frame.ICONIFIED
    }

    /** Moves the window by a drag of the overlay's title bar ([dx], [dy] in screen units). */
    fun moveBy(dx: Int, dy: Int) {
        val f = frame ?: return
        if (!isFree) return
        f.setLocation(f.x + dx, f.y + dy)
    }

    /** Resizes from an edge: [edges] says which sides move (left, top, right, bottom). */
    fun resizeBy(dx: Int, dy: Int, left: Boolean, top: Boolean, right: Boolean, bottom: Boolean) {
        val f = frame ?: return
        if (!isFree) return
        val b = f.bounds
        var x = b.x
        var y = b.y
        var w = b.width
        var h = b.height
        if (left) { x += dx; w -= dx }
        if (right) w += dx
        if (top) { y += dy; h -= dy }
        if (bottom) h += dy
        if (w < MIN_W) { if (left) x -= MIN_W - w; w = MIN_W }
        if (h < MIN_H) { if (top) y -= MIN_H - h; h = MIN_H }
        f.setBounds(x, y, w, h)
    }

    private companion object {
        const val MIN_W = 480
        const val MIN_H = 270
    }
}

/**
 * The video window: libVLC draws straight into a heavyweight canvas (GPU decoding and output, no frame copy). Compose
 * cannot paint over a native child window, so the controls live in a borderless transparent window owned by this one
 * and kept exactly over the video area; it is only shown while the controls are, so the picture is untouched otherwise.
 */
@Composable
fun PlayerWindow(app: AppController, icon: Painter?) {
    val services = app.services
    val session by services.playback.ui.collectAsState()
    val settings by services.settings.state.collectAsState()
    val engine = services.engine
    val ui = app.player
    val windowState = rememberWindowState(size = DpSize(1280.dp, 720.dp), position = WindowPosition.Aligned(androidx.compose.ui.Alignment.Center))

    // Borderless window: fullscreen is the monitor's bounds (the taskbar steps aside), maximised is the work area.
    // AWT's own fullscreen keeps the title bar of a decorated window and is exclusive; this one is neither.
    Window(
        onCloseRequest = { if (session.owner == PlaybackOwner.REMOTE) services.playback.endRemote() else services.playback.close() },
        state = windowState,
        title = listOfNotNull(session.title, session.subtitle).joinToString(" · ").ifBlank { "Lecteur Média" },
        icon = icon,
        undecorated = true,
        resizable = true,
        alwaysOnTop = ui.alwaysOnTop || ui.mini
    ) {
        val frame = window
        ui.frame = frame
        LaunchedEffect(ui.fullscreen, ui.maximized, ui.mini) {
            val config = frame.graphicsConfiguration
            val screen = config.bounds
            val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
            val work = Rectangle(screen.x + insets.left, screen.y + insets.top, screen.width - insets.left - insets.right, screen.height - insets.top - insets.bottom)
            frame.bounds = when {
                ui.fullscreen -> screen
                ui.mini -> Rectangle(work.x + work.width - 500, work.y + work.height - 300, 480, 270)
                ui.maximized -> work
                else -> ui.normalBounds ?: Rectangle(work.x + (work.width - 1280) / 2, work.y + (work.height - 720) / 2, 1280, 720)
            }
        }
        // Keep the overlay glued to the video area, whatever moves the window (drag, maximise, fullscreen, mini mode)
        DisposableEffect(frame) {
            fun track() {
                if (!frame.isShowing) return
                if (ui.isFree && frame.extendedState == java.awt.Frame.NORMAL) ui.normalBounds = frame.bounds
                val pane = frame.contentPane
                val origin = runCatching { pane.locationOnScreen }.getOrNull() ?: return
                ui.overlayBounds = Rectangle(origin.x, origin.y, pane.width, pane.height)
                engine.applyDisplay()
            }
            val listener = object : ComponentAdapter() {
                override fun componentMoved(e: ComponentEvent) = track()
                override fun componentResized(e: ComponentEvent) = track()
                override fun componentShown(e: ComponentEvent) = track()
            }
            frame.addComponentListener(listener)
            frame.contentPane.addComponentListener(listener)
            track()
            onDispose {
                frame.removeComponentListener(listener)
                frame.contentPane.removeComponentListener(listener)
            }
        }

        // Mouse on the video itself (libVLC's mouse handling is off, so the canvas gets the events)
        DisposableEffect(engine.canvas) {
            val canvas = engine.canvas
            var pendingClick: Thread? = null
            val mouse = object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) = ui.poke()
                override fun mouseDragged(e: MouseEvent) = ui.poke()
                override fun mouseClicked(e: MouseEvent) {
                    ui.poke()
                    if (e.button != MouseEvent.BUTTON1) return
                    if (e.clickCount >= 2) {
                        pendingClick?.interrupt()
                        ui.fullscreen = !ui.fullscreen
                    } else {
                        pendingClick = Thread {
                            try {
                                Thread.sleep(260)
                                engine.togglePause()
                            } catch (_: InterruptedException) {
                            }
                        }.apply { isDaemon = true; start() }
                    }
                }

                override fun mouseWheelMoved(e: MouseWheelEvent) {
                    ui.poke()
                    engine.setVolume((engine.state.value.volume - e.wheelRotation * 5).coerceAtMost(PlayerKeys.maxVolume(app)))
                }
            }
            canvas.addMouseListener(mouse)
            canvas.addMouseMotionListener(mouse)
            canvas.addMouseWheelListener(mouse)
            onDispose {
                canvas.removeMouseListener(mouse)
                canvas.removeMouseMotionListener(mouse)
                canvas.removeMouseWheelListener(mouse)
            }
        }

        // Keyboard shortcuts wherever the focus is inside the player
        DisposableEffect(frame) {
            val dispatcher = KeyEventDispatcher { e ->
                if (e.id != KeyEvent.KEY_PRESSED) return@KeyEventDispatcher false
                val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
                if (focused !== frame && focused?.owner !== frame) return@KeyEventDispatcher false
                PlayerKeys.handle(e, app, ui)
            }
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
            onDispose { KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher) }
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            SwingPanel(background = Color.Black, factory = { engine.canvas }, modifier = Modifier.fillMaxSize())
        }

        // Controls visibility: shown on activity, while paused, while a menu or a prompt is up
        val engineState by engine.state.collectAsState()
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(250)
                now = System.currentTimeMillis()
            }
        }
        val visible = !engineState.isPlaying || ui.menuOpen || now - ui.lastActivity < HIDE_AFTER_MS ||
            session.resumeQuestion != null || session.upNextSeconds != null
        LaunchedEffect(visible) {
            engine.canvas.cursor = if (visible) Cursor.getDefaultCursor() else BLANK_CURSOR
            if (visible) ui.overlayShows++
        }

        val bounds = ui.overlayBounds
        if (bounds != null) {
            DialogWindow(
                visible = visible,
                create = {
                    ComposeDialog(frame, Dialog.ModalityType.MODELESS).apply {
                        isUndecorated = true
                        isTransparent = true
                        focusableWindowState = false
                        type = java.awt.Window.Type.UTILITY
                        this.bounds = bounds
                    }
                },
                dispose = ComposeDialog::dispose,
                update = { dialog -> if (dialog.bounds != bounds) { ui.overlayReshapes++; dialog.bounds = bounds } }
            ) {
                CompositionLocalProvider(LocalApp provides app) {
                    LecteurTheme(settings.themeMode) {
                        Hud(ui)
                    }
                }
            }
        }
    }
}

private const val HIDE_AFTER_MS = 2_800L

private val BLANK_CURSOR: Cursor = Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "blank")
