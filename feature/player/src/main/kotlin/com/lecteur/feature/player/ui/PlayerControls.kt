package com.lecteur.feature.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lecteur.core.player.engine.PlaybackStatus
import com.lecteur.core.player.engine.PlayerState
import com.lecteur.core.player.engine.Progress
import com.lecteur.core.player.sleep.SleepTimerState
import com.lecteur.feature.player.QueueUi
import com.lecteur.feature.player.TimeFormat

enum class PlayerSheet { TRACKS, SUBTITLE_STYLE, SPEED, DISPLAY, CHAPTERS, SLEEP, INFO, SETTINGS, QUEUE }

/** Everything the control bars can ask for; the screen decides what each one does. */
class ControlActions(
    val back: () -> Unit,
    val togglePlayPause: () -> Unit,
    val seekBy: (Long) -> Unit,
    val seekTo: (Long) -> Unit,
    val previousChapter: () -> Unit,
    val nextChapter: () -> Unit,
    val previousItem: () -> Unit,
    val nextItem: () -> Unit,
    val openSheet: (PlayerSheet) -> Unit,
    val lock: () -> Unit,
    val enterPip: () -> Unit,
    val toggleRotation: () -> Unit,
    val interaction: () -> Unit
)

@Composable
fun PlayerControls(
    visible: Boolean,
    title: String?,
    state: PlayerState,
    progress: Progress,
    seekStepSeconds: Int,
    hasChapters: Boolean,
    sleepTimer: SleepTimerState,
    canEnterPip: Boolean,
    queue: QueueUi?,
    actions: ControlActions,
    modifier: Modifier = Modifier
) {
    if (!visible) return

    Box(modifier.fillMaxSize()) {
        // Scrims keep the white icons readable over bright pictures
        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) { TopBar(title, canEnterPip, queue, actions) }

        CenterControls(state, seekStepSeconds, hasChapters, actions, Modifier.align(Alignment.Center))

        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            SeekBar(progress, actions)
            BottomButtons(state, hasChapters, sleepTimer, queue, actions)
        }
    }
}

@Composable
private fun TopBar(title: String?, canEnterPip: Boolean, queue: QueueUi?, actions: ControlActions) {
    Column {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = actions.back) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Retour", tint = Color.White)
        }
        Box(Modifier.weight(1f))
        if (queue != null && !queue.isSingle) {
            IconButton(onClick = actions.previousItem, enabled = queue.hasPrevious) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Fichier précédent", tint = Color.White.copy(alpha = if (queue.hasPrevious) 1f else 0.4f))
            }
            IconButton(onClick = actions.nextItem, enabled = queue.hasNext) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Fichier suivant", tint = Color.White.copy(alpha = if (queue.hasNext) 1f else 0.4f))
            }
        }
        IconButton(onClick = actions.toggleRotation) {
            Icon(Icons.Rounded.ScreenRotation, contentDescription = "Verrouiller la rotation", tint = Color.White)
        }
        IconButton(onClick = actions.lock) {
            Icon(Icons.Rounded.Lock, contentDescription = "Verrouiller l'écran tactile", tint = Color.White)
        }
        if (canEnterPip) {
            IconButton(onClick = actions.enterPip) {
                Icon(Icons.Rounded.PictureInPictureAlt, contentDescription = "Image dans l'image", tint = Color.White)
            }
        }
        IconButton(onClick = { actions.openSheet(PlayerSheet.INFO) }) {
            Icon(Icons.Rounded.Info, contentDescription = "Informations techniques", tint = Color.White)
        }
        IconButton(onClick = { actions.openSheet(PlayerSheet.SETTINGS) }) {
            Icon(Icons.Rounded.Settings, contentDescription = "Réglages du lecteur", tint = Color.White)
        }
    }
    // The title gets its own line: eight icons leave it no room beside them on a phone held upright
    val heading = listOfNotNull(title, queue?.takeUnless { it.isSingle }?.let { "(${it.currentIndex + 1}/${it.size})" }).joinToString(" ")
    if (heading.isNotEmpty()) {
        Text(heading, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
    }
    }
}

@Composable
private fun CenterControls(
    state: PlayerState,
    seekStepSeconds: Int,
    hasChapters: Boolean,
    actions: ControlActions,
    modifier: Modifier
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        if (hasChapters) {
            IconButton(onClick = actions.previousChapter) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Chapitre précédent", tint = Color.White, modifier = Modifier.size(36.dp))
            }
        }
        IconButton(onClick = { actions.seekBy(-seekStepSeconds * 1000L) }, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Rounded.Replay10, contentDescription = "Reculer de $seekStepSeconds secondes", tint = Color.White, modifier = Modifier.size(40.dp))
        }
        IconButton(onClick = actions.togglePlayPause, modifier = Modifier.size(84.dp)) {
            val icon: ImageVector = when {
                state.status == PlaybackStatus.ENDED -> Icons.Rounded.Replay
                state.playWhenReady -> Icons.Rounded.PauseCircle
                else -> Icons.Rounded.PlayCircle
            }
            Icon(
                icon,
                contentDescription = if (state.playWhenReady) "Pause" else "Lecture",
                tint = Color.White,
                modifier = Modifier.size(76.dp)
            )
        }
        IconButton(onClick = { actions.seekBy(seekStepSeconds * 1000L) }, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Rounded.Forward10, contentDescription = "Avancer de $seekStepSeconds secondes", tint = Color.White, modifier = Modifier.size(40.dp))
        }
        if (hasChapters) {
            IconButton(onClick = actions.nextChapter) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Chapitre suivant", tint = Color.White, modifier = Modifier.size(36.dp))
            }
        }
    }
}

@Composable
private fun SeekBar(progress: Progress, actions: ControlActions) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val duration = progress.durationMs
    val fraction = if (duration > 0) (progress.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shownPosition = dragFraction?.let { (it * duration).toLong() } ?: progress.positionMs

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(TimeFormat.clock(shownPosition), color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(60.dp))
        Slider(
            value = dragFraction ?: fraction,
            onValueChange = {
                dragFraction = it
                actions.interaction()
            },
            onValueChangeFinished = {
                dragFraction?.let { actions.seekTo((it * duration).toLong()) }
                dragFraction = null
            },
            enabled = duration > 0,
            modifier = Modifier.weight(1f)
        )
        Text(
            "-" + TimeFormat.clock((duration - shownPosition).coerceAtLeast(0)),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(68.dp)
        )
    }
}

@Composable
private fun BottomButtons(state: PlayerState, hasChapters: Boolean, sleepTimer: SleepTimerState, queue: QueueUi?, actions: ControlActions) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        BarButton(Icons.Rounded.Subtitles, "Pistes") { actions.openSheet(PlayerSheet.TRACKS) }
        BarButton(Icons.Rounded.Speed, if (state.speed == 1f) "Vitesse" else TimeFormat.speed(state.speed)) { actions.openSheet(PlayerSheet.SPEED) }
        BarButton(Icons.Rounded.Fullscreen, "Affichage") { actions.openSheet(PlayerSheet.DISPLAY) }
        if (hasChapters) BarButton(Icons.AutoMirrored.Rounded.ViewList, "Chapitres") { actions.openSheet(PlayerSheet.CHAPTERS) }
        if (queue != null && !queue.isSingle) BarButton(Icons.AutoMirrored.Rounded.PlaylistPlay, "File") { actions.openSheet(PlayerSheet.QUEUE) }
        BarButton(
            Icons.Rounded.Bedtime,
            when (sleepTimer) {
                is SleepTimerState.Counting -> TimeFormat.clock(sleepTimer.remainingMs)
                SleepTimerState.UntilEndOfMedia -> "Fin"
                SleepTimerState.Off -> "Minuteur"
            }
        ) { actions.openSheet(PlayerSheet.SLEEP) }
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = Color.White)
            Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/** Shown instead of the controls when the touch screen is locked. */
@Composable
fun UnlockButton(visible: Boolean, onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    if (!visible) return
    IconButton(
        onClick = onUnlock,
        modifier = modifier
            .statusBarsPadding()
            .padding(16.dp)
            .background(Color.Black.copy(alpha = 0.6f), androidx.compose.foundation.shape.CircleShape)
    ) {
        Icon(Icons.Rounded.LockOpen, contentDescription = "Déverrouiller", tint = Color.White)
    }
}
