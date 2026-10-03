package com.lecteur.feature.library

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lecteur.core.data.browse.BrowseEntry
import com.lecteur.core.data.browse.BrowseRepository
import com.lecteur.core.data.browse.BrowseRoot
import com.lecteur.core.data.browse.DirectoryListing
import com.lecteur.core.designsystem.components.EmptyState
import com.lecteur.core.designsystem.components.LoadingBox
import com.lecteur.core.model.Formatters
import com.lecteur.core.model.PlayPlan
import com.lecteur.core.model.QueueEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One level of the path being browsed. */
data class Crumb(val treeUri: String, val documentId: String, val name: String)

data class ExplorerUi(
    val roots: List<BrowseRoot> = emptyList(),
    val crumbs: List<Crumb> = emptyList(),
    val listing: DirectoryListing? = null,
    val loading: Boolean = true,
    /** The folder could not be read: drive unplugged or access revoked. */
    val unreadable: Boolean = false
) {
    val atRoots: Boolean get() = crumbs.isEmpty()
    val path: String get() = crumbs.joinToString(" › ") { it.name }
}

sealed interface ExplorerEvent {
    data class Play(val plan: PlayPlan) : ExplorerEvent
    data class Message(val text: String) : ExplorerEvent
}

@HiltViewModel
class FolderExplorerViewModel @Inject constructor(
    private val browse: BrowseRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ExplorerUi())
    val state: StateFlow<ExplorerUi> = _state

    private val eventChannel = Channel<ExplorerEvent>(Channel.BUFFERED)
    val events: Flow<ExplorerEvent> = eventChannel.receiveAsFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            val roots = browse.roots()
            _state.update { it.copy(roots = roots, loading = false) }
            // One watched folder: no need to make the user pick it
            roots.singleOrNull()?.let(::openRoot)
        }
    }

    fun openRoot(root: BrowseRoot) = go(listOf(Crumb(root.treeUri, root.rootDocumentId, root.displayPath.trimEnd('/').substringAfterLast('/').ifEmpty { root.displayPath })))

    fun openFolder(entry: BrowseEntry) {
        val current = _state.value.crumbs
        val tree = current.lastOrNull()?.treeUri ?: return
        go(current + Crumb(tree, entry.documentId, entry.name))
    }

    /** Back one level; false at the list of folders, where the system back button takes over. */
    fun up(): Boolean {
        val crumbs = _state.value.crumbs
        if (crumbs.isEmpty()) return false
        if (crumbs.size == 1) {
            loadJob?.cancel()
            _state.update { it.copy(crumbs = emptyList(), listing = null, unreadable = false, loading = false) }
        } else {
            go(crumbs.dropLast(1))
        }
        return true
    }

    fun refresh() {
        val crumbs = _state.value.crumbs
        if (crumbs.isNotEmpty()) go(crumbs)
    }

    fun play(entry: BrowseEntry) {
        val uri = entry.uri ?: return
        viewModelScope.launch { eventChannel.send(ExplorerEvent.Play(PlayPlan(listOf(QueueEntry(uri, entry.name))))) }
    }

    /** "Lire le dossier": its videos one after the other, from the first or from a random one. */
    fun playFolder(shuffle: Boolean, startAt: BrowseEntry? = null) {
        val state = _state.value
        val listing = state.listing ?: return
        val plan = browse.planFor(listing, state.crumbs.lastOrNull()?.name, start = startAt?.documentId, shuffle = shuffle)
        viewModelScope.launch {
            eventChannel.send(if (plan != null) ExplorerEvent.Play(plan) else ExplorerEvent.Message("Ce dossier ne contient aucune vidéo."))
        }
    }

    private fun go(crumbs: List<Crumb>) {
        loadJob?.cancel()
        _state.update { it.copy(crumbs = crumbs, loading = true, unreadable = false) }
        val target = crumbs.last()
        loadJob = viewModelScope.launch {
            val listing = browse.open(target.treeUri, target.documentId)
            _state.update { it.copy(listing = listing, loading = false, unreadable = listing == null) }
        }
    }
}

/**
 * The watched folders as they are on disk: any file plays, identified or not, and a folder can be played as a whole.
 * The system back button climbs the path before it leaves the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderExplorerScreen(
    onPlay: (PlayPlan) -> Unit,
    onManageFolders: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FolderExplorerViewModel = hiltViewModel()
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Any video, wherever it is, even outside the watched folders: it is remembered so "Reprendre" works next time
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = Uri.decode(uri.lastPathSegment.orEmpty()).substringAfterLast('/').ifEmpty { "Vidéo" }
            onPlay(PlayPlan(listOf(QueueEntry(uri.toString(), name))))
        }
    }

    BackHandler(enabled = !ui.atRoots) { viewModel.up() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ExplorerEvent.Play -> onPlay(event.plan)
                is ExplorerEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (ui.atRoots) "Dossiers" else ui.path, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (!ui.atRoots) IconButton(onClick = { viewModel.up() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Dossier parent") }
                },
                actions = {
                    IconButton(onClick = { picker.launch(arrayOf("video/*", "application/x-matroska")) }) {
                        Icon(Icons.Rounded.FileOpen, contentDescription = "Ouvrir un fichier vidéo")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                ui.atRoots && ui.loading -> LoadingBox()
                ui.atRoots && ui.roots.isEmpty() -> EmptyState(
                    icon = Icons.Rounded.CreateNewFolder,
                    title = "Aucun dossier surveillé",
                    message = "Les dossiers que vous ajoutez à la bibliothèque apparaissent ici, tels qu'ils sont sur le disque.",
                    actionLabel = "Ajouter un dossier",
                    onAction = onManageFolders
                )
                ui.atRoots -> Roots(ui.roots, viewModel::openRoot)
                ui.loading && ui.listing == null -> LoadingBox()
                ui.unreadable -> EmptyState(
                    icon = Icons.Rounded.FolderOff,
                    title = "Dossier inaccessible",
                    message = "Ce dossier n'a pas pu être lu : le support (carte SD, clé USB) est peut-être débranché, ou l'accès a été retiré. Rebranchez-le puis réessayez.",
                    actionLabel = "Réessayer",
                    onAction = viewModel::refresh
                )
                else -> ui.listing?.let { Listing(it, viewModel) }
            }
        }
    }
}

@Composable
private fun Roots(roots: List<BrowseRoot>, onOpen: (BrowseRoot) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(roots, key = { it.treeUri }) { root ->
            EntryRow(Icons.Rounded.Folder, root.displayPath, null, null, 0f) { onOpen(root) }
            HorizontalDivider()
        }
    }
}

@Composable
private fun Listing(listing: DirectoryListing, viewModel: FolderExplorerViewModel) {
    Column(Modifier.fillMaxSize()) {
        if (listing.videos.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.playFolder(shuffle = false) }) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Text("Lire le dossier", Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = { viewModel.playFolder(shuffle = true) }) {
                    Icon(Icons.Rounded.Shuffle, contentDescription = null)
                    Text("Aléatoire", Modifier.padding(start = 6.dp))
                }
            }
            HorizontalDivider()
        }
        if (listing.entries.isEmpty()) {
            EmptyState(Icons.Rounded.Folder, "Dossier vide", "Aucun fichier ni sous-dossier ici.")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(listing.entries, key = { it.documentId }) { entry ->
                    when {
                        entry.isDirectory -> EntryRow(Icons.Rounded.Folder, entry.name, null, null, 0f) { viewModel.openFolder(entry) }
                        entry.isVideo -> EntryRow(
                            icon = Icons.Rounded.Movie,
                            name = entry.name,
                            detail = Formatters.size(entry.sizeBytes),
                            watched = entry.watched,
                            progress = entry.progress
                        ) { viewModel.play(entry) }
                        else -> EntryRow(Icons.AutoMirrored.Rounded.InsertDriveFile, entry.name, Formatters.size(entry.sizeBytes), null, 0f, enabled = false) {}
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun EntryRow(
    icon: ImageVector,
    name: String,
    detail: String?,
    watched: Boolean?,
    progress: Float,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val alpha = if (enabled) 1f else 0.45f
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (progress > 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        if (watched == true) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = "Vu", tint = MaterialTheme.colorScheme.primary)
        }
    }
}
