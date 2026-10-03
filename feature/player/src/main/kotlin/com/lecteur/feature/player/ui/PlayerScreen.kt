package com.lecteur.feature.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.lecteur.core.player.display.ZoomState
import com.lecteur.core.player.engine.Media3PlayerEngine
import com.lecteur.core.player.engine.PlaybackStatus
import com.lecteur.core.player.queue.UpNext
import com.lecteur.feature.player.PlayerPhase
import com.lecteur.feature.player.PlayerViewModel
import com.lecteur.feature.player.TimeFormat
import com.lecteur.feature.player.gesture.GestureOverlayView
import kotlinx.coroutines.delay

@UnstableApi
@Composable
fun PlayerScreen(viewModel: PlayerViewModel, host: PlayerHostActions, isInPip: Boolean) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val engine by viewModel.engine.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val phase = ui.phase
        when (phase) {
            PlayerPhase.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            is PlayerPhase.Failed -> FailedContent(
                phase = phase,
                onRetry = viewModel::retry,
                onSoftware = viewModel::retryWithSoftwareDecoding,
                onClose = host.close,
                modifier = Modifier.align(Alignment.Center)
            )
            is PlayerPhase.ResumePrompt, PlayerPhase.Playing -> engine?.let { PlayerContent(it, viewModel, host, isInPip) }
        }

        if (phase is PlayerPhase.ResumePrompt) {
            AlertDialog(
                onDismissRequest = host.close,
                title = { Text("Reprendre la lecture ?") },
                text = { Text("Vous vous étiez arrêté à ${TimeFormat.clock(phase.positionMs)}.") },
                confirmButton = { Button(onClick = viewModel::resumeFromPrompt) { Text("Reprendre à ${TimeFormat.clock(phase.positionMs)}") } },
                dismissButton = { TextButton(onClick = viewModel::startOver) { Text("Recommencer") } }
            )
        }
    }
}

@UnstableApi
@Composable
private fun PlayerContent(engine: Media3PlayerEngine, viewModel: PlayerViewModel, host: PlayerHostActions, isInPip: Boolean) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val state by engine.state.collectAsStateWithLifecycle()
    val progress by engine.progress.collectAsStateWithLifecycle()

    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableLongStateOf(0L) }
    var unlockVisible by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var zoom by remember { mutableStateOf(ZoomState()) }
    var viewSize by remember { mutableStateOf(1f to 1f) }
    val hud = remember { HudState() }

    val latestSettings by rememberUpdatedState(settings)
    val latestZoom by rememberUpdatedState(zoom)
    val latestUi by rememberUpdatedState(ui)

    // Back to the unzoomed picture whenever the display mode changes
    LaunchedEffect(ui.displayMode) { zoom = ZoomState() }

    // Controls hide by themselves while playing
    LaunchedEffect(controlsVisible, state.isPlaying, interaction, sheet) {
        if (controlsVisible && state.isPlaying && sheet == null) {
            delay(4_000)
            controlsVisible = false
        }
    }
    LaunchedEffect(state.status) { if (state.status == PlaybackStatus.ENDED) controlsVisible = true }
    LaunchedEffect(unlockVisible, interaction) {
        if (unlockVisible) {
            delay(3_000)
            unlockVisible = false
        }
    }

    val controller = remember(engine) {
        PlayerGestureController(
            viewModel = viewModel,
            host = host,
            hud = hud,
            settings = { latestSettings },
            engine = { engine },
            viewSize = { viewSize },
            zoom = { latestZoom },
            setZoom = { zoom = it },
            onSingleTapped = {
                if (latestUi.isLocked) unlockVisible = true else controlsVisible = !controlsVisible
                interaction++
            }
        )
    }

    BackHandler {
        when {
            sheet != null -> sheet = null
            ui.isLocked -> unlockVisible = true
            else -> host.close()
        }
    }

    Box(Modifier.fillMaxSize().onSizeChanged { viewSize = it.width.toFloat() to it.height.toFloat() }) {
        VideoLayer(engine, state.videoSize, ui.displayMode, zoom, settings.subtitleStyle)

        AndroidView(
            factory = { GestureOverlayView(it) },
            update = {
                it.listener = controller
                it.gesturesEnabled = settings.gesturesEnabled && !ui.isLocked
                it.isZoomed = zoom.scale > 1f
            },
            modifier = Modifier.fillMaxSize()
        )

        if (state.status == PlaybackStatus.BUFFERING) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(48.dp), color = Color.White)
        }

        HudOverlay(hud, settings.volumeBoostEnabled)

        if (!isInPip) {
            val actions = remember(engine) {
                ControlActions(
                    back = host.close,
                    togglePlayPause = viewModel::togglePlayPause,
                    seekBy = viewModel::seekBy,
                    seekTo = viewModel::seekTo,
                    previousChapter = { latestUi.chapters.previousStart(engine.progress.value.positionMs)?.let(viewModel::seekTo) },
                    nextChapter = { latestUi.chapters.nextStart(engine.progress.value.positionMs)?.let(viewModel::seekTo) },
                    previousItem = viewModel::playPrevious,
                    nextItem = viewModel::playNext,
                    openSheet = { sheet = it },
                    lock = {
                        viewModel.setLocked(true)
                        controlsVisible = false
                    },
                    enterPip = host.enterPictureInPicture,
                    toggleRotation = host.toggleRotationLock,
                    interaction = { interaction++ }
                )
            }
            PlayerControls(
                visible = controlsVisible && !ui.isLocked,
                title = ui.title,
                state = state,
                progress = progress,
                seekStepSeconds = settings.seekStepSeconds,
                hasChapters = !ui.chapters.isEmpty,
                sleepTimer = ui.sleepTimer,
                canEnterPip = host.canEnterPictureInPicture,
                queue = ui.queue,
                actions = actions
            )

            UnlockButton(
                visible = ui.isLocked && unlockVisible,
                onUnlock = {
                    viewModel.setLocked(false)
                    unlockVisible = false
                    controlsVisible = true
                },
                modifier = Modifier.align(Alignment.TopStart)
            )

            val skipTarget = if (ui.isLocked) null else ui.chapters.introSkipTarget(progress.positionMs)
            if (skipTarget != null) {
                FilledTonalButton(
                    onClick = { viewModel.seekTo(skipTarget) },
                    modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 24.dp, bottom = if (controlsVisible) 150.dp else 32.dp)
                ) { Text("Passer le générique") }
            }

            val upNextTitle = ui.queue?.upNextTitle
            if (upNextTitle != null && !ui.isLocked && !ui.upNextCancelled && ui.phase == PlayerPhase.Playing) {
                val prompt = UpNext.prompt(
                    positionMs = progress.positionMs,
                    durationMs = progress.durationMs,
                    speed = state.speed,
                    countdownSeconds = if (settings.autoPlayNext) settings.nextCountdownSeconds else 0,
                    creditsStartMs = ui.chapters.creditsStart(progress.durationMs)
                )
                if (prompt != null) {
                    UpNextCard(
                        title = upNextTitle,
                        secondsLeft = prompt.secondsLeft?.takeIf { settings.autoPlayNext },
                        onPlayNow = viewModel::playNext,
                        onCancel = viewModel::cancelUpNext,
                        modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 24.dp, bottom = if (controlsVisible) 150.dp else 32.dp)
                    )
                }
            }

            PlayerSheetHost(
                sheet = sheet,
                onDismiss = { sheet = null },
                onSelectSheet = { sheet = it },
                viewModel = viewModel,
                state = state,
                progress = progress,
                ui = ui,
                settings = settings,
                host = host
            )
        }
    }
}

/** "Épisode suivant" with its countdown: [secondsLeft] is null when nothing starts by itself. */
@Composable
private fun UpNextCard(title: String, secondsLeft: Int?, onPlayNow: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(12.dp))
            .padding(16.dp)
            .widthIn(max = 320.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            if (secondsLeft != null) "Suivant dans $secondsLeft s" else "Suivant",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelLarge
        )
        Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPlayNow) { Text("Lire maintenant") }
            if (secondsLeft != null) TextButton(onClick = onCancel) { Text("Annuler") }
        }
    }
}

@Composable
private fun FailedContent(
    phase: PlayerPhase.Failed,
    onRetry: () -> Unit,
    onSoftware: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(56.dp))
        Text(phase.error.message, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        phase.error.suggestion?.let {
            Text(it, color = Color.White.copy(alpha = 0.75f), textAlign = TextAlign.Center)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = onRetry) { Text("Réessayer") }
            if (phase.canRetryWithSoftware) {
                FilledTonalButton(onClick = onSoftware) { Text("Décodage logiciel") }
            }
            OutlinedButton(onClick = onClose) { Text("Fermer") }
        }
    }
}
