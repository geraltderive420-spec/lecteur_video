package com.lecteur.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.lecteur.feature.shared.SharedServersViewModel
import com.lecteur.feature.shared.ui.PairingDialog

/**
 * The PCs whose libraries this TV shows: pair one with the code it displays, forget one, or type its address when
 * multicast discovery is blocked on the network. Their titles appear in the TV's own home, films and series.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvServersScreen(onPaired: () -> Unit, viewModel: SharedServersViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var typing by remember { mutableStateOf(false) }

    LaunchedEffect(ui.openedServerId) {
        if (ui.openedServerId != null) {
            viewModel.consumeOpened()
            onPaired()
        }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Bibliothèques des PC", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Les films et séries des PC associés apparaissent dans l'Accueil, Films et Séries, avec le nom du PC en dessous.",
                    style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f)
                )
            }
        }
        if (ui.paired.isNotEmpty()) item { Text("PC associés", style = MaterialTheme.typography.titleMedium) }
        items(ui.paired, key = { it.server.serverId }) { row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Rounded.Computer, null, tint = if (row.online) SourceColor else Color.Gray, modifier = Modifier.size(28.dp))
                Column(Modifier.width(420.dp)) {
                    Text(row.server.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (row.online) "En ligne" else "Non détecté sur le réseau · ${row.server.baseUrl.removePrefix("http://")}",
                        style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f)
                    )
                }
                OutlinedButton(onClick = { viewModel.forget(row.server.serverId) }) { Text("Oublier") }
            }
        }
        item { Text("Sur le réseau", style = MaterialTheme.typography.titleMedium) }
        if (ui.nearby.isEmpty()) item {
            Text(
                if (ui.paired.isEmpty()) "Aucun PC trouvé. Sur le PC : Lecteur Média → « Partage » → activer le partage. La TV et le PC doivent être sur le même réseau."
                else "Aucun autre PC trouvé.",
                style = MaterialTheme.typography.bodyLarge
            )
        }
        items(ui.nearby, key = { it.serviceName }) { server ->
            Button(onClick = { viewModel.startPairing(server) }) {
                Icon(Icons.Rounded.Computer, null, tint = androidx.tv.material3.LocalContentColor.current, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Associer « ${server.name} »")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = viewModel::rescan) { Text("Rechercher à nouveau") }
                OutlinedButton(onClick = { typing = true }) { Text("Saisir l'adresse du PC") }
            }
        }
        ui.message?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFFFB4AB)) } }
    }

    if (typing) {
        var address by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { typing = false },
            title = { androidx.compose.material3.Text("Adresse du PC") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.Text("Elle est affichée sur le PC, dans « Partage ». Exemple : 192.168.1.20")
                    OutlinedTextField(address, { address = it }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(enabled = address.isNotBlank(), onClick = { typing = false; viewModel.startPairing(address) }) { androidx.compose.material3.Text("Continuer") } },
            dismissButton = { TextButton(onClick = { typing = false }) { androidx.compose.material3.Text("Annuler") } }
        )
    }
    ui.pairing?.let { PairingDialog(it, onSubmit = viewModel::submitCode, onDismiss = viewModel::cancelPairing) }
}
