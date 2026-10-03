package com.lecteur.feature.cast.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lecteur.core.common.cast.compat.CastVerdict
import com.lecteur.core.common.cast.protocol.PairingCode
import com.lecteur.feature.cast.discovery.DiscoveredReceiver

/**
 * "Diffuser" for one library file: the TVs running the companion app (full playback, every format) first, then
 * Chromecast devices (limited formats, explained when refused).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastSheet(mediaFileId: Long, onDismiss: () -> Unit, onOpenRemote: () -> Unit, viewModel: CastViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<DiscoveredReceiver?>(null) }

    LaunchedEffect(mediaFileId) { viewModel.load(mediaFileId) }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                CastEvent.OpenRemote -> onOpenRemote()
                CastEvent.Done -> onDismiss()
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Diffuser sur un écran", style = MaterialTheme.typography.titleLarge)

            Section("TV avec Lecteur Média (tous les formats)")
            if (ui.receivers.isEmpty()) {
                Text(
                    "Aucune TV trouvée. Ouvrez l'application Lecteur Média sur votre TV Android, connectée au même Wi-Fi.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            ui.receivers.forEach { receiver ->
                DeviceRow(receiver.deviceName, Icons.Rounded.Tv) { pending = receiver }
            }

            if (ui.chromecastAvailable) {
                Section("Chromecast")
                ChromecastVerdictText(ui.chromecastVerdict)
                if (ui.chromecasts.isEmpty()) {
                    Text("Aucun Chromecast trouvé sur ce réseau.", style = MaterialTheme.typography.bodyMedium)
                }
                ui.chromecasts.forEach { route ->
                    DeviceRow(route.name + if (route.isSelected) " (connecté)" else "", Icons.Rounded.Cast) {
                        viewModel.selectChromecast(route)
                    }
                }
                val compatible = ui.chromecastVerdict is CastVerdict.Compatible
                if (ui.chromecasts.any { it.isSelected } || !compatible) {
                    Button(onClick = viewModel::castToChromecast, enabled = !ui.working) { Text("Diffuser sur le Chromecast") }
                }
            }

            if (ui.working) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            ui.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        }
    }

    pending?.let { receiver ->
        PairingDialog(
            receiverName = receiver.deviceName,
            onDismiss = { pending = null },
            onConfirm = { code ->
                pending = null
                viewModel.castToReceiver(receiver, code)
            }
        )
    }
}

@Composable
private fun Section(title: String) = Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

@Composable
private fun DeviceRow(name: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(icon, contentDescription = null)
        Text(name, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ChromecastVerdictText(verdict: CastVerdict?) {
    when (verdict) {
        is CastVerdict.Incompatible -> Text(verdict.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        is CastVerdict.Compatible -> {
            Text("Ce fichier est compatible Chromecast.", style = MaterialTheme.typography.bodyMedium)
            verdict.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        null -> Unit
    }
}

@Composable
private fun PairingDialog(receiverName: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connexion à $receiverName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Saisissez le code à ${PairingCode.LENGTH} chiffres affiché sur la TV.")
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(PairingCode.LENGTH) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.width(200.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(code) }, enabled = code.length == PairingCode.LENGTH) { Text("Connecter") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}
