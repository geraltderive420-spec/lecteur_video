package com.lecteur.feature.scanner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.lecteur.core.data.library.ReviewItem
import com.lecteur.core.model.ImageUrls
import com.lecteur.core.model.MatchState
import com.lecteur.core.model.MetadataSearchHit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(onBack: () -> Unit, viewModel: ReviewViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val correction by viewModel.correction.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("À vérifier") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") } }
            )
        }
    ) { padding ->
        val list = items
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text("Tout est identifié. Rien à vérifier.", style = MaterialTheme.typography.titleMedium)
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(list, key = { "${it.kind}-${it.id}" }) { item ->
                    ReviewRow(item, onClick = { viewModel.open(item) })
                    HorizontalDivider()
                }
                item { Attribution() }
            }
        }
    }

    correction?.let { state ->
        CorrectionDialog(state, viewModel)
    }
}

@Composable
private fun ReviewRow(item: ReviewItem, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Poster(item.posterPath)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                listOfNotNull(item.title, item.year?.toString()).joinToString(" · "),
                style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            item.sampleFileName?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "${item.kind.label()} · " + if (item.state == MatchState.TO_VERIFY) "association à confirmer" else "non identifié",
                style = MaterialTheme.typography.labelMedium,
                color = if (item.state == MatchState.TO_VERIFY) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun Poster(path: String?) {
    val url = ImageUrls.poster(path)
    Box(Modifier.size(width = 48.dp, height = 72.dp)) {
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
internal fun CorrectionDialog(state: CorrectionState, viewModel: ReviewViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::close,
        title = { Text("Corriger l'association") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.item.sampleFileName?.let {
                    Text("Fichier : $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.query, onValueChange = viewModel::onQuery, label = { Text("Titre") },
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = state.year, onValueChange = viewModel::onYear, label = { Text("Année") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(0.5f)
                    )
                }
                Button(onClick = viewModel::search, enabled = !state.busy && state.query.isNotBlank()) { Text("Rechercher") }

                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (state.busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))

                if (state.searched && state.results.isEmpty() && !state.busy && state.error == null) {
                    Text("Aucun résultat. Essayez un autre titre, sans l'année, ou saisissez l'identifiant TMDB.", style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(Modifier.height(240.dp)) {
                    items(state.results, key = { it.tmdbId }) { hit -> ResultRow(hit, onClick = { viewModel.choose(hit) }) }
                }

                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.tmdbId, onValueChange = viewModel::onTmdbId, label = { Text("Identifiant TMDB") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = viewModel::applyTypedId, enabled = !state.busy && state.tmdbId.isNotEmpty()) { Text("Associer") }
                }
                Text(
                    "Le choix est verrouillé : les prochaines analyses ne le modifieront pas.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            if (state.item.state == MatchState.TO_VERIFY && state.item.tmdbId != null) {
                TextButton(onClick = viewModel::confirm) { Text("Confirmer la proposition") }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = viewModel::retryAutomatically) { Text("Chercher à nouveau") }
                TextButton(onClick = viewModel::close) { Text("Fermer") }
            }
        }
    )
}

@Composable
private fun ResultRow(hit: MetadataSearchHit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Poster(hit.posterPath)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(listOfNotNull(hit.title, hit.year?.toString()).joinToString(" · "), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!hit.originalTitle.isNullOrBlank() && hit.originalTitle != hit.title) {
                Text(hit.originalTitle!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            hit.overview?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}
