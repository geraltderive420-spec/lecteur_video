package com.lecteur.feature.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.lecteur.core.player.display.DisplayMode
import com.lecteur.core.player.engine.PlayerEngine
import com.lecteur.core.player.engine.PlayerState
import com.lecteur.core.player.engine.Progress
import com.lecteur.core.player.hdr.HdrStatusResolver
import com.lecteur.core.player.settings.DecoderMode
import com.lecteur.core.player.settings.EqualizerPreset
import com.lecteur.core.player.settings.PassthroughMode
import com.lecteur.core.player.settings.PlayerSettings
import com.lecteur.core.player.settings.SubtitleEdge
import com.lecteur.core.player.queue.RepeatMode
import com.lecteur.core.player.sleep.SleepTimerState
import com.lecteur.core.player.tracks.SUBTITLE_OFF
import com.lecteur.core.player.tracks.SubtitleMode
import com.lecteur.feature.player.PlayerUiState
import com.lecteur.feature.player.PlayerViewModel
import com.lecteur.feature.player.QueueUi
import com.lecteur.feature.player.TimeFormat

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSheetHost(
    sheet: PlayerSheet?,
    onDismiss: () -> Unit,
    onSelectSheet: (PlayerSheet) -> Unit,
    viewModel: PlayerViewModel,
    state: PlayerState,
    progress: Progress,
    ui: PlayerUiState,
    settings: PlayerSettings,
    host: PlayerHostActions
) {
    if (sheet == null) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 4.dp)
        ) {
            when (sheet) {
                PlayerSheet.TRACKS -> TracksContent(viewModel, state, host, onOpenStyle = { onSelectSheet(PlayerSheet.SUBTITLE_STYLE) })
                PlayerSheet.SUBTITLE_STYLE -> SubtitleStyleContent(settings, viewModel)
                PlayerSheet.SPEED -> SpeedContent(state.speed, viewModel)
                PlayerSheet.DISPLAY -> DisplayContent(ui.displayMode, viewModel, onDismiss)
                PlayerSheet.CHAPTERS -> ChaptersContent(ui, progress, viewModel, onDismiss)
                PlayerSheet.SLEEP -> SleepContent(ui.sleepTimer, viewModel, onDismiss)
                PlayerSheet.INFO -> InfoContent(state, ui)
                PlayerSheet.SETTINGS -> SettingsContent(settings, viewModel)
                PlayerSheet.QUEUE -> QueueContent(ui.queue, viewModel, onDismiss)
            }
        }
    }
}

// region shared pieces

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 12.dp))
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
private fun ChoiceRow(selected: Boolean, label: String, supporting: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Column(Modifier.padding(start = 4.dp)) {
            Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, supporting: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun DelayRow(label: String, delayMs: Long, enabled: Boolean, onStep: (Long) -> Unit, onReset: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        OutlinedButton(onClick = { onStep(-PlayerEngine.DELAY_STEP_MS) }, enabled = enabled) { Text("−50") }
        Text(TimeFormat.delay(delayMs), Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = { onStep(PlayerEngine.DELAY_STEP_MS) }, enabled = enabled) { Text("+50") }
        TextButton(onClick = onReset, enabled = enabled && delayMs != 0L) { Text("0") }
    }
}

// endregion

@UnstableApi
@Composable
private fun TracksContent(viewModel: PlayerViewModel, state: PlayerState, host: PlayerHostActions, onOpenStyle: () -> Unit) {
    Title("Pistes")

    SectionLabel("Audio")
    if (state.audioOptions.isEmpty()) Text("Aucune piste audio.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    state.audioOptions.forEach { option ->
        ChoiceRow(
            selected = option.isSelected,
            label = PlayerLabels.audio(option),
            supporting = if (option.isSupported) null else "Non pris en charge par cet appareil",
            enabled = option.isSupported
        ) { viewModel.selectAudio(option.index) }
    }

    SectionLabel("Sous-titres")
    ChoiceRow(selected = state.selectedSubtitleIndex == null, label = "Désactivés") { viewModel.selectSubtitle(SUBTITLE_OFF) }
    state.subtitleOptions.forEach { option ->
        ChoiceRow(
            selected = option.isSelected,
            label = PlayerLabels.subtitle(option),
            supporting = PlayerLabels.subtitleProblem(option) ?: PlayerLabels.subtitleNote(option),
            enabled = option.isSupported
        ) { viewModel.selectSubtitle(option.index) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        OutlinedButton(onClick = host.pickSubtitleFile) { Text("Charger un fichier…") }
        OutlinedButton(onClick = onOpenStyle) { Text("Apparence") }
    }

    SectionLabel("Synchronisation")
    val passthrough = state.technical.isAudioPassthrough
    DelayRow("Audio", state.audioDelayMs, enabled = !passthrough, onStep = viewModel::adjustAudioDelay, onReset = { viewModel.adjustAudioDelay(-state.audioDelayMs) })
    if (passthrough) {
        Text(
            "Le décalage audio est indisponible tant que le son est envoyé tel quel à l'amplificateur (passthrough).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    DelayRow("Sous-titres", state.subtitleDelayMs, enabled = true, onStep = viewModel::adjustSubtitleDelay, onReset = { viewModel.adjustSubtitleDelay(-state.subtitleDelayMs) })
}

@Composable
private fun SubtitleStyleContent(settings: PlayerSettings, viewModel: PlayerViewModel) {
    val style = settings.subtitleStyle
    Title("Apparence des sous-titres")

    SectionLabel("Taille")
    Slider(
        value = style.sizeFraction,
        onValueChange = { v -> viewModel.updateSettings { it.copy(subtitleStyle = it.subtitleStyle.copy(sizeFraction = v)) } },
        valueRange = 0.03f..0.10f
    )

    SectionLabel("Position verticale")
    Slider(
        value = style.bottomPaddingFraction,
        onValueChange = { v -> viewModel.updateSettings { it.copy(subtitleStyle = it.subtitleStyle.copy(bottomPaddingFraction = v)) } },
        valueRange = 0f..0.3f
    )

    SectionLabel("Couleur du texte")
    val colors = listOf(0xFFFFFFFF, 0xFFFFEB3B, 0xFF80DEEA, 0xFFA5D6A7, 0xFFFFAB91).map { it.toInt() }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        colors.forEach { argb ->
            Row(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .border(if (argb == style.textColorArgb) 3.dp else 1.dp, if (argb == style.textColorArgb) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                    .clickable { viewModel.updateSettings { it.copy(subtitleStyle = it.subtitleStyle.copy(textColorArgb = argb)) } }
            ) {}
        }
    }

    SectionLabel("Contour")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(SubtitleEdge.NONE to "Aucun", SubtitleEdge.OUTLINE to "Contour", SubtitleEdge.DROP_SHADOW to "Ombre").forEach { (edge, label) ->
            FilterChip(
                selected = style.edge == edge,
                onClick = { viewModel.updateSettings { it.copy(subtitleStyle = it.subtitleStyle.copy(edge = edge)) } },
                label = { Text(label) }
            )
        }
    }

    SectionLabel("Fond")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0x00000000 to "Aucun", 0x99000000.toInt() to "Semi-transparent", 0xFF000000.toInt() to "Noir").forEach { (argb, label) ->
            FilterChip(
                selected = style.backgroundArgb == argb,
                onClick = { viewModel.updateSettings { it.copy(subtitleStyle = it.subtitleStyle.copy(backgroundArgb = argb)) } },
                label = { Text(label) }
            )
        }
    }
    Text(
        "Les sous-titres ASS/SSA gardent leurs couleurs et positions d'origine ; seule la taille est réglable. " +
            "Les animations et dessins vectoriels ASS ne sont pas rendus.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedContent(current: Float, viewModel: PlayerViewModel) {
    Title("Vitesse de lecture")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f).forEach { speed ->
            FilterChip(selected = current == speed, onClick = { viewModel.setSpeed(speed) }, label = { Text(TimeFormat.speed(speed)) })
        }
    }
    Slider(
        value = current,
        onValueChange = { viewModel.setSpeed((it / 0.05f).toInt() * 0.05f) },
        valueRange = PlayerEngine.MIN_SPEED..PlayerEngine.MAX_SPEED,
        modifier = Modifier.padding(top = 12.dp)
    )
    Text("Actuelle : ${TimeFormat.speed(current)} (la voix garde sa hauteur)", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun DisplayContent(current: DisplayMode, viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    Title("Mode d'affichage")
    DisplayMode.entries.forEach { mode ->
        ChoiceRow(selected = mode == current, label = PlayerLabels.displayMode(mode)) {
            viewModel.setDisplayMode(mode)
            onDismiss()
        }
    }
    Text(
        "Le choix est mémorisé pour ce fichier. Pincez l'image pour zoomer.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun ChaptersContent(ui: PlayerUiState, progress: Progress, viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    Title("Chapitres")
    val currentIndex = ui.chapters.currentIndex(progress.positionMs)
    ui.chapters.chapters.forEachIndexed { i, chapter ->
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    viewModel.seekTo(chapter.startMs)
                    onDismiss()
                }
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "${i + 1}. ${chapter.title ?: "Chapitre ${i + 1}"}",
                color = if (i == currentIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (i == currentIndex) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
            Text(TimeFormat.clock(chapter.startMs), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
    }
}

@Composable
private fun QueueContent(queue: QueueUi?, viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    Title(queue?.label?.let { "File de lecture · $it" } ?: "File de lecture")
    if (queue == null) {
        Text("Aucune file de lecture.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = viewModel::cycleRepeat) {
            Text(
                when (queue.repeat) {
                    RepeatMode.OFF -> "Répétition : non"
                    RepeatMode.ALL -> "Répéter tout"
                    RepeatMode.ONE -> "Répéter ce fichier"
                }
            )
        }
        FilterChip(selected = queue.shuffled, onClick = viewModel::toggleShuffle, label = { Text("Aléatoire") })
    }
    queue.titles.forEachIndexed { index, title ->
        val current = index == queue.currentIndex
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    viewModel.playQueueIndex(index)
                    onDismiss()
                }
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("${index + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(32.dp))
            Text(
                title,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        HorizontalDivider()
    }
}

@Composable
private fun SleepContent(current: SleepTimerState, viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    Title("Minuteur de sommeil")
    when (current) {
        is SleepTimerState.Counting -> Text("Pause dans ${TimeFormat.clock(current.remainingMs)}", color = MaterialTheme.colorScheme.primary)
        SleepTimerState.UntilEndOfMedia -> Text("Pause à la fin de ce média", color = MaterialTheme.colorScheme.primary)
        SleepTimerState.Off -> Unit
    }
    listOf(15, 30, 45, 60, 90).forEach { minutes ->
        ChoiceRow(selected = false, label = "$minutes minutes") {
            viewModel.startSleepTimer(minutes)
            onDismiss()
        }
    }
    ChoiceRow(selected = current == SleepTimerState.UntilEndOfMedia, label = "À la fin de l'épisode / du film") {
        viewModel.startSleepTimerUntilEnd()
        onDismiss()
    }
    if (current != SleepTimerState.Off) {
        TextButton(onClick = { viewModel.cancelSleepTimer(); onDismiss() }) { Text("Désactiver le minuteur") }
    }
}

@Composable
private fun InfoContent(state: PlayerState, ui: PlayerUiState) {
    val t = state.technical
    val hdr = HdrStatusResolver.resolve(t.sourceHdr, t.isDolbyVisionDecoder, ui.displayHdrTypes)

    Title("Informations techniques")
    SectionLabel("Vidéo")
    InfoRow("Codec", PlayerLabels.videoCodec(t.videoCodec))
    InfoRow("Décodeur", t.videoDecoder?.let { it + if (t.isHardwareVideoDecoder == true) " (matériel)" else " (logiciel)" } ?: "—")
    InfoRow("Résolution", if (t.videoSize.isKnown) "${t.videoSize.width} × ${t.videoSize.height}" else "—")
    InfoRow("Débit", PlayerLabels.bitrate(t.videoBitrate))
    InfoRow("Images/s", t.frameRate?.let { "%.3f".format(java.util.Locale.FRANCE, it).trimEnd('0').trimEnd(',') } ?: "—")
    InfoRow("Images perdues", t.droppedFrames.toString())
    InfoRow("HDR", hdr.label + if (hdr.isDegraded) " ⚠" else "")
    if (hdr.isDegraded) {
        Text(
            if (t.sourceHdr == com.lecteur.core.model.HdrType.DOLBY_VISION) "Le fichier est en Dolby Vision mais cet appareil l'affiche en repli : le badge Dolby Vision n'est pas actif."
            else "L'écran ne gère pas ce format HDR : l'image est convertie en SDR.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    SectionLabel("Audio")
    InfoRow("Codec", t.audioCodec ?: "—")
    InfoRow("Décodeur", t.audioDecoder ?: "—")
    InfoRow("Canaux", t.audioChannels?.let(PlayerLabels::channels) ?: "—")
    InfoRow("Fréquence", t.audioSampleRate?.let { "$it Hz" } ?: "—")
    InfoRow("Sortie", if (t.isAudioPassthrough) "Passthrough (flux envoyé tel quel)" else "Décodé (PCM)")
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.padding(start = 16.dp))
    }
}

@Composable
private fun SettingsContent(settings: PlayerSettings, viewModel: PlayerViewModel) {
    Title("Réglages du lecteur")

    SectionLabel("Gestes")
    SwitchRow("Gestes tactiles", settings.gesturesEnabled) { v -> viewModel.updateSettings { it.copy(gesturesEnabled = v) } }
    SwitchRow("Amplification du volume au-delà de 100 %", settings.volumeBoostEnabled) { v -> viewModel.updateSettings { it.copy(volumeBoostEnabled = v) } }
    Text("Saut du double appui : ${settings.seekStepSeconds} s", Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(5, 10, 15, 30, 60).forEach { s ->
            FilterChip(selected = settings.seekStepSeconds == s, onClick = { viewModel.updateSettings { it.copy(seekStepSeconds = s) } }, label = { Text("$s s") })
        }
    }

    SectionLabel("Enchaînement")
    SwitchRow("Lire automatiquement le fichier suivant", settings.autoPlayNext, "Épisode suivant d'une série, fichier suivant d'un dossier") { v ->
        viewModel.updateSettings { it.copy(autoPlayNext = v) }
    }
    Text("Compte à rebours : ${if (settings.nextCountdownSeconds == 0) "aucun" else "${settings.nextCountdownSeconds} s"}", Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0, 5, 10, 15, 30).forEach { seconds ->
            FilterChip(
                selected = settings.nextCountdownSeconds == seconds,
                onClick = { viewModel.updateSettings { it.copy(nextCountdownSeconds = seconds) } },
                label = { Text(if (seconds == 0) "Aucun" else "$seconds s") }
            )
        }
    }

    SectionLabel("Sous-titres")
    SubtitleMode.entries.forEach { mode ->
        ChoiceRow(selected = settings.subtitleMode == mode, label = PlayerLabels.subtitleMode(mode)) { viewModel.updateSettings { it.copy(subtitleMode = mode) } }
    }

    SectionLabel("Son")
    SwitchRow("Mode nuit", settings.nightMode, "Réduit l'écart entre dialogues et explosions") { v -> viewModel.updateSettings { it.copy(nightMode = v) } }
    Text("Égaliseur", Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EqualizerPreset.entries.forEach { preset ->
            FilterChip(selected = settings.equalizer == preset, onClick = { viewModel.updateSettings { it.copy(equalizer = preset) } }, label = { Text(PlayerLabels.equalizer(preset)) })
        }
    }
    SwitchRow("Lecture en arrière-plan (audio seul)", settings.backgroundAudio, "Le son continue quand vous quittez l'application") { v ->
        viewModel.updateSettings { it.copy(backgroundAudio = v) }
    }

    SectionLabel("Décodage (pris en compte à la prochaine ouverture)")
    DecoderMode.entries.forEach { mode ->
        ChoiceRow(selected = settings.decoderMode == mode, label = PlayerLabels.decoderMode(mode)) { viewModel.updateSettings { it.copy(decoderMode = mode) } }
    }
    PassthroughMode.entries.forEach { mode ->
        ChoiceRow(selected = settings.passthrough == mode, label = "Passthrough : " + PlayerLabels.passthrough(mode)) { viewModel.updateSettings { it.copy(passthrough = mode) } }
    }
    SwitchRow("Tunneling", settings.tunneling, "Peut améliorer la synchro sur certains téléviseurs ; à désactiver en cas d'image figée") { v ->
        viewModel.updateSettings { it.copy(tunneling = v) }
    }
}
