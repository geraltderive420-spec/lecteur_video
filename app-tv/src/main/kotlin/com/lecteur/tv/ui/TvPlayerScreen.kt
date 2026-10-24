package com.lecteur.tv.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Toc
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.IconButton
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.lecteur.core.player.display.DisplayMode
import com.lecteur.core.player.engine.Media3PlayerEngine
import com.lecteur.core.player.engine.PlayerState
import com.lecteur.core.player.settings.EqualizerPreset
import com.lecteur.core.player.settings.SubtitleEdge
import com.lecteur.core.player.settings.SubtitleStyle
import com.lecteur.core.player.sleep.SleepTimerState
import com.lecteur.core.player.tracks.AudioOption
import com.lecteur.core.player.tracks.LanguageCodes
import com.lecteur.core.player.tracks.SUBTITLE_OFF
import com.lecteur.core.player.tracks.SubtitleOption
import kotlinx.coroutines.delay
import java.util.Locale

private const val SEEK_STEP_MS = 10_000L
private const val CONTROLS_MS = 6_000L
private const val SEEK_OVERLAY_MS = 2_500L

/** The menus of the player, the phone's sheets for the remote. */
private enum class TvPanel(val title: String) {
    AUDIO("Audio"), SUBTITLES("Sous-titres"), SPEED("Vitesse"), DISPLAY("Format d'image"), DELAYS("Décalages"),
    STYLE("Style des sous-titres"), SOUND("Son"), CHAPTERS("Chapitres"), SLEEP("Minuterie"), INFO("Informations")
}

/**
 * Full-screen playback on the TV, with the phone's options. Hidden bar: OK or up/down show it, left/right seek 10 s,
 * back leaves. Bar shown: the arrows move between its buttons, back hides it. A menu open: back closes it. A phone
 * connected in companion mode drives the same engine through the receiver host.
 */
@UnstableApi
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvPlayerScreen(viewModel: TvReceiverViewModel) {
    val engine by viewModel.engine.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val player = engine ?: return
    val state by player.state.collectAsStateWithLifecycle()
    val progress by player.progress.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val displayMode by viewModel.displayMode.collectAsStateWithLifecycle()

    var controls by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<TvPanel?>(null) }
    var lastKeyAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var seekOverlayUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val screenFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }

    LaunchedEffect(player, state.status) { viewModel.loadChapters(player) }
    LaunchedEffect(Unit) { screenFocus.requestFocus() }
    // The clock of the auto-hide and of the seek overlay
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            if (controls && panel == null && state.isPlaying && now - lastKeyAt > CONTROLS_MS) controls = false
            delay(250)
        }
    }
    LaunchedEffect(controls, panel) {
        if (controls && panel == null) runCatching { playFocus.requestFocus() }
        if (!controls) runCatching { screenFocus.requestFocus() }
    }

    BackHandler {
        when {
            panel != null -> panel = null
            controls -> controls = false
            else -> viewModel.closePlayback()
        }
    }

    fun showControls() {
        controls = true
        lastKeyAt = System.currentTimeMillis()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                lastKeyAt = System.currentTimeMillis()
                when (event.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (state.isPlaying) player.pause() else player.play(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { player.seekBy(SEEK_STEP_MS); seekOverlayUntil = now + SEEK_OVERLAY_MS; true }
                    AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> { player.seekBy(-SEEK_STEP_MS); seekOverlayUntil = now + SEEK_OVERLAY_MS; true }
                    AndroidKeyEvent.KEYCODE_MEDIA_STOP -> { viewModel.closePlayback(); true }
                    AndroidKeyEvent.KEYCODE_MENU, AndroidKeyEvent.KEYCODE_CAPTIONS -> {
                        showControls()
                        if (event.key.nativeKeyCode == AndroidKeyEvent.KEYCODE_CAPTIONS) panel = TvPanel.SUBTITLES
                        true
                    }
                    // Bar hidden: the remote's keys act on the video itself
                    else -> if (controls || panel != null) false else when (event.key.nativeKeyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { player.seekBy(SEEK_STEP_MS); seekOverlayUntil = System.currentTimeMillis() + SEEK_OVERLAY_MS; true }
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { player.seekBy(-SEEK_STEP_MS); seekOverlayUntil = System.currentTimeMillis() + SEEK_OVERLAY_MS; true }
                        AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
                        AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { showControls(); true }
                        else -> false
                    }
                }
            }
            .focusRequester(screenFocus)
            .focusable()
    ) {
        Video(player, state, displayMode, settings.subtitleStyle)

        val showBar = controls || panel != null || !state.isPlaying || now < seekOverlayUntil
        if (showBar) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                    .padding(horizontal = 48.dp, vertical = 28.dp)
            ) {
                Text(ui.title.orEmpty(), style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val fraction = if (progress.durationMs > 0) (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${clock(progress.positionMs)} / ${clock(progress.durationMs)}" + if (!state.isPlaying) "   ⏸" else "",
                        style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.weight(1f)
                    )
                    val sleep by viewModel.sleep.collectAsStateWithLifecycle()
                    (sleep as? SleepTimerState.Counting)?.let { Text("Veille dans ${clock(it.remainingMs)}", style = MaterialTheme.typography.labelLarge, color = Color.White) }
                }
                state.error?.let {
                    Text(it.message, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodyLarge)
                    it.suggestion?.let { s -> Text(s, color = Color.White, style = MaterialTheme.typography.bodyMedium) }
                }
                if (controls || panel != null) {
                    Spacer(Modifier.height(14.dp))
                    ControlBar(viewModel, player, state, playFocus, onOpen = { panel = it }, onUsed = { lastKeyAt = System.currentTimeMillis() })
                }
            }
        }

        panel?.let { p ->
            Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(460.dp).background(Color(0xF01A1A1F)).padding(28.dp)) {
                Panel(p, viewModel, player, state, onClose = { panel = null })
            }
        }
    }
}

/** The picture in the chosen display mode, and subtitles in the user's style (the same as on the phone). */
@UnstableApi
@Composable
private fun Video(player: Media3PlayerEngine, state: PlayerState, mode: DisplayMode, style: SubtitleStyle) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val video = state.videoSize
        val modifier = when {
            mode.forcedRatio != null -> Modifier.fillMaxHeight().aspectRatio(mode.forcedRatio!!, matchHeightConstraintsFirst = true)
            mode == DisplayMode.ORIGINAL && video.width > 0 -> with(density) { Modifier.size(video.width.toDp(), video.height.toDp()) }
            else -> Modifier.fillMaxSize()
        }
        AndroidView(
            modifier = modifier,
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    keepScreenOn = true
                    this.player = player.player
                }
            },
            update = { view ->
                if (view.player !== player.player) view.player = player.player
                view.resizeMode = when (mode) {
                    DisplayMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    DisplayMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                }
                view.subtitleView?.apply {
                    setStyle(
                        CaptionStyleCompat(
                            style.textColorArgb, style.backgroundArgb, 0x00000000,
                            when (style.edge) {
                                SubtitleEdge.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
                                SubtitleEdge.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
                                SubtitleEdge.DROP_SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
                            },
                            style.edgeColorArgb, null
                        )
                    )
                    setFractionalTextSize(style.sizeFraction)
                    setBottomPaddingFraction(style.bottomPaddingFraction)
                    setApplyEmbeddedStyles(true)
                    setApplyEmbeddedFontSizes(false)
                }
            }
        )
    }
}

@UnstableApi
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ControlBar(
    viewModel: TvReceiverViewModel, player: Media3PlayerEngine, state: PlayerState, playFocus: FocusRequester,
    onOpen: (TvPanel) -> Unit, onUsed: () -> Unit
) {
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    data class Control(val label: String, val icon: ImageVector, val action: () -> Unit)
    val controls = buildList {
        add(Control("−10 s", Icons.Rounded.Replay10) { player.seekBy(-SEEK_STEP_MS) })
        add(Control(if (state.isPlaying) "Pause" else "Lecture", if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow) {
            if (state.isPlaying) player.pause() else player.play()
        })
        add(Control("+10 s", Icons.Rounded.Forward10) { player.seekBy(SEEK_STEP_MS) })
        add(Control("Audio", Icons.Rounded.Audiotrack) { onOpen(TvPanel.AUDIO) })
        add(Control("Sous-titres", Icons.Rounded.ClosedCaption) { onOpen(TvPanel.SUBTITLES) })
        add(Control("Vitesse ${speedLabel(state.speed)}", Icons.Rounded.Speed) { onOpen(TvPanel.SPEED) })
        add(Control("Image", Icons.Rounded.AspectRatio) { onOpen(TvPanel.DISPLAY) })
        add(Control("Décalages", Icons.Rounded.Sync) { onOpen(TvPanel.DELAYS) })
        add(Control("Style", Icons.Rounded.TextFields) { onOpen(TvPanel.STYLE) })
        add(Control("Son", Icons.Rounded.Equalizer) { onOpen(TvPanel.SOUND) })
        if (chapters.isNotEmpty()) add(Control("Chapitres", Icons.Rounded.Toc) { onOpen(TvPanel.CHAPTERS) })
        add(Control("Minuterie", Icons.Rounded.Bedtime) { onOpen(TvPanel.SLEEP) })
        add(Control("Infos", Icons.Rounded.Info) { onOpen(TvPanel.INFO) })
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(controls) { i, c ->
            OutlinedButton(onClick = { onUsed(); c.action() }, modifier = if (i == 1) Modifier.focusRequester(playFocus) else Modifier) {
                Icon(c.icon, null, tint = androidx.tv.material3.LocalContentColor.current, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(c.label)
            }
        }
    }
}

@UnstableApi
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Panel(panel: TvPanel, viewModel: TvReceiverViewModel, player: Media3PlayerEngine, state: PlayerState, onClose: () -> Unit) {
    val first = remember(panel) { FocusRequester() }
    LaunchedEffect(panel) { runCatching { first.requestFocus() } }
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    /** One selectable line; the first line takes the focus when the menu opens. */
    @Composable
    fun Choice(index: Int, label: String, selected: Boolean, detail: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
        ListItem(
            selected = selected,
            onClick = onClick,
            enabled = enabled,
            headlineContent = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = detail?.let { { Text(it, style = MaterialTheme.typography.labelSmall) } },
            trailingContent = if (selected) ({ Icon(Icons.Rounded.Check, null, tint = androidx.tv.material3.LocalContentColor.current) }) else null,
            modifier = if (index == 0) Modifier.focusRequester(first) else Modifier
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(panel.title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (panel) {
                TvPanel.AUDIO -> {
                    if (state.audioOptions.isEmpty()) item { Text("Aucune piste audio.", color = Color.White) }
                    itemsIndexed(state.audioOptions) { i, a ->
                        Choice(i, audioLabel(a), a.isSelected, if (!a.isSupported) "Format non pris en charge par cette TV" else null, enabled = a.isSupported) {
                            player.selectAudio(a.index)
                            onClose()
                        }
                    }
                }

                TvPanel.SUBTITLES -> {
                    item { Choice(0, "Désactivés", state.subtitleOptions.none { it.isSelected }) { player.selectSubtitle(SUBTITLE_OFF); onClose() } }
                    itemsIndexed(state.subtitleOptions) { i, s ->
                        Choice(i + 1, subtitleLabel(s), s.isSelected, if (!s.isSupported) "Format non pris en charge" else null, enabled = s.isSupported) {
                            player.selectSubtitle(s.index)
                            onClose()
                        }
                    }
                }

                TvPanel.SPEED -> itemsIndexed(SPEEDS) { i, speed ->
                    Choice(i, speedLabel(speed), state.speed == speed) { player.setSpeed(speed); onClose() }
                }

                TvPanel.DISPLAY -> {
                    val current = viewModel.displayMode.value
                    itemsIndexed(DisplayMode.entries) { i, mode ->
                        Choice(i, displayLabel(mode), mode == current) { viewModel.setDisplayMode(mode); onClose() }
                    }
                }

                TvPanel.DELAYS -> {
                    item { Text("Audio : ${signed(state.audioDelayMs)}", color = Color.White, style = MaterialTheme.typography.titleMedium) }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { player.setAudioDelayMs(state.audioDelayMs - 50) }, modifier = Modifier.focusRequester(first)) { Text("−50 ms") }
                            OutlinedButton(onClick = { player.setAudioDelayMs(state.audioDelayMs + 50) }) { Text("+50 ms") }
                            OutlinedButton(onClick = { player.setAudioDelayMs(0) }) { Text("0") }
                        }
                    }
                    if (state.technical.isAudioPassthrough) item {
                        Text("Son envoyé tel quel à l'ampli : le décalage audio ne s'applique pas.", color = Color.White, style = MaterialTheme.typography.bodySmall)
                    }
                    item { Text("Sous-titres : ${signed(state.subtitleDelayMs)}", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { player.setSubtitleDelayMs(state.subtitleDelayMs - 100) }) { Text("−100 ms") }
                            OutlinedButton(onClick = { player.setSubtitleDelayMs(state.subtitleDelayMs + 100) }) { Text("+100 ms") }
                            OutlinedButton(onClick = { player.setSubtitleDelayMs(0) }) { Text("0") }
                        }
                    }
                    item { Text("Positif : le son ou les sous-titres arrivent plus tard.", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall) }
                }

                TvPanel.STYLE -> {
                    val style = settings.subtitleStyle
                    item { Text("Taille", color = Color.White, style = MaterialTheme.typography.titleSmall) }
                    itemsIndexed(SIZES) { i, (label, size) ->
                        Choice(i, label, kotlin.math.abs(style.sizeFraction - size) < 0.002f) { viewModel.setSubtitleStyle { it.copy(sizeFraction = size) } }
                    }
                    item { Text("Couleur", color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                    itemsIndexed(COLORS) { i, (label, argb) ->
                        Choice(SIZES.size + i, label, style.textColorArgb == argb) { viewModel.setSubtitleStyle { it.copy(textColorArgb = argb) } }
                    }
                    item {
                        val withBackground = style.backgroundArgb != 0
                        Choice(99, "Fond semi-transparent", withBackground) {
                            viewModel.setSubtitleStyle { it.copy(backgroundArgb = if (withBackground) 0 else 0x99000000.toInt()) }
                        }
                    }
                    item {
                        Choice(98, "Remonter les sous-titres", style.bottomPaddingFraction > 0.1f) {
                            viewModel.setSubtitleStyle { it.copy(bottomPaddingFraction = if (it.bottomPaddingFraction > 0.1f) 0.08f else 0.16f) }
                        }
                    }
                }

                TvPanel.SOUND -> {
                    item { Choice(0, "Mode nuit", settings.nightMode, "Rapproche le volume des dialogues et des explosions") { viewModel.setNightMode(!settings.nightMode) } }
                    item { Text("Égaliseur", color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                    itemsIndexed(EqualizerPreset.entries) { i, preset ->
                        Choice(i + 1, equalizerLabel(preset), settings.equalizer == preset) { viewModel.setEqualizer(preset) }
                    }
                }

                TvPanel.CHAPTERS -> {
                    val chapters = viewModel.chapters.value
                    itemsIndexed(chapters) { i, c ->
                        Choice(i, c.title ?: "Chapitre ${c.index + 1}", false, clock(c.startMs)) { player.seekTo(c.startMs); onClose() }
                    }
                }

                TvPanel.SLEEP -> {
                    val sleep = viewModel.sleep.value
                    itemsIndexed(listOf(0, 15, 30, 45, 60, 90, 120)) { i, minutes ->
                        val label = if (minutes == 0) "Désactivée" else "Dans $minutes min"
                        Choice(i, label, if (minutes == 0) sleep is SleepTimerState.Off else false) { viewModel.startSleep(minutes); onClose() }
                    }
                }

                TvPanel.INFO -> {
                    val t = state.technical
                    val lines = listOfNotNull(
                        "Image" to "${t.videoSize.width} × ${t.videoSize.height}" + (t.frameRate?.let { " · %.3f i/s".format(Locale.FRANCE, it) } ?: ""),
                        "Codec vidéo" to listOfNotNull(t.videoCodec, t.videoDecoder?.let { "($it)" }).joinToString(" "),
                        "Décodage" to when (t.isHardwareVideoDecoder) { true -> "matériel"; false -> "logiciel"; null -> "—" },
                        "HDR" to t.sourceHdr.name.replace('_', ' ') + if (t.isDolbyVisionDecoder) " (décodeur Dolby Vision)" else "",
                        "Débit vidéo" to (t.videoBitrate?.let { "%.1f Mb/s".format(Locale.FRANCE, it / 1_000_000.0) } ?: "—"),
                        "Audio" to listOfNotNull(t.audioCodec, t.audioChannels?.let { "$it canaux" }, t.audioSampleRate?.let { "$it Hz" }).joinToString(" · "),
                        "Sortie audio" to if (t.isAudioPassthrough) "passthrough (ampli)" else "décodée",
                        "Images perdues" to t.droppedFrames.toString()
                    )
                    itemsIndexed(lines) { i, (label, value) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).then(if (i == 0) Modifier.focusRequester(first).focusable() else Modifier)) {
                            Text(label, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.width(150.dp))
                            Text(value.ifBlank { "—" }, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
private val SIZES = listOf("Petite" to 0.0433f, "Normale" to 0.0533f, "Grande" to 0.0653f, "Très grande" to 0.08f)
private val COLORS = listOf("Blanc" to 0xFFFFFFFF.toInt(), "Jaune" to 0xFFFFEB3B.toInt(), "Cyan" to 0xFF80DEEA.toInt())

private fun speedLabel(speed: Float): String = if (speed == 1f) "normale" else "×" + "%.2f".format(Locale.FRANCE, speed).trimEnd('0').trimEnd(',')

private fun signed(ms: Long): String = when {
    ms > 0 -> "+$ms ms"
    ms < 0 -> "$ms ms"
    else -> "aucun"
}

private fun language(code: String?): String? = LanguageCodes.displayName(code, Locale.FRENCH)?.replaceFirstChar { it.titlecase(Locale.FRENCH) }

private fun channels(count: Int): String? = when (count) {
    0 -> null
    1 -> "mono"
    2 -> "stéréo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "$count canaux"
}

private fun audioLabel(a: AudioOption): String {
    val lang = language(a.language) ?: "Piste ${a.index + 1}"
    return listOfNotNull(lang, a.codec, channels(a.channels), a.label?.takeIf { it.isNotBlank() && !it.equals(lang, ignoreCase = true) }).joinToString(" · ")
}

private fun subtitleLabel(s: SubtitleOption): String {
    val lang = language(s.language) ?: s.label?.takeIf { it.isNotBlank() } ?: "Piste ${s.index + 1}"
    return listOfNotNull(lang, s.codec, "forcés".takeIf { s.isForced }, "externe".takeIf { s.isExternal }).joinToString(" · ")
}

private fun displayLabel(mode: DisplayMode): String = when (mode) {
    DisplayMode.FIT -> "Ajuster"
    DisplayMode.FILL -> "Remplir (rogner)"
    DisplayMode.STRETCH -> "Étirer"
    DisplayMode.ORIGINAL -> "Original (1:1)"
    DisplayMode.RATIO_16_9 -> "16:9"
    DisplayMode.RATIO_4_3 -> "4:3"
    DisplayMode.RATIO_21_9 -> "21:9"
}

private fun equalizerLabel(preset: EqualizerPreset): String = when (preset) {
    EqualizerPreset.FLAT -> "Neutre"
    EqualizerPreset.VOICE -> "Voix"
    EqualizerPreset.BASS_BOOST -> "Graves"
    EqualizerPreset.TREBLE_BOOST -> "Aigus"
}

private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
