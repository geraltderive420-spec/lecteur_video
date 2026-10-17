package com.lecteur.desktop.ui.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lecteur.core.model.channelLabel
import com.lecteur.core.player.display.DisplayMode
import com.lecteur.core.player.queue.RepeatMode
import com.lecteur.core.player.sleep.SleepTimerState
import com.lecteur.desktop.data.LibrarySnapshot
import com.lecteur.desktop.player.EngineStatus
import com.lecteur.desktop.player.PlaybackOwner
import com.lecteur.desktop.ui.Tip
import com.lecteur.desktop.ui.app
import com.lecteur.desktop.ui.formatTime
import java.io.File
import javax.swing.JFileChooser

private enum class Panel { AUDIO, SUBTITLES, SPEED, DISPLAY, SLEEP, QUEUE, CHAPTERS, INFO }

private val White = Color.White
private val Dim = Color.White.copy(alpha = 0.7f)

val DisplayMode.label: String
    get() = when (this) {
        DisplayMode.FIT -> "Adapter (bandes noires)"
        DisplayMode.FILL -> "Remplir (recadrer)"
        DisplayMode.STRETCH -> "Étirer"
        DisplayMode.ORIGINAL -> "Taille d'origine"
        DisplayMode.RATIO_16_9 -> "Forcer 16:9"
        DisplayMode.RATIO_4_3 -> "Forcer 4:3"
        DisplayMode.RATIO_21_9 -> "Forcer 21:9"
    }

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun Hud(ui: PlayerWindowState) {
    val a = app()
    val playback = a.services.playback
    val engine = playback.engine
    val s by engine.state.collectAsState()
    val session by playback.ui.collectAsState()
    var panel by remember { mutableStateOf<Panel?>(null) }
    ui.menuOpen = panel != null
    val remote = session.owner == PlaybackOwner.REMOTE

    Box(
        Modifier.fillMaxSize()
            // Almost transparent, not fully: a window region with zero alpha would let the clicks through
            .background(Color.Black.copy(alpha = 0.02f))
            .onPointerEvent(PointerEventType.Move) { ui.pointerMoved() }
            .onPointerEvent(PointerEventType.Scroll) { e ->
                ui.poke()
                val dy = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                engine.setVolume((s.volume - (dy * 5).toInt()).coerceAtMost(PlayerKeys.maxVolume(a)))
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (panel != null) panel = null else engine.togglePause() },
                    onDoubleTap = { ui.fullscreen = !ui.fullscreen }
                )
            }
    ) {
        // Top bar: also the window's title bar (drag to move, double-click to maximise)
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        Row(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)))
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        ui.poke()
                        ui.moveBy((drag.x / density).toInt(), (drag.y / density).toInt())
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { if (!ui.fullscreen) ui.maximized = !ui.maximized }) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HudIcon(Icons.AutoMirrored.Filled.ArrowBack, "Quitter la lecture") { if (remote) playback.endRemote() else playback.close() }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(session.title ?: "", color = White, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                session.subtitle?.let { Text(it, color = Dim, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (session.remoteController != null) {
                Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f), shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PhoneAndroid, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(6.dp))
                        Text(if (remote) "Piloté par ${session.remoteController}" else "${session.remoteController} connecté", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
            HudIcon(if (ui.alwaysOnTop) Icons.Filled.PushPin else Icons.Outlined.PushPin, "Toujours au premier plan") { ui.alwaysOnTop = !ui.alwaysOnTop }
            HudIcon(Icons.Filled.PictureInPictureAlt, "Mini-lecteur") { ui.mini = !ui.mini; if (ui.mini) ui.fullscreen = false }
            Spacer(Modifier.width(12.dp))
            HudIcon(Icons.Filled.Remove, "Réduire") { ui.minimize() }
            if (!ui.fullscreen) HudIcon(if (ui.maximized) Icons.Filled.FilterNone else Icons.Filled.CropSquare, if (ui.maximized) "Restaurer" else "Agrandir") { ui.maximized = !ui.maximized; ui.mini = false }
            HudIcon(Icons.Filled.Close, "Fermer") { if (remote) playback.endRemote() else playback.close() }
        }

        // Centre: buffering / paused
        Box(Modifier.align(Alignment.Center)) {
            when {
                s.status == EngineStatus.OPENING || s.status == EngineStatus.BUFFERING -> CircularProgressIndicator(Modifier.size(56.dp), color = White)
                s.status == EngineStatus.ERROR -> Surface(color = Color.Black.copy(alpha = 0.8f), shape = RoundedCornerShape(12.dp)) {
                    Text(s.error ?: "Erreur de lecture", color = White, modifier = Modifier.padding(20.dp).widthIn(max = 520.dp))
                }
                !s.isPlaying && s.status == EngineStatus.READY -> Box(
                    Modifier.size(84.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).clickable { engine.play() },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.PlayArrow, "Lecture", Modifier.size(56.dp), tint = White) }
            }
        }

        // Resume prompt
        session.resumeQuestion?.let { q ->
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp), modifier = Modifier.align(Alignment.Center)) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(q.title, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { playback.answerResume(true) }) { Text("Reprendre à ${formatTime(q.positionMs)}") }
                        FilledTonalButton(onClick = { playback.answerResume(false) }) { Text("Depuis le début") }
                    }
                }
            }
        }

        // Bottom: seek bar and controls
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                .padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 12.dp)
        ) {
            session.upNextSeconds?.let { seconds ->
                Surface(color = Color.Black.copy(alpha = 0.75f), shape = RoundedCornerShape(12.dp), modifier = Modifier.align(Alignment.End)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("Épisode suivant dans $seconds s", color = Dim, style = MaterialTheme.typography.bodySmall)
                            Text(session.upNextTitle ?: "", color = White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        }
                        Spacer(Modifier.width(14.dp))
                        Button(onClick = playback::next) { Text("Lire") }
                        TextButton(onClick = playback::dismissUpNext) { Text("Annuler", color = White) }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            SeekBar(s.positionMs, s.durationMs) { engine.seekTo(it); ui.poke() }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${formatTime(s.positionMs)} / ${formatTime(s.durationMs)}", color = Dim, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                if (session.hasPrevious || !remote) HudIcon(Icons.Filled.SkipPrevious, "Précédent (P)") { playback.previous() }
                HudIcon(Icons.Filled.Replay10, "Reculer (←)") { engine.seekBy(-a.services.settings.value.seekStepSeconds * 1000L) }
                Box(
                    Modifier.padding(horizontal = 8.dp).size(56.dp).clip(CircleShape).background(White.copy(alpha = 0.15f)).clickable { engine.togglePause() },
                    contentAlignment = Alignment.Center
                ) { Icon(if (s.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Lecture / pause (Espace)", Modifier.size(38.dp), tint = White) }
                HudIcon(Icons.Filled.Forward10, "Avancer (→)") { engine.seekBy(a.services.settings.value.seekStepSeconds * 1000L) }
                if (session.hasNext) HudIcon(Icons.Filled.SkipNext, "Suivant (N)") { playback.next() }
                Spacer(Modifier.weight(1f))
                HudIcon(if (s.muted || s.volume == 0) Icons.AutoMirrored.Filled.VolumeOff else if (s.volume < 50) Icons.AutoMirrored.Filled.VolumeDown else Icons.AutoMirrored.Filled.VolumeUp, "Muet (M)") { engine.setMuted(!s.muted) }
                val maxVolume = if (a.services.settings.value.volumeBoost) 200f else 100f
                SlimSlider(
                    value = s.volume.toFloat().coerceAtMost(maxVolume) / maxVolume, onValueChange = { engine.setVolume((it * maxVolume).toInt()); ui.poke() },
                    modifier = Modifier.width(120.dp), color = if (s.volume > 100) MaterialTheme.colorScheme.tertiary else White
                )
                Text("${s.volume} %", color = Dim, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp).width(44.dp))
                HudIcon(Icons.Filled.Audiotrack, "Audio (A)", panel == Panel.AUDIO) { panel = if (panel == Panel.AUDIO) null else Panel.AUDIO }
                HudIcon(Icons.Filled.ClosedCaption, "Sous-titres (S)", panel == Panel.SUBTITLES) { panel = if (panel == Panel.SUBTITLES) null else Panel.SUBTITLES }
                HudIcon(Icons.Filled.Speed, "Vitesse", panel == Panel.SPEED) { panel = if (panel == Panel.SPEED) null else Panel.SPEED }
                HudIcon(Icons.Filled.AspectRatio, "Affichage (Z)", panel == Panel.DISPLAY) { panel = if (panel == Panel.DISPLAY) null else Panel.DISPLAY }
                HudIcon(Icons.Filled.Bedtime, "Minuterie de veille", panel == Panel.SLEEP) { panel = if (panel == Panel.SLEEP) null else Panel.SLEEP }
                if (s.chapters.size > 1) HudIcon(Icons.Filled.Bookmarks, "Chapitres", panel == Panel.CHAPTERS) { panel = if (panel == Panel.CHAPTERS) null else Panel.CHAPTERS }
                HudIcon(Icons.Filled.Info, "Informations techniques", panel == Panel.INFO) { panel = if (panel == Panel.INFO) null else Panel.INFO }
                if (session.queue.size > 1) HudIcon(Icons.AutoMirrored.Filled.QueueMusic, "File de lecture", panel == Panel.QUEUE) { panel = if (panel == Panel.QUEUE) null else Panel.QUEUE }
                HudIcon(if (ui.fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, "Plein écran (F)") { ui.fullscreen = !ui.fullscreen }
            }
        }

        // Resize edges of the borderless window
        if (ui.isFree) ResizeEdges(ui, density)

        // Side panel
        panel?.let { p ->
            Surface(
                color = Color(0xEE16161B), shape = RoundedCornerShape(14.dp),
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp, top = 70.dp, bottom = 120.dp).width(340.dp).fillMaxHeight()
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    when (p) {
                        Panel.AUDIO -> AudioPanel()
                        Panel.SUBTITLES -> SubtitlePanel()
                        Panel.SPEED -> SpeedPanel()
                        Panel.DISPLAY -> DisplayPanel()
                        Panel.SLEEP -> SleepPanel()
                        Panel.QUEUE -> QueuePanel { panel = null }
                        Panel.CHAPTERS -> ChaptersPanel()
                        Panel.INFO -> InfoPanel()
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.ResizeEdges(ui: PlayerWindowState, density: Float) {
    val edge = 6.dp
    @Composable
    fun handle(modifier: Modifier, cursor: Int, left: Boolean, top: Boolean, right: Boolean, bottom: Boolean) {
        Box(
            modifier.pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon(java.awt.Cursor.getPredefinedCursor(cursor)))
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        ui.poke()
                        ui.resizeBy((drag.x / density).toInt(), (drag.y / density).toInt(), left, top, right, bottom)
                    }
                }
        )
    }
    handle(Modifier.align(Alignment.CenterStart).width(edge).fillMaxHeight(), java.awt.Cursor.W_RESIZE_CURSOR, true, false, false, false)
    handle(Modifier.align(Alignment.CenterEnd).width(edge).fillMaxHeight(), java.awt.Cursor.E_RESIZE_CURSOR, false, false, true, false)
    handle(Modifier.align(Alignment.TopCenter).height(edge).fillMaxWidth(), java.awt.Cursor.N_RESIZE_CURSOR, false, true, false, false)
    handle(Modifier.align(Alignment.BottomCenter).height(edge).fillMaxWidth(), java.awt.Cursor.S_RESIZE_CURSOR, false, false, false, true)
    handle(Modifier.align(Alignment.TopStart).size(14.dp), java.awt.Cursor.NW_RESIZE_CURSOR, true, true, false, false)
    handle(Modifier.align(Alignment.TopEnd).size(14.dp), java.awt.Cursor.NE_RESIZE_CURSOR, false, true, true, false)
    handle(Modifier.align(Alignment.BottomStart).size(14.dp), java.awt.Cursor.SW_RESIZE_CURSOR, true, false, false, true)
    handle(Modifier.align(Alignment.BottomEnd).size(14.dp), java.awt.Cursor.SE_RESIZE_CURSOR, false, false, true, true)
}

@Composable
private fun HudIcon(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    Tip(label) {
        IconButton(onClick = onClick) { Icon(icon, label, tint = if (active) MaterialTheme.colorScheme.primary else White, modifier = Modifier.size(26.dp)) }
    }
}

@Composable
private fun SeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = durationMs.coerceAtLeast(1)
    Box {
        SlimSlider(
            value = dragging ?: (positionMs.toFloat() / duration).coerceIn(0f, 1f),
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { onSeek((it * duration).toLong()) }; dragging = null },
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
        )
        dragging?.let {
            Surface(color = Color.Black.copy(alpha = 0.8f), shape = RoundedCornerShape(6.dp), modifier = Modifier.align(Alignment.TopCenter)) {
                Text(formatTime((it * duration).toLong()), color = White, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }
    }
}

/** Thin track, small round thumb: the look of video players rather than of a settings slider. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SlimSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Slider(
        value = value.coerceIn(0f, 1f),
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interaction,
        modifier = modifier.height(22.dp),
        thumb = { Box(Modifier.size(14.dp).background(color, CircleShape)) },
        track = { state ->
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(White.copy(alpha = 0.28f))) {
                Box(Modifier.fillMaxWidth(state.value.coerceIn(0f, 1f)).height(4.dp).background(color))
            }
        }
    )
}

@Composable
private fun ColumnScope.PanelTitle(text: String) {
    Text(text, color = White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Option(text: String, selected: Boolean, detail: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(28.dp)) { if (selected) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
        Column {
            Text(text, color = White, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, color = Dim, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DelayRow(label: String, valueMs: Long, onChange: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Dim, modifier = Modifier.weight(1f))
        TextButton(onClick = { onChange(valueMs - 100) }) { Text("−100", color = White) }
        Text("${if (valueMs > 0) "+" else ""}$valueMs ms", color = White, modifier = Modifier.width(80.dp))
        TextButton(onClick = { onChange(valueMs + 100) }) { Text("+100", color = White) }
        TextButton(onClick = { onChange(0) }) { Text("0", color = White) }
    }
}

@Composable
private fun ColumnScope.AudioPanel() {
    val a = app()
    val s by a.services.engine.state.collectAsState()
    val settings by a.services.settings.state.collectAsState()
    PanelTitle("Piste audio")
    if (s.audio.isEmpty()) Text("Aucune piste audio.", color = Dim)
    s.audio.forEach { t ->
        Option(
            listOfNotNull(LibrarySnapshot.languageName(t.language).takeIf { t.language != null }, t.label?.takeIf { t.language == null }).firstOrNull() ?: "Piste ${t.index + 1}",
            t.isSelected,
            listOfNotNull(t.codec, channelLabel(t.channels), t.label).distinct().joinToString(" · ")
        ) { a.services.playback.selectAudio(t.index) }
    }
    Spacer(Modifier.height(12.dp))
    HorizontalDivider(color = White.copy(alpha = 0.15f))
    DelayRow("Décalage audio", s.audioDelayMs) { a.services.engine.setAudioDelay(it) }
    Option("Mode nuit", settings.nightMode, "Réduit l'écart entre dialogues et explosions") { a.services.playback.setNightMode(!settings.nightMode) }
}

@Composable
private fun ColumnScope.SubtitlePanel() {
    val a = app()
    val s by a.services.engine.state.collectAsState()
    PanelTitle("Sous-titres")
    Option("Désactivés", s.selectedSubtitleIndex == null) { a.services.playback.selectSubtitle(-1) }
    s.subtitles.forEach { t ->
        Option(
            listOfNotNull(LibrarySnapshot.languageName(t.language).takeIf { t.language != null }, t.label).firstOrNull() ?: "Piste ${t.index + 1}",
            t.isSelected,
            listOfNotNull(t.label?.takeIf { t.language != null }, if (t.isForced) "forcés" else null, t.codec).joinToString(" · ").ifBlank { null }
        ) { a.services.playback.selectSubtitle(t.index) }
    }
    TextButton(onClick = {
        val chooser = JFileChooser().apply {
            dialogTitle = "Charger des sous-titres"
            fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Sous-titres", "srt", "ass", "ssa", "vtt", "sub", "idx", "sup")
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) a.services.engine.addSubtitleFile(chooser.selectedFile)
    }) { Text("Charger un fichier de sous-titres…") }
    Spacer(Modifier.height(12.dp))
    HorizontalDivider(color = White.copy(alpha = 0.15f))
    DelayRow("Décalage (G / H)", s.subtitleDelayMs) { a.services.engine.setSubtitleDelay(it) }
}

@Composable
private fun ColumnScope.SpeedPanel() {
    val a = app()
    val s by a.services.engine.state.collectAsState()
    PanelTitle("Vitesse de lecture")
    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f).forEach { v ->
        Option(if (v == 1f) "Normale" else "×$v", s.speed == v) { a.services.engine.setSpeed(v) }
    }
}

@Composable
private fun ColumnScope.DisplayPanel() {
    val a = app()
    val s by a.services.engine.state.collectAsState()
    PanelTitle("Affichage")
    DisplayMode.entries.forEach { m -> Option(m.label, s.displayMode == m) { a.services.engine.setDisplayMode(m) } }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Zoom", color = Dim, modifier = Modifier.weight(1f))
        TextButton(onClick = { a.services.engine.setZoom(s.zoom - 0.1f) }) { Text("−", color = White) }
        Text("×%.1f".format(s.zoom), color = White)
        TextButton(onClick = { a.services.engine.setZoom(s.zoom + 0.1f) }) { Text("+", color = White) }
    }
    if (s.videoWidth > 0) Text("Image : ${s.videoWidth} × ${s.videoHeight}", color = Dim, style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = {
        val dir = File(System.getProperty("user.home"), "Pictures/Lecteur Média").apply { mkdirs() }
        val file = File(dir, "capture-${System.currentTimeMillis()}.png")
        if (a.services.engine.snapshot(file)) a.say("Capture enregistrée : ${file.name}")
    }) { Text("Capture d'image") }
}

@Composable
private fun ColumnScope.SleepPanel() {
    val a = app()
    val timer = a.services.playback.sleepTimer
    val state by timer.state.collectAsState()
    PanelTitle("Minuterie de veille")
    when (val st = state) {
        is SleepTimerState.Counting -> Text("Pause dans ${formatTime(st.remainingMs)}", color = MaterialTheme.colorScheme.primary)
        SleepTimerState.UntilEndOfMedia -> Text("Pause à la fin de la vidéo", color = MaterialTheme.colorScheme.primary)
        SleepTimerState.Off -> Unit
    }
    Option("Désactivée", state == SleepTimerState.Off) { timer.cancel() }
    listOf(15, 30, 45, 60, 90, 120).forEach { m -> Option("$m minutes", false) { timer.startAfter(m * 60_000L) } }
    Option("À la fin de la vidéo", state == SleepTimerState.UntilEndOfMedia) { timer.startUntilEndOfMedia() }
}

@Composable
private fun ColumnScope.QueuePanel(onPicked: () -> Unit) {
    val a = app()
    val session by a.services.playback.ui.collectAsState()
    PanelTitle(session.queueLabel ?: "File de lecture")
    Row {
        TextButton(onClick = { a.services.playback.setShuffle(!session.shuffle) }) {
            Text(if (session.shuffle) "Aléatoire : oui" else "Aléatoire : non", color = if (session.shuffle) MaterialTheme.colorScheme.primary else White)
        }
        TextButton(onClick = { a.services.playback.cycleRepeat() }) {
            Text(
                when (session.repeat) {
                    RepeatMode.OFF -> "Répétition : non"
                    RepeatMode.ALL -> "Répéter tout"
                    RepeatMode.ONE -> "Répéter ce fichier"
                },
                color = if (session.repeat != RepeatMode.OFF) MaterialTheme.colorScheme.primary else White
            )
        }
    }
    session.queue.forEachIndexed { i, item ->
        val title = item.mediaFileId?.let { com.lecteur.desktop.player.PlaybackController.titleOf(a.services.store.snapshot.value, it) }
        Option(title?.second ?: title?.first ?: item.title, i == session.queueIndex) {
            a.services.playback.jumpTo(i)
            onPicked()
        }
    }
}

@Composable
private fun ColumnScope.ChaptersPanel() {
    val a = app()
    val s by a.services.engine.state.collectAsState()
    PanelTitle("Chapitres")
    val current = s.chapters.indexOfLast { it.second <= s.positionMs }
    s.chapters.forEachIndexed { i, (name, start) ->
        Option(name.ifBlank { "Chapitre ${i + 1}" }, i == current, formatTime(start)) { a.services.engine.setChapter(i) }
    }
}

@Composable
private fun ColumnScope.InfoPanel() {
    val a = app()
    val s by a.services.engine.state.collectAsState()
    var stats by remember { mutableStateOf(a.services.engine.stats()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            stats = a.services.engine.stats()
            kotlinx.coroutines.delay(1_000)
        }
    }
    val request = a.services.engine.currentRequest
    PanelTitle("Informations techniques")
    InfoLine("Image", if (s.videoWidth > 0) "${s.videoWidth} × ${s.videoHeight}" else "—")
    InfoLine("Décodage", if (request?.hardwareDecoding == false) "Logiciel" else "Matériel (Direct3D 11)")
    InfoLine("Audio", s.audio.firstOrNull { it.isSelected }?.let { listOfNotNull(it.codec, channelLabel(it.channels)).joinToString(" ") } ?: "—")
    stats?.let { st ->
        InfoLine("Images affichées", "${st.displayedFrames}")
        InfoLine("Images perdues", "${st.lostFrames}")
        InfoLine("Débit", "%.1f Mb/s".format(st.inputKbps / 1000.0))
    }
    InfoLine("Source", request?.location?.let { if (it.startsWith("http")) "Flux réseau (téléphone)" else File(it).name } ?: "—")
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(label, color = Dim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(130.dp))
        Text(value, color = White, style = MaterialTheme.typography.bodyMedium)
    }
}
