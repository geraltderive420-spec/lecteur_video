package com.lecteur.feature.shared.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lecteur.core.data.shared.CatalogState
import com.lecteur.core.data.shared.LibrarySourceRepository
import com.lecteur.core.data.shared.SharedLibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** One library the phone can show: this phone's ([serverId] null) or a paired PC's, with how its catalog stands. */
data class SourceRow(val serverId: String?, val name: String, val status: String, val selected: Boolean)

data class SourcesUi(val rows: List<SourceRow> = emptyList()) {
    val current: SourceRow? get() = rows.firstOrNull { it.selected }
}

/** The library on display, and the choice between this phone's and the paired PCs'. */
@HiltViewModel
class LibrarySourceViewModel @Inject constructor(
    private val sources: LibrarySourceRepository,
    private val shared: SharedLibraryRepository
) : ViewModel() {

    /** The PC whose library is on display, null for this phone's (the default). */
    val active: StateFlow<String?> = sources.active

    val ui: StateFlow<SourcesUi> = combine(sources.active, shared.servers, shared.catalogs) { active, servers, catalogs ->
        val phone = SourceRow(null, PHONE, "Les dossiers de ce téléphone", active == null)
        SourcesUi(listOf(phone) + servers.sortedBy { it.name.lowercase() }.map { server ->
            val status = when (val state = catalogs[server.serverId]) {
                is CatalogState.Loaded -> "${state.catalog.movies.size} films · ${state.catalog.series.size} séries"
                is CatalogState.Failed -> if (state.previous != null) "Ne répond pas · dernière liste connue" else "Ne répond pas"
                CatalogState.Loading -> "Chargement…"
                null -> "PC associé"
            }
            SourceRow(server.serverId, server.name, status, active == server.serverId)
        })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SourcesUi())

    fun select(serverId: String?) = sources.select(serverId)

    /** Reads every PC's catalog, so the sheet says which ones answer. */
    fun refreshAll() = shared.refreshAll()

    companion object {
        const val PHONE = "Ce téléphone"
    }
}

/**
 * The top bar's title on the home and library: the library on display ("Ce téléphone" or the PC's name), and a sheet to
 * switch to another one or to pair a new PC ([onManage]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySourceTitle(onManage: () -> Unit, viewModel: LibrarySourceViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val current = ui.current

    Row(
        Modifier.clickable {
            viewModel.refreshAll()
            open = true
        }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (current?.serverId == null) Icons.Rounded.PhoneAndroid else Icons.Rounded.Computer,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Text(
            current?.name ?: LibrarySourceViewModel.PHONE,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false)
        )
        Icon(Icons.Rounded.ArrowDropDown, contentDescription = "Changer de bibliothèque")
    }

    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                Text("Bibliothèque à afficher", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                ui.rows.forEach { row ->
                    ListItem(
                        headlineContent = { Text(row.name) },
                        supportingContent = { Text(row.status) },
                        leadingContent = { Icon(if (row.serverId == null) Icons.Rounded.PhoneAndroid else Icons.Rounded.Computer, contentDescription = null) },
                        trailingContent = { if (row.selected) Icon(Icons.Rounded.Check, contentDescription = "Affichée", tint = MaterialTheme.colorScheme.primary) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {
                            viewModel.select(row.serverId)
                            open = false
                        }
                    )
                }
                ListItem(
                    headlineContent = { Text(if (ui.rows.size > 1) "Gérer les PC" else "Associer un PC") },
                    supportingContent = { Text("Lire la bibliothèque d'un PC du réseau et copier ses fichiers sur le téléphone") },
                    leadingContent = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {
                        open = false
                        onManage()
                    }
                )
            }
        }
    }
}
