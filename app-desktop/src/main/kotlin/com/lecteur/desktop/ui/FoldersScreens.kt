package com.lecteur.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lecteur.core.common.scan.VideoFiles
import com.lecteur.core.common.text.NaturalOrder
import com.lecteur.core.model.Formatters
import com.lecteur.core.model.ImageUrls
import com.lecteur.core.model.LibraryFolder
import com.lecteur.core.model.LibraryItem
import com.lecteur.core.model.MatchState
import com.lecteur.core.model.MediaCategory
import com.lecteur.core.model.MediaKind
import java.io.File
import java.text.DateFormat
import java.util.Date
import javax.swing.JFileChooser

val MediaCategory.label: String
    get() = when (this) {
        MediaCategory.MOVIES -> "Films"
        MediaCategory.SERIES -> "Séries"
        MediaCategory.ANIME -> "Animés"
        MediaCategory.DOCUMENTARIES -> "Documentaires"
        MediaCategory.PERSONAL -> "Vidéos personnelles"
        MediaCategory.GENERIC -> "Mixte (films et séries)"
    }

/** Folder picker: the Windows-style Swing chooser (the AWT one cannot pick folders). */
fun pickFolder(title: String = "Choisir un dossier"): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

fun pickVideoFile(): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Ouvrir une vidéo"
        fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Vidéos", *VideoFiles.VIDEO_EXTENSIONS.toTypedArray())
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

@Composable
fun FoldersScreen() {
    val a = app()
    val snapshot by a.services.store.snapshot.collectAsState()
    val status by a.services.scanner.status.collectAsState()
    var adding by remember { mutableStateOf<File?>(null) }
    val toVerify = snapshot.movies.values.count { it.movie.matchState != MatchState.IDENTIFIED && !it.movie.matchLocked } +
        snapshot.series.values.count { it.series.matchState != MatchState.IDENTIFIED && !it.series.matchLocked }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Dossiers de la bibliothèque", "${snapshot.folders.size} dossier(s) · ${snapshot.movies.size} films · ${snapshot.series.size} séries") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (toVerify > 0) OutlinedButton(onClick = { a.go(Screen.Review) }) { Text("À vérifier ($toVerify)") }
                FilledTonalButton(onClick = { a.services.scanner.start() }, enabled = !status.running && snapshot.folders.isNotEmpty()) {
                    Icon(Icons.Filled.Refresh, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Analyser")
                }
                Button(onClick = { pickFolder()?.let { adding = it } }) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Ajouter un dossier")
                }
            }
        }
        ScanBanner()
        if (snapshot.folders.isEmpty()) {
            EmptyState(Icons.Filled.FolderOpen, "Aucun dossier", "Ajoutez un dossier (disque interne, disque externe, partage réseau monté en lecteur) contenant vos films ou séries.")
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(snapshot.folders, key = { it.id }) { folder ->
                    val count = snapshot.fileIndex.values.count { it.file.folderId == folder.id }
                    FolderCard(folder, count)
                }
                item {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { a.services.scanner.start(force = true) }, enabled = !status.running) { Text("Tout réanalyser (forcer)") }
                        TextButton(onClick = { a.services.scanner.identifyOnly() }, enabled = !status.running) { Text("Relancer l'identification en ligne") }
                    }
                }
            }
        }
    }
    adding?.let { dir ->
        CategoryDialog(dir, onDismiss = { adding = null }) { category ->
            adding = null
            a.background {
                val id = a.services.store.addFolder(dir.absolutePath, category)
                a.services.scanner.start(listOf(id))
            }
        }
    }
}

@Composable
fun ScanBanner() {
    val a = app()
    val status by a.services.scanner.status.collectAsState()
    if (!status.running && status.lastMessage == null) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 6.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            if (status.running) {
                val text = when {
                    status.identifying -> "Identification en ligne… ${status.identified} titre(s)"
                    status.total > 0 -> "Analyse de ${status.folderName} : ${status.processed}/${status.total} — ${status.currentFile ?: ""}"
                    else -> "Analyse de ${status.folderName ?: ""}…"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TextButton(onClick = { a.services.scanner.cancel() }) { Text("Arrêter") }
                }
                Spacer(Modifier.height(6.dp))
                if (status.total > 0 && !status.identifying) LinearProgressIndicator(progress = { status.processed / status.total.toFloat() }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Text(status.lastMessage ?: "", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun FolderCard(folder: LibraryFolder, count: Int) {
    val a = app()
    var menu by remember { mutableStateOf(false) }
    val reachable = remember(folder.uri) { File(folder.uri).isDirectory }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Folder, null, tint = if (reachable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(34.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(folder.displayPath, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        "$count fichier(s)",
                        folder.lastScannedAt?.let { "analysé le " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) },
                        if (!reachable) "introuvable (disque débranché ?)" else null
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                TextButton(onClick = { menu = true }) { Text(folder.category.label) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    MediaCategory.entries.forEach { c ->
                        DropdownMenuItem(text = { Text(c.label) }, onClick = {
                            menu = false
                            a.background { a.services.store.updateFolder(folder.id, c, folder.enabled); a.services.scanner.start(listOf(folder.id), force = true) }
                        })
                    }
                }
            }
            Switch(folder.enabled, { on -> a.background { a.services.store.updateFolder(folder.id, folder.category, on) } })
            IconButton(onClick = { a.go(Screen.Explorer(folder.uri)) }) { Icon(Icons.Filled.TravelExplore, "Parcourir") }
            IconButton(onClick = { a.services.scanner.start(listOf(folder.id)) }) { Icon(Icons.Filled.Refresh, "Analyser ce dossier") }
            IconButton(onClick = {
                a.dialog = AppDialog.Confirm(
                    "Retirer ce dossier ?", "Ses films et séries quitteront la bibliothèque (les fichiers ne sont pas supprimés du disque).", "Retirer"
                ) { a.background { a.services.store.removeFolder(folder.id) } }
            }) { Icon(Icons.Filled.Delete, "Retirer") }
        }
    }
}

@Composable
private fun CategoryDialog(dir: File, onDismiss: () -> Unit, onPick: (MediaCategory) -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Que contient ce dossier ?") },
        text = {
            Column {
                Text(dir.absolutePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                MediaCategory.entries.forEach { c ->
                    Text(
                        c.label, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onPick(c) }.padding(12.dp)
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

/** Titles the automatic identification was not sure about: confirm the guess, or pick the right one. */
@Composable
fun ReviewScreen() {
    val a = app()
    val snapshot by a.services.store.snapshot.collectAsState()
    val items: List<LibraryItem> = remember(snapshot) {
        (snapshot.movies.values.filter { !it.movie.matchLocked && it.movie.matchState != MatchState.IDENTIFIED }.map { snapshot.item(it) } +
            snapshot.series.values.filter { !it.series.matchLocked && it.series.matchState != MatchState.IDENTIFIED }.map { snapshot.item(it) })
            .sortedWith(compareBy<LibraryItem> { it.matchState != MatchState.TO_VERIFY }.thenBy { it.title })
    }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("À vérifier", "Titres reconnus avec un doute, ou pas reconnus du tout") {
            IconButton(onClick = a::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
        }
        if (items.isEmpty()) return EmptyState(Icons.Filled.CheckCircle, "Tout est identifié", "Aucun titre n'attend de vérification.")
        LazyColumn(contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.key }) { item ->
                val file = if (item.kind == MediaKind.MOVIE) snapshot.movies[item.id]?.files?.firstOrNull() else snapshot.series[item.id]?.files?.firstOrNull()
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(60.dp).height(90.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            RemoteImage(ImageUrls.poster(item.posterPath), Modifier.fillMaxSize()) { Icon(Icons.Filled.Movie, null, Modifier.align(Alignment.Center)) }
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (item.matchState == MatchState.TO_VERIFY) Icon(Icons.Outlined.HelpOutline, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(listOfNotNull(item.title, item.year?.toString()).joinToString(" · "), style = MaterialTheme.typography.titleMedium)
                            }
                            Text(file?.file?.fileName ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (item.matchState == MatchState.TO_VERIFY) "Proposition à confirmer" else "Non identifié", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (item.matchState == MatchState.TO_VERIFY) TextButton(onClick = { a.background { a.services.identifier.confirm(item.kind, item.id) } }) { Text("C'est bien ça") }
                        FilledTonalButton(onClick = { a.dialog = AppDialog.FixMatch(item.kind, item.id, item.title, item.year) }) { Text("Corriger") }
                        TextButton(onClick = { a.open(item) }) { Text("Fiche") }
                    }
                }
            }
        }
    }
}

/** Browses the folders on disk: plays any video, in the library or not, with the rest of the folder queued after it. */
@Composable
fun ExplorerScreen(path: String?) {
    val a = app()
    val snapshot by a.services.store.snapshot.collectAsState()
    val dir = path?.let(::File)
    val roots = remember(snapshot.folders) { snapshot.folders.map { File(it.uri) }.distinct() }
    val (dirs, videos) = remember(path) {
        val children = dir?.listFiles()?.filter { !it.isHidden }.orEmpty()
        children.filter { it.isDirectory }.sortedWith(compareBy(NaturalOrder) { it.name }) to
            children.filter { it.isFile && VideoFiles.isVideo(it.name) }.sortedWith(compareBy(NaturalOrder) { it.name })
    }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(dir?.name?.ifBlank { dir.absolutePath } ?: "Explorateur", dir?.absolutePath ?: "Dossiers de la bibliothèque et lecteurs") {
            if (a.canGoBack) IconButton(onClick = a::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
            dir?.parentFile?.let { parent -> TextButton(onClick = { a.go(Screen.Explorer(parent.absolutePath)) }) { Text("Dossier parent") } }
            TextButton(onClick = { pickVideoFile()?.let { f -> a.services.playback.playPath(f, f.parentFile?.listFiles()?.filter { VideoFiles.isVideo(it.name) }?.sortedWith(compareBy(NaturalOrder) { it.name }) ?: listOf(f)) } }) {
                Text("Ouvrir un fichier…")
            }
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp)) {
            if (dir == null) {
                items(roots + File.listRoots().toList()) { f -> ExplorerRow(Icons.Filled.Folder, f.absolutePath, null) { a.go(Screen.Explorer(f.absolutePath)) } }
            } else {
                items(dirs, key = { it.absolutePath }) { f -> ExplorerRow(Icons.Filled.Folder, f.name, null) { a.go(Screen.Explorer(f.absolutePath)) } }
                items(videos, key = { it.absolutePath }) { f ->
                    val known = snapshot.fileIndex.values.firstOrNull { it.file.uri.equals(f.absolutePath, ignoreCase = true) }
                    ExplorerRow(
                        Icons.Filled.Movie, f.name,
                        listOfNotNull(Formatters.size(f.length()), known?.let { if (it.completed) "vu" else if (it.inProgress) "en cours ${(it.progress * 100).toInt()} %" else null }).joinToString(" · ")
                    ) { a.services.playback.playPath(f, videos) }
                }
                if (dirs.isEmpty() && videos.isEmpty()) item { Text("Aucune vidéo ici.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun ExplorerRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
