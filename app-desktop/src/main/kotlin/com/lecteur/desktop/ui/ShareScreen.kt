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
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lecteur.desktop.background.Autostart
import com.lecteur.desktop.data.SharedDevice
import com.lecteur.desktop.transcode.TranscoderState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Library sharing: turn it on, pair a phone or a TV with a one-time code, see and revoke the devices allowed in.
 */
@Composable
fun ShareScreen() {
    val a = app()
    val ui by a.services.share.ui.collectAsState()
    val settings by a.services.settings.state.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 40.dp)) {
        ScreenTitle("Partage de la bibliothèque", "Les téléphones et TV de la maison parcourent et lisent les films et séries de ce PC")
        Column(Modifier.padding(horizontal = 32.dp).widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Devices, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Partager avec mes appareils", style = MaterialTheme.typography.titleLarge)
                            Text(
                                if (ui.running) "Actif : « ${settings.receiverName} » est visible sur le réseau local" else ui.error ?: "Désactivé : aucun appareil ne peut lire la bibliothèque",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(settings.shareEnabled, { on ->
                            // A server is useful only if it is there when the TV is: the first time, it starts with Windows
                            val offer = on && !settings.autostartOffered && Autostart.available
                            a.services.settings.update { it.copy(shareEnabled = on, autostartOffered = it.autostartOffered || offer) }
                            if (on) a.services.share.start() else a.services.share.stop()
                            if (offer && !a.startWithWindows) {
                                a.changeStartWithWindows(true)
                                if (a.startWithWindows) a.say("Le partage démarrera aussi avec Windows, en arrière-plan")
                            }
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Démarrer avec Windows", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (Autostart.available) "Comme Plex ou Jellyfin : la bibliothèque reste disponible sans ouvrir l'application (icône près de l'horloge)"
                                else "Disponible dans la version installée",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(a.startWithWindows, a::changeStartWithWindows, enabled = Autostart.available)
                    }
                    if (ui.running) {
                        Text(
                            "Adresse à saisir si l'appareil ne trouve pas le PC : ${ui.addresses.joinToString { "$it:${ui.port}" }.ifEmpty { "aucun réseau" }}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { a.services.share.openPairing() }) { Text("Associer un appareil") }
                        }
                    }
                }
            }

            val transcoder by a.services.transcoder.state.collectAsState()
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Versions réduites (transcodage)", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                            Text(
                                when (val t = transcoder) {
                                    TranscoderState.Detecting -> "Recherche de l'encodeur…"
                                    is TranscoderState.Ready -> "Encodeur : ${t.encoder.label}${if (t.encoder.hardware) " (carte graphique)" else ""}"
                                    TranscoderState.NoFfmpeg -> "Indisponible : ffmpeg est absent de cette installation"
                                    TranscoderState.NoEncoder -> "Indisponible : ffmpeg ne peut encoder sur ce PC"
                                },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(settings.transcodingEnabled, { on -> a.services.settings.update { it.copy(transcodingEnabled = on) } }, enabled = transcoder is TranscoderState.Ready)
                    }
                    Text(
                        "Les appareils peuvent demander un film en 1080p, 720p, 480p ou 360p : pour regarder sur un réseau lent, ou garder une copie plus légère sur le téléphone. Ce PC convertit la vidéo pendant la lecture (H.264, HDR converti en SDR).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Appareils autorisés", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    if (settings.sharedDevices.isEmpty()) {
                        Text("Aucun appareil pour l'instant.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    settings.sharedDevices.forEachIndexed { i, device ->
                        if (i > 0) HorizontalDivider()
                        DeviceRow(device, a.services.share.lastSeen(device), onRevoke = {
                            a.dialog = AppDialog.Confirm(
                                "Retirer « ${device.name} » ?",
                                "Cet appareil ne pourra plus parcourir ni lire la bibliothèque, même avec un lien déjà ouvert. Il faudra l'associer à nouveau.",
                                "Retirer"
                            ) { a.services.share.revoke(device.id) }
                        })
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Shield, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Sécurité", style = MaterialTheme.typography.titleMedium)
                    }
                    listOf(
                        "Seuls les appareils du réseau local reçoivent une réponse ; rien n'est accessible depuis Internet.",
                        "Un appareil ne s'associe qu'avec le code affiché ici, valable 5 minutes, une seule fois ; 5 codes faux le désactivent.",
                        "Ensuite, chaque requête est signée avec une clé propre à l'appareil, qui ne circule plus sur le réseau.",
                        "Les liens de lecture ne donnent accès qu'au fichier demandé, et cessent de fonctionner dès que l'appareil est retiré.",
                        "Les appareils lisent les fichiers et l'état de visionnage ; ils ne peuvent ni modifier ni supprimer la bibliothèque."
                    ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }

    if (ui.pairingCode != null || ui.justPaired != null) PairingDialog(ui.pairingCode, ui.pairingRemainingS, ui.justPaired, settings.receiverName) { a.services.share.closePairing() }
}

@Composable
private fun DeviceRow(device: SharedDevice, lastSeen: Long, onRevoke: () -> Unit) {
    val format = SimpleDateFormat("d MMM yyyy 'à' HH:mm", Locale.FRENCH)
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (device.platform == "tv") Icons.Filled.Tv else Icons.Filled.PhoneAndroid, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(device.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "Associé le ${format.format(Date(device.pairedAt))}" + (if (lastSeen > device.pairedAt) " · vu le ${format.format(Date(lastSeen))}" else ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onRevoke) { Text("Retirer") }
    }
}

@Composable
private fun PairingDialog(code: String?, remainingS: Int, paired: String?, pcName: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (paired != null) "Appareil associé" else "Associer un appareil") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    paired != null -> Text("« $paired » peut maintenant parcourir et lire la bibliothèque.")
                    code != null -> {
                        Text("Sur le téléphone ou la TV : « Bibliothèques partagées » → « $pcName », puis saisissez ce code :")
                        Text(
                            code.chunked(3).joinToString(" "), fontSize = 52.sp, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary, letterSpacing = 6.sp
                        )
                        Text("Valable encore ${remainingS / 60}:${"%02d".format(remainingS % 60)}, pour un seul appareil.", style = MaterialTheme.typography.bodySmall)
                    }
                    else -> Text("Le code a expiré.")
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(if (paired != null) "OK" else "Fermer") } }
    )
}
