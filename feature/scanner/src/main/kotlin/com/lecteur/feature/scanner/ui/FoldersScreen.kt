package com.lecteur.feature.scanner.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lecteur.core.data.library.FolderStatus
import com.lecteur.core.model.MediaCategory
import com.lecteur.feature.scanner.work.JobText
import java.text.DateFormat
import java.util.Date

fun MediaCategory.label(): String = when (this) {
    MediaCategory.MOVIES -> "Films"
    MediaCategory.SERIES -> "Séries"
    MediaCategory.ANIME -> "Animés"
    MediaCategory.DOCUMENTARIES -> "Documentaires"
    MediaCategory.PERSONAL -> "Vidéos personnelles"
    MediaCategory.GENERIC -> "Mixte (auto)"
}

private fun MediaCategory.hint(): String = when (this) {
    MediaCategory.MOVIES -> "Chaque fichier est un film, recherché sur TMDB."
    MediaCategory.SERIES -> "Les fichiers sont des épisodes, regroupés par série."
    MediaCategory.ANIME -> "Comme les séries, avec la numérotation continue des animés."
    MediaCategory.DOCUMENTARIES -> "Chaque fichier est un documentaire, recherché sur TMDB."
    MediaCategory.PERSONAL -> "Aucune recherche en ligne : le nom du fichier sert de titre."
    MediaCategory.GENERIC -> "Films, séries et animés mélangés : chaque fichier est classé selon son nom, dans tous les sous-dossiers."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(
    onBack: () -> Unit,
    onOpenReview: () -> Unit,
    viewModel: FoldersViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var pendingTree by rememberSaveable { mutableStateOf<String?>(null) }
    var folderToRemove by remember { mutableStateOf<FolderStatus?>(null) }

    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    // Notifications (scan progress) need a runtime permission from Android 13; the scan itself works without it
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) pendingTree = uri.toString()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dossiers de la bibliothèque") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") } },
                actions = {
                    if (state.folders.isNotEmpty()) {
                        IconButton(onClick = viewModel::scanAll, enabled = !state.job.scanRunning) {
                            Icon(Icons.Default.Refresh, contentDescription = "Analyser tous les dossiers")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { treePicker.launch(null) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Ajouter un dossier") }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.job.isBusy) item { JobCard(state.job, onCancel = viewModel::cancelJobs) }

            if (!state.metadataConfigured) item {
                NoticeCard(
                    "Identification en ligne désactivée",
                    "Aucune clé API TMDB dans cette version : les fichiers sont listés avec le titre de leur nom de fichier. " +
                        "Ajoutez tmdb.apiKey dans local.properties et recompilez."
                )
            }

            if (state.reviewCount > 0) item {
                Card(onClick = onOpenReview, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("À vérifier", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${state.reviewCount} titre${if (state.reviewCount > 1) "s" else ""} sans association sûre",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text("Corriger", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            if (state.loaded && state.folders.isEmpty()) item { EmptyState() }

            items(state.folders, key = { it.folder.id }) { status ->
                FolderCard(
                    status = status,
                    scanning = state.job.scanRunning && state.job.scanProgress?.folderId == status.folder.id,
                    onPause = { viewModel.setPaused(status.folder.id, !status.isPaused) },
                    onRescan = { viewModel.rescan(status.folder.id) },
                    onRemove = { folderToRemove = status },
                    onCategory = { viewModel.setCategory(status.folder.id, it) }
                )
            }

            item { Attribution() }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    pendingTree?.let { tree ->
        CategoryDialog(
            onDismiss = { pendingTree = null },
            onConfirm = { categories ->
                viewModel.addFolder(Uri.parse(tree), categories)
                pendingTree = null
            }
        )
    }

    folderToRemove?.let { status ->
        AlertDialog(
            onDismissRequest = { folderToRemove = null },
            title = { Text("Retirer ce dossier ?") },
            text = {
                Text(
                    "« ${status.folder.displayPath} » quitte la bibliothèque, avec la progression de lecture de ses ${status.fileCount} fichiers. " +
                        "Les fichiers eux-mêmes ne sont pas touchés."
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.remove(status.folder.id); folderToRemove = null }) { Text("Retirer") } },
            dismissButton = { TextButton(onClick = { folderToRemove = null }) { Text("Annuler") } }
        )
    }
}

@Composable
private fun CategoryDialog(onDismiss: () -> Unit, onConfirm: (Set<MediaCategory>) -> Unit) {
    // Several categories can be ticked: the same folder is then watched once per category, like several Plex libraries
    var selected by rememberSaveable { mutableStateOf(listOf(MediaCategory.GENERIC)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Que contient ce dossier ?") },
        text = {
            Column {
                Text(
                    "Cochez plusieurs catégories pour utiliser le même dossier comme plusieurs bibliothèques : chaque fichier ira " +
                        "à la catégorie qui lui correspond (film, série ou animé), sans doublon.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                MediaCategory.entries.forEach { category ->
                    val checked = category in selected
                    Row(
                        Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox) {
                            selected = when {
                                checked -> selected - category
                                // "Mixte" already takes everything: it excludes the specific categories, and the other way round
                                category == MediaCategory.GENERIC -> listOf(category)
                                else -> selected.filter { it != MediaCategory.GENERIC } + category
                            }
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(category.label(), style = MaterialTheme.typography.bodyLarge)
                            Text(category.hint(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(selected.toSet()) }, enabled = selected.isNotEmpty()) { Text("Ajouter et analyser") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
private fun FolderCard(
    status: FolderStatus,
    scanning: Boolean,
    onPause: () -> Unit,
    onRescan: () -> Unit,
    onRemove: () -> Unit,
    onCategory: (MediaCategory) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(status.folder.displayPath, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)

            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Box {
                    OutlinedButton(onClick = { menuOpen = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
                        Text(status.folder.category.label())
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        MediaCategory.entries.forEach { category ->
                            DropdownMenuItem(text = { Text(category.label()) }, onClick = { menuOpen = false; onCategory(category) })
                        }
                    }
                }
                if (status.isPaused) Text("  En pause", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
            }

            Text(
                buildString {
                    append("${status.fileCount} média${if (status.fileCount > 1) "s" else ""}")
                    if (status.hasUnavailableFiles) append(" · ${status.fileCount - status.availableCount} indisponible${if (status.fileCount - status.availableCount > 1) "s" else ""}")
                    append(" · ")
                    append(status.folder.lastScannedAt?.let { "analysé le " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: "pas encore analysé")
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (status.hasUnavailableFiles) {
                Text(
                    "Des fichiers sont introuvables (disque débranché ou fichiers supprimés). Ils restent dans la bibliothèque avec leur progression.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary
                )
            }
            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onPause) {
                    Icon(if (status.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = if (status.isPaused) "Reprendre" else "Mettre en pause")
                }
                IconButton(onClick = onRescan) { Icon(Icons.Default.Refresh, contentDescription = "Analyser à nouveau") }
                IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, contentDescription = "Retirer") }
            }
        }
    }
}

@Composable
private fun JobCard(job: com.lecteur.feature.scanner.work.LibraryJobState, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val title = when {
                job.scanRunning || job.scanQueued -> JobText.scanTitle(job.scanProgress)
                else -> "Métadonnées de la bibliothèque"
            }
            val detail = when {
                job.scanRunning -> JobText.scanDetail(job.scanProgress)
                job.scanQueued -> "En attente…"
                job.identifyRunning -> JobText.identifyDetail(job.identifiedSoFar)
                else -> "En attente de connexion"
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val fraction = JobText.fraction(job.scanProgress)
            if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onCancel) { Text("Annuler") } }
        }
    }
}

@Composable
private fun NoticeCard(title: String, text: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Votre bibliothèque est vide", style = MaterialTheme.typography.titleLarge)
        Text(
            "Ajoutez un dossier de films ou de séries (mémoire interne, carte SD ou clé USB). Il est analysé en arrière-plan : " +
                "les titres apparaissent au fil de l'analyse, et les affiches et synopsis sont téléchargés une seule fois.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun Attribution() {
    Text(
        "Ce produit utilise l'API TMDB mais n'est ni approuvé ni certifié par TMDB.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}
