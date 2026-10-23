package com.lecteur.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lecteur.desktop.cast.ConnectionState
import com.lecteur.desktop.cast.DiscoveredReceiver
import com.lecteur.desktop.cast.LocalAddress
import kotlinx.coroutines.launch

@Composable
fun CastScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("Diffusion", "Recevoir depuis le téléphone, ou piloter une TV depuis ce PC")
        Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f)) { ReceiverCard() }
            Column(Modifier.weight(1f)) { SenderCard() }
        }
        Spacer(Modifier.height(32.dp))
    }
}

/** The PC as a screen for the phone, like the Android TV app. */
@Composable
private fun ReceiverCard() {
    val a = app()
    val ui by a.services.receiver.ui.collectAsState()
    val settings by a.services.settings.state.collectAsState()
    var name by remember(settings.receiverName) { mutableStateOf(settings.receiverName) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PhoneAndroid, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(12.dp))
                Text("Recevoir depuis le téléphone", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Switch(settings.receiverEnabled, { on ->
                    a.services.settings.update { it.copy(receiverEnabled = on) }
                    if (on) a.services.receiver.start() else a.services.receiver.stop()
                })
            }
            Spacer(Modifier.height(12.dp))
            if (ui.running) {
                Text("Sur le téléphone : fiche d'un film → « Diffuser sur un écran » → « ${ui.deviceName} », puis saisissez ce code :", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(14.dp))
                Text(
                    ui.pairingCode.chunked(3).joinToString(" "), fontSize = 56.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary, letterSpacing = 6.sp
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    ui.controllerName?.let { "Connecté : $it" } ?: "En attente d'un téléphone…",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (ui.controllerName != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Adresse : ${LocalAddress.candidates().joinToString { it.hostAddress }} · port ${ui.port}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(name, { name = it.take(40) }, singleLine = true, label = { Text("Nom de ce PC sur le réseau") }, modifier = Modifier.weight(1f))
                    TextButton(enabled = name.isNotBlank() && name != settings.receiverName, onClick = {
                        a.services.settings.update { it.copy(receiverName = name.trim()) }
                        a.services.receiver.restart()
                        // The shared library is announced under the same name
                        if (a.services.share.ui.value.running) a.services.share.restart()
                    }) { Text("Renommer") }
                }
                TextButton(onClick = { a.services.receiver.newPairingCode() }) { Text("Générer un nouveau code (déconnecte les téléphones appairés)") }
            } else {
                Text(ui.error ?: "Réception désactivée : le téléphone ne verra pas ce PC.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Le PC lit le fichier du téléphone avec son propre moteur (MKV, DTS, TrueHD, HDR…) ; le téléphone sert de télécommande et garde la progression. " +
                    "Si le téléphone ne trouve pas le PC : même Wi-Fi, réseau « Privé » dans Windows, et Lecteur Média autorisé dans le pare-feu.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** This PC driving a TV: pick it, then the remote control. */
@Composable
private fun SenderCard() {
    val a = app()
    val receivers by a.services.mdns.receivers.collectAsState()
    val remote by a.services.remote.ui.collectAsState()
    var pairing by remember { mutableStateOf<DiscoveredReceiver?>(null) }
    var code by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        a.services.mdns.startBrowsing()
        a.services.mdns.refresh()
        onDispose { }
    }
    androidx.compose.runtime.LaunchedEffect(remote.notice) { remote.notice?.let { a.say(it); a.services.remote.consumeNotice() } }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (remote.connection is ConnectionState.Connected) Icons.Filled.CastConnected else Icons.Filled.Cast, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(12.dp))
                Text("Diffuser vers un écran", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { a.services.mdns.refresh() }) { Text("Actualiser") }
            }
            Spacer(Modifier.height(12.dp))
            when (val c = remote.connection) {
                is ConnectionState.Connected -> RemoteControl(c.receiverName)
                else -> {
                    if (c is ConnectionState.Failed) Text(c.message, color = MaterialTheme.colorScheme.tertiary)
                    if (c is ConnectionState.Connecting) Text("Connexion à ${c.receiverName}…")
                    if (receivers.isEmpty()) Text("Aucun écran trouvé. Ouvrez Lecteur Média sur la TV (au premier plan) ou sur un autre PC du même réseau.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    receivers.forEach { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.deviceName, style = MaterialTheme.typography.titleMedium)
                                Text(r.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Button(onClick = {
                                val saved = a.services.remote.savedCode(r)
                                if (saved != null) scope.launch { if (!a.services.remote.connect(r, saved)) { pairing = r; code = "" } } else { pairing = r; code = "" }
                            }) { Text("Connecter") }
                        }
                    }
                    pairing?.let { r ->
                        Spacer(Modifier.height(12.dp))
                        Text("Code affiché sur « ${r.deviceName} » :")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, singleLine = true, modifier = Modifier.width(180.dp))
                            Spacer(Modifier.width(8.dp))
                            Button(enabled = code.length == 6, onClick = { scope.launch { if (a.services.remote.connect(r, code)) pairing = null } }) { Text("Valider") }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Ensuite, depuis la fiche d'un film ou un clic droit sur une affiche : « Diffuser sur un écran ».", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun RemoteControl(receiverName: String) {
    val a = app()
    val remote by a.services.remote.ui.collectAsState()
    val p = remote.playback
    var seeking by remember { mutableStateOf<Float?>(null) }
    Column(Modifier.widthIn(max = 640.dp)) {
        Text("Connecté à $receiverName", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Text(p.title ?: "Rien en lecture", style = MaterialTheme.typography.headlineSmall)
        p.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
        Spacer(Modifier.height(8.dp))
        val duration = remote.durationMs.coerceAtLeast(1)
        Slider(
            value = seeking ?: (remote.positionMs.toFloat() / duration).coerceIn(0f, 1f),
            onValueChange = { seeking = it },
            onValueChangeFinished = { seeking?.let { a.services.remote.seekTo((it * duration).toLong()) }; seeking = null }
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(seeking?.let { (it * duration).toLong() } ?: remote.positionMs), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text(formatTime(remote.durationMs), style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { a.services.remote.seekBy(-10_000) }) { Icon(Icons.Filled.Replay10, "−10 s", Modifier.size(32.dp)) }
            Spacer(Modifier.width(12.dp))
            FilledIconButton(onClick = { if (p.isPlaying) a.services.remote.pause() else a.services.remote.play() }, modifier = Modifier.size(60.dp)) {
                Icon(if (p.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Lecture", Modifier.size(36.dp))
            }
            Spacer(Modifier.width(12.dp))
            IconButton(onClick = { a.services.remote.seekBy(30_000) }) { Icon(Icons.Filled.Forward10, "+30 s", Modifier.size(32.dp)) }
            Spacer(Modifier.width(24.dp))
            IconButton(onClick = { a.services.remote.stopPlayback() }) { Icon(Icons.Filled.Stop, "Arrêter") }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { a.services.remote.setMuted(!p.muted) }) { Icon(if (p.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, "Muet") }
            var volume by remember(p.volume) { mutableStateOf(p.volume) }
            Slider(volume, { volume = it }, onValueChangeFinished = { a.services.remote.setVolume(volume) }, modifier = Modifier.width(220.dp))
        }
        if (p.audio.size > 1) {
            Text("Audio", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                p.audio.forEach { t -> FilterChip(t.isSelected, { a.services.remote.selectAudio(t.index) }, { Text(listOfNotNull(t.label ?: t.language, t.codec).joinToString(" · ")) }) }
            }
        }
        if (p.subtitles.isNotEmpty()) {
            Text("Sous-titres", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(p.subtitles.none { it.isSelected }, { a.services.remote.selectSubtitle(-1) }, { Text("Aucun") })
                p.subtitles.forEach { t -> FilterChip(t.isSelected, { a.services.remote.selectSubtitle(t.index) }, { Text(t.label ?: t.language ?: "Piste ${t.index + 1}") }) }
            }
        }
        Text("Vitesse", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s -> FilterChip(p.speed == s, { a.services.remote.setSpeed(s) }, { Text("×$s") }) }
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = { a.services.remote.disconnect() }) { Text("Se déconnecter de $receiverName") }
    }
}
