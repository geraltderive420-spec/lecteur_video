package com.lecteur.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lecteur.core.model.ImageUrls
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.MetadataException
import com.lecteur.core.model.MetadataSearchHit
import com.lecteur.desktop.cast.ConnectionState
import com.lecteur.desktop.cast.DiscoveredReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AppDialogs() {
    val a = app()
    when (val d = a.dialog) {
        null -> Unit
        is AppDialog.AddToList -> AddToListDialog(d)
        is AppDialog.CastTo -> CastDialog(d)
        is AppDialog.FixMatch -> FixMatchDialog(d)
        is AppDialog.Confirm -> AlertDialog(
            onDismissRequest = { a.dialog = null },
            title = { Text(d.title) },
            text = { Text(d.text) },
            confirmButton = { TextButton(onClick = { a.dialog = null; d.action() }) { Text(d.confirm) } },
            dismissButton = { TextButton(onClick = { a.dialog = null }) { Text("Annuler") } }
        )
    }
}

@Composable
private fun AddToListDialog(d: AppDialog.AddToList) {
    val a = app()
    val snapshot by a.services.store.snapshot.collectAsState()
    val member = snapshot.listsOf(d.kind, d.id)
    var creating by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { a.dialog = null },
        title = { Text("Ajouter « ${d.title} » à…") },
        text = {
            Column {
                snapshot.lists.forEach { list ->
                    val on = list.id in member
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                            a.background { a.services.store.setMember(list.id, d.id.takeIf { d.kind == MediaKind.MOVIE }, d.id.takeIf { d.kind == MediaKind.SERIES }, !on) }
                        }.padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(on, null)
                        Spacer(Modifier.width(8.dp))
                        Text(list.name)
                    }
                }
                TextButton(onClick = { creating = true }) { Text("+ Nouvelle liste") }
            }
        },
        confirmButton = { TextButton(onClick = { a.dialog = null }) { Text("Terminé") } }
    )
    if (creating) {
        NameDialog("Nouvelle liste", "", snapshot.lists.map { it.name }, onDismiss = { creating = false }) { name ->
            creating = false
            a.background {
                val id = a.services.store.createList(name)
                a.services.store.setMember(id, d.id.takeIf { d.kind == MediaKind.MOVIE }, d.id.takeIf { d.kind == MediaKind.SERIES }, true)
            }
        }
    }
}

/** Picks a TV (or another PC) on the network, pairs with its code, sends the file. */
@Composable
private fun CastDialog(d: AppDialog.CastTo) {
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
    fun castTo() {
        a.services.remote.cast(d.mediaFileId)
        a.dialog = null
        a.root(Screen.Cast)
    }
    AlertDialog(
        onDismissRequest = { a.dialog = null },
        title = { Text("Diffuser « ${d.title} »") },
        text = {
            Column(Modifier.width(420.dp)) {
                val connected = remote.connection as? ConnectionState.Connected
                if (connected != null) {
                    Text("Connecté à ${connected.receiverName}", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = ::castTo) { Text("Lire sur ${connected.receiverName}") }
                    TextButton(onClick = { a.services.remote.disconnect() }) { Text("Se déconnecter") }
                } else if (pairing != null) {
                    Text("Saisissez le code à 6 chiffres affiché sur « ${pairing!!.deviceName} ».")
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, singleLine = true, label = { Text("Code") })
                    (remote.connection as? ConnectionState.Failed)?.let { Text(it.message, color = MaterialTheme.colorScheme.tertiary) }
                    if (remote.connection is ConnectionState.Connecting) CircularProgressIndicator(Modifier.size(24.dp))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Écrans sur le réseau", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { a.services.mdns.refresh() }) { Icon(Icons.Filled.Refresh, "Actualiser") }
                    }
                    if (receivers.isEmpty()) {
                        Text(
                            "Recherche… Ouvrez Lecteur Média sur la TV (ou sur un autre PC) connectée au même réseau.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    receivers.forEach { r ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                                val saved = a.services.remote.savedCode(r)
                                if (saved != null) {
                                    scope.launch {
                                        if (a.services.remote.connect(r, saved)) castTo() else { pairing = r; code = "" }
                                    }
                                } else {
                                    pairing = r
                                    code = ""
                                }
                            }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Cast, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(r.deviceName, style = MaterialTheme.typography.titleMedium)
                                Text(r.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (pairing != null && remote.connection !is ConnectionState.Connected) {
                TextButton(enabled = code.length == 6 && remote.connection !is ConnectionState.Connecting, onClick = {
                    val target = pairing!!
                    scope.launch { if (a.services.remote.connect(target, code)) castTo() }
                }) { Text("Connecter") }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (pairing != null) pairing = null else a.dialog = null }) { Text(if (pairing != null) "Retour" else "Fermer") }
        }
    )
}

/** Manual identification: search TMDB with what the user types, pick the right title. */
@Composable
private fun FixMatchDialog(d: AppDialog.FixMatch) {
    val a = app()
    var query by remember { mutableStateOf(d.title) }
    var year by remember { mutableStateOf(d.year?.toString().orEmpty()) }
    val kind = d.kind
    var hits by remember { mutableStateOf<List<MetadataSearchHit>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun search() {
        busy = true
        error = null
        scope.launch {
            try {
                hits = withContext(Dispatchers.IO) { a.services.identifier.search(kind, query, year.toIntOrNull()) }
                if (hits.isEmpty()) error = "Aucun résultat sur TMDB."
            } catch (e: MetadataException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(Unit) { search() }

    AlertDialog(
        onDismissRequest = { a.dialog = null },
        title = { Text("Corriger l'identification") },
        text = {
            Column(Modifier.width(560.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Titre") }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, singleLine = true, label = { Text("Année") }, modifier = Modifier.width(100.dp))
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = ::search) { Text("Chercher") }
                }
                if (d.kind == MediaKind.MOVIE) {
                    Text("Recherche parmi les films", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("Recherche parmi les séries", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                if (busy) CircularProgressIndicator(Modifier.size(28.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(hits, key = { it.tmdbId }) { hit ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                                busy = true
                                scope.launch {
                                    try {
                                        val newId = withContext(Dispatchers.IO) { a.services.identifier.applyManual(d.kind, d.id, hit.tmdbId) }
                                        a.dialog = null
                                        a.say("« ${hit.title} » associé")
                                        if (newId != d.id) { a.back(); a.open(d.kind, newId) }
                                    } catch (e: Exception) {
                                        error = e.message
                                        busy = false
                                    }
                                }
                            }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.width(54.dp).height(80.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                RemoteImage(ImageUrls.poster(hit.posterPath), Modifier.fillMaxWidth().height(80.dp)) { Icon(Icons.Filled.Movie, null, Modifier.align(Alignment.Center)) }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(listOfNotNull(hit.title, hit.year?.toString()).joinToString(" · "), style = MaterialTheme.typography.titleMedium)
                                hit.originalTitle?.takeIf { it != hit.title }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                hit.overview?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { a.dialog = null }) { Text("Fermer") } }
    )
}
