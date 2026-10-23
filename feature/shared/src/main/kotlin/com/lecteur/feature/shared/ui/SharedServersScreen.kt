package com.lecteur.feature.shared.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lecteur.feature.shared.PairingUi
import com.lecteur.feature.shared.ServerRow
import com.lecteur.feature.shared.SharedServersViewModel

/**
 * "Bibliothèques partagées": the PCs this phone reads, the ones on the network, and pairing with the code shown on the PC.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedServersScreen(
    onOpenServer: (String) -> Unit,
    onBack: (() -> Unit)? = null,
    viewModel: SharedServersViewModel = hiltViewModel()
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var typing by remember { mutableStateOf(false) }

    LaunchedEffect(ui.openedServerId) {
        ui.openedServerId?.let {
            viewModel.consumeOpened()
            onOpenServer(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bibliothèques partagées") },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Retour") } },
                actions = { IconButton(onClick = viewModel::rescan) { Icon(Icons.Rounded.Refresh, "Rechercher à nouveau") } }
            )
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (ui.paired.isNotEmpty()) {
                item { Header("Mes PC") }
                items(ui.paired, key = { it.server.serverId }) { row -> PairedRow(row, onOpen = { onOpenServer(row.server.serverId) }, onForget = { viewModel.forget(row.server.serverId) }) }
            }
            item { Header("Sur le réseau") }
            if (ui.nearby.isEmpty()) {
                item {
                    Text(
                        if (ui.paired.isEmpty()) "Aucun PC trouvé. Sur le PC, ouvrez Lecteur Média → « Partage » et activez le partage. " +
                            "Le téléphone et le PC doivent être sur le même Wi-Fi."
                        else "Aucun autre PC trouvé.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
            items(ui.nearby, key = { it.serviceName }) { server ->
                ListItem(
                    headlineContent = { Text(server.name) },
                    supportingContent = { Text("Appuyez pour associer ce téléphone") },
                    leadingContent = { Icon(Icons.Rounded.Lan, null) },
                    modifier = Modifier.clickable { viewModel.startPairing(server) }
                )
            }
            item {
                OutlinedButton(onClick = { typing = true }, modifier = Modifier.padding(16.dp)) { Text("Saisir l'adresse du PC") }
            }
            ui.message?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) } }
            item { SecurityNote() }
        }
    }

    if (typing) {
        AddressDialog(onDismiss = { typing = false }, onConfirm = { typing = false; viewModel.startPairing(it) })
    }
    ui.pairing?.let { PairingDialog(it, onSubmit = viewModel::submitCode, onDismiss = viewModel::cancelPairing) }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))
}

@Composable
private fun PairedRow(row: ServerRow, onOpen: () -> Unit, onForget: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(row.server.name) },
        supportingContent = { Text(if (row.online) "En ligne" else "Non détecté sur le réseau · ${row.server.baseUrl.removePrefix("http://")}") },
        leadingContent = {
            Icon(Icons.Rounded.Computer, null, tint = if (row.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Options") }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Oublier ce PC") }, onClick = { menu = false; onForget() })
            }
        },
        modifier = Modifier.clickable(onClick = onOpen)
    )
}

@Composable
private fun SecurityNote() {
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Rounded.Shield, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "Le PC ne s'ouvre qu'aux appareils associés avec le code qu'il affiche, et seulement sur le réseau local. " +
                "Vous pouvez retirer un appareil à tout moment depuis le PC.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AddressDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adresse du PC") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Elle est affichée sur le PC, dans « Partage ».", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    text, { text = it }, singleLine = true, placeholder = { Text("192.168.1.20") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text) }) { Text("Continuer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
fun PairingDialog(pairing: PairingUi, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var code by remember(pairing.baseUrl) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Associer « ${pairing.name} »") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Sur le PC : « Partage » → « Associer un appareil ». Saisissez le code à 6 chiffres affiché.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    code, { v -> code = v.filter(Char::isDigit).take(6) }, singleLine = true, enabled = !pairing.busy,
                    textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 6.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = pairing.error != null,
                    modifier = Modifier.fillMaxWidth()
                )
                pairing.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (pairing.busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Connexion…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
        },
        confirmButton = { TextButton(enabled = code.length == 6 && !pairing.busy, onClick = { onSubmit(code) }) { Text("Associer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}
