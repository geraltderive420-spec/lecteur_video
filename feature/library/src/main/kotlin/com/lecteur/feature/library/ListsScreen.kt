package com.lecteur.feature.library

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
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lecteur.core.data.library.ListResult
import com.lecteur.core.data.library.UserListsRepository
import com.lecteur.core.designsystem.components.EmptyState
import com.lecteur.core.model.UserListSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ListsViewModel @Inject constructor(private val repository: UserListsRepository) : ViewModel() {

    val lists: StateFlow<List<UserListSummary>?> = repository.lists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The reason when the name is refused, null when the list was created. */
    suspend fun create(name: String): String? = (repository.create(name) as? ListResult.Refused)?.problem?.message

    suspend fun rename(id: Long, name: String): String? = (repository.rename(id, name) as? ListResult.Refused)?.problem?.message

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }
}

private sealed interface Dialog {
    data object Create : Dialog
    data class Rename(val list: UserListSummary) : Dialog
    data class Delete(val list: UserListSummary) : Dialog
}

/** The user's lists, favourites first. Opening one shows its titles through the library screen scoped to it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsScreen(onBack: () -> Unit, onOpenList: (Long, String) -> Unit, viewModel: ListsViewModel = hiltViewModel()) {
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<Dialog?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mes listes") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Retour") } }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { dialog = Dialog.Create },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("Nouvelle liste") }
            )
        }
    ) { padding ->
        val current = lists
        when {
            current == null -> Box(Modifier.fillMaxSize().padding(padding))
            // Only the favourites list (created on demand) or nothing: still worth explaining.
            current.none { !it.isFavorites } && current.all { it.itemCount == 0 } -> EmptyState(
                icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
                title = "Aucune liste pour l'instant",
                message = "Créez une liste (« À voir ce week-end », « Pour les enfants »...), puis ajoutez-y des films et des séries depuis leur fiche ou par un appui long sur une affiche.",
                actionLabel = "Nouvelle liste",
                onAction = { dialog = Dialog.Create },
                modifier = Modifier.padding(padding)
            )
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
                items(current, key = { it.id }) { list ->
                    ListRow(list, onOpen = { onOpenList(list.id, list.name) }, onRename = { dialog = Dialog.Rename(list) }, onDelete = { dialog = Dialog.Delete(list) })
                }
            }
        }
    }

    when (val d = dialog) {
        Dialog.Create -> NameDialog(
            title = "Nouvelle liste", initial = "", confirm = "Créer",
            onSubmit = viewModel::create, onDismiss = { dialog = null }
        )
        is Dialog.Rename -> NameDialog(
            title = "Renommer la liste", initial = d.list.name, confirm = "Renommer",
            onSubmit = { viewModel.rename(d.list.id, it) }, onDismiss = { dialog = null }
        )
        is Dialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Supprimer « ${d.list.name} » ?") },
            text = { Text("Seule la liste est supprimée : les films et les séries restent dans la bibliothèque.") },
            confirmButton = { TextButton(onClick = { viewModel.delete(d.list.id); dialog = null }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Annuler") } }
        )
        null -> Unit
    }
}

@Composable
private fun ListRow(list: UserListSummary, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 24.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (list.isFavorites) Icons.Rounded.Favorite else Icons.AutoMirrored.Rounded.PlaylistPlay,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Column(Modifier.weight(1f).padding(start = 20.dp, top = 8.dp, bottom = 8.dp)) {
            Text(list.name, style = MaterialTheme.typography.titleMedium)
            Text(
                when (list.itemCount) { 0 -> "Vide"; 1 -> "1 titre"; else -> "${list.itemCount} titres" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // Favourites can be neither renamed nor deleted: no menu rather than a menu of disabled lines.
        if (!list.isFavorites) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Actions de la liste ${list.name}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Renommer") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Supprimer") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

/** Asks for a name; [onSubmit] returns the reason it was refused, which keeps the dialog open. */
@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onSubmit: suspend (String) -> String?, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it; error = null }, singleLine = true, isError = error != null, label = { Text("Nom") })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val refused = onSubmit(name)
                    if (refused == null) onDismiss() else error = refused
                }
            }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}
