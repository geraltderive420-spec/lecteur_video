package com.lecteur.feature.cast.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lecteur.feature.cast.controller.ConnectionState

/** Shown on every screen while the phone is linked to a TV: one tap goes back to the remote. */
@Composable
fun MiniRemote(onOpen: () -> Unit, modifier: Modifier = Modifier, viewModel: RemoteViewModel = hiltViewModel()) {
    val session = viewModel.session
    val ui by session.ui.collectAsStateWithLifecycle()
    val connection = ui.connection as? ConnectionState.Connected ?: return
    val playback = ui.playback

    Surface(modifier.fillMaxWidth().clickable(onClick = onOpen), color = MaterialTheme.colorScheme.secondaryContainer, tonalElevation = 3.dp) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Rounded.Tv, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(playback.title ?: "Connecté à ${connection.receiverName}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Télécommande · ${connection.receiverName}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            if (playback.title != null) {
                IconButton(onClick = { if (playback.isPlaying) session.pause() else session.play() }) {
                    Icon(
                        if (playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (playback.isPlaying) "Pause" else "Lecture"
                    )
                }
            }
        }
    }
}
