package com.lecteur.feature.cast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lecteur.core.common.cast.protocol.RemoteStatus
import com.lecteur.core.common.cast.protocol.RemoteTrack
import com.lecteur.feature.cast.controller.ConnectionState
import com.lecteur.feature.cast.controller.RemoteSession
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class RemoteViewModel @Inject constructor(val session: RemoteSession) : ViewModel()

/** The phone as a remote control for the TV: transport, seek bar, tracks, speed, volume. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(onBack: () -> Unit, viewModel: RemoteViewModel = hiltViewModel()) {
    val session = viewModel.session
    val ui by session.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ui.notice) {
        ui.notice?.let {
            snackbar.showSnackbar(it)
            session.consumeNotice()
        }
    }
    // The link is gone (TV off, Wi-Fi lost, user disconnected): nothing left to control here.
    LaunchedEffect(ui.connection) {
        if (ui.connection !is ConnectionState.Connected) onBack()
    }

    val state = ui.playback
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text((ui.connection as? ConnectionState.Connected)?.receiverName ?: "Télécommande") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Retour") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(state.title ?: "Rien n'est en lecture sur la TV", style = MaterialTheme.typography.headlineSmall)
            if (state.status == RemoteStatus.ERROR) {
                Text(state.errorMessage ?: "Erreur de lecture.", color = MaterialTheme.colorScheme.error)
                state.errorSuggestion?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }

            SeekBar(ui.positionMs, ui.durationMs, onSeek = session::seekTo)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { session.seekBy(-10_000) }) { Icon(Icons.Rounded.Replay10, "Reculer de 10 secondes") }
                FilledIconButton(onClick = { if (state.isPlaying) session.pause() else session.play() }, modifier = Modifier.size(72.dp)) {
                    Icon(
                        if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (state.isPlaying) "Pause" else "Lecture",
                        modifier = Modifier.size(40.dp)
                    )
                }
                IconButton(onClick = { session.seekBy(10_000) }) { Icon(Icons.Rounded.Forward10, "Avancer de 10 secondes") }
                IconButton(onClick = session::stopPlayback) { Icon(Icons.Rounded.Stop, "Arrêter") }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = "Volume de la TV")
                Slider(value = state.volume, onValueChange = session::setVolume, modifier = Modifier.weight(1f).padding(start = 12.dp))
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TrackMenu("Audio", state.audio, offLabel = null, onSelect = session::selectAudio)
                TrackMenu("Sous-titres", state.subtitles, offLabel = "Désactivés") { session.selectSubtitle(it) }
                SpeedMenu(state.speed, session::setSpeed)
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { session.disconnect() }, modifier = Modifier.fillMaxWidth()) { Text("Se déconnecter de la TV") }
        }
    }
}

@Composable
private fun SeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Column {
        Slider(
            value = dragging ?: fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek((it * durationMs).toLong()) }
                dragging = null
            },
            enabled = durationMs > 0
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock(dragging?.let { (it * durationMs).toLong() } ?: positionMs), style = MaterialTheme.typography.bodySmall)
            Text(clock(durationMs), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TrackMenu(label: String, tracks: List<RemoteTrack>, offLabel: String?, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, enabled = tracks.isNotEmpty()) { Text(label) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        offLabel?.let {
            DropdownMenuItem(text = { Text(it) }, onClick = { open = false; onSelect(-1) })
        }
        tracks.forEach { track ->
            DropdownMenuItem(
                text = { Text(trackLabel(track) + if (track.isSelected) "  ✓" else "") },
                enabled = track.isSupported,
                onClick = { open = false; onSelect(track.index) }
            )
        }
    }
}

@Composable
private fun SpeedMenu(current: Float, onSelect: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }) { Text("${current}x") }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
            DropdownMenuItem(text = { Text("${speed}x") }, onClick = { open = false; onSelect(speed) })
        }
    }
}

internal fun trackLabel(track: RemoteTrack): String {
    val language = track.language?.uppercase()
    return listOfNotNull(track.label, language, track.codec?.uppercase()).distinct().joinToString(" · ").ifBlank { "Piste ${track.index + 1}" }
}

internal fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
