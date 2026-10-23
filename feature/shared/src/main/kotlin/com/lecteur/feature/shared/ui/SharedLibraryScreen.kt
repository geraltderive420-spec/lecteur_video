package com.lecteur.feature.shared.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lecteur.core.common.library.Catalog
import com.lecteur.core.common.library.LibraryException
import com.lecteur.core.common.library.MovieDto
import com.lecteur.core.common.library.SeriesDto
import com.lecteur.core.common.text.TextFold
import com.lecteur.core.data.shared.CatalogState
import com.lecteur.core.designsystem.components.EmptyState
import com.lecteur.core.designsystem.components.LoadingBox
import com.lecteur.core.designsystem.components.PosterImage
import com.lecteur.core.model.PlayPlan
import com.lecteur.feature.shared.SharedLibraryViewModel
import com.lecteur.feature.shared.SharedShelves
import com.lecteur.feature.shared.SharedShelves.isCompleted
import com.lecteur.feature.shared.ShelfCard

private val TABS = listOf("Accueil", "Films", "Séries")

/** A PC's library on the phone: home shelves, movies, series. Everything plays from the PC's files. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedLibraryScreen(
    onBack: () -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenSeries: (Long) -> Unit,
    onPlay: (PlayPlan) -> Unit,
    onForgotten: () -> Unit,
    viewModel: SharedLibraryViewModel = hiltViewModel()
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ui.serverName.ifBlank { "Bibliothèque partagée" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Retour") } },
                actions = { IconButton(onClick = viewModel::refresh) { Icon(Icons.Rounded.Refresh, "Actualiser") } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val catalog = ui.catalog
            when {
                catalog == null && ui.state is CatalogState.Failed -> Failure(ui.state as CatalogState.Failed, onRetry = viewModel::refresh, onForgotten = { viewModel.forget(); onForgotten() })
                catalog == null -> LoadingBox()
                else -> {
                    (ui.state as? CatalogState.Failed)?.let { failed ->
                        Text(
                            "Hors connexion : ${failed.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                    TabRow(selectedTabIndex = tab) {
                        TABS.forEachIndexed { i, label -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) }) }
                    }
                    when (tab) {
                        0 -> Shelves(ui.continueWatching, ui.nextUp, ui.recent, viewModel::art) { card ->
                            when {
                                card.episodeId != null || (card.movieId != null && card.progress > 0f) -> viewModel.planFor(card)?.let(onPlay)
                                card.movieId != null -> onOpenMovie(card.movieId)
                                card.seriesId != null -> onOpenSeries(card.seriesId)
                            }
                        }
                        1 -> Grid(query, { query = it }, movies(catalog, query), viewModel::art) { onOpenMovie(it) }
                        else -> Grid(query, { query = it }, series(catalog, query), viewModel::art) { onOpenSeries(it) }
                    }
                }
            }
        }
    }
}

/** A poster of the grid, whatever the kind. */
private data class Tile(val id: Long, val title: String, val year: Int?, val poster: String?, val progress: Float, val watched: Boolean)

private fun matches(query: String, title: String): Boolean {
    val tokens = TextFold.tokens(query)
    return tokens.isEmpty() || TextFold.score(TextFold.fold(title), tokens) != null
}

private fun movies(c: Catalog, query: String) = c.movies.filter { matches(query, it.title) }.map(::tile)
private fun series(c: Catalog, query: String) = c.series.filter { matches(query, it.title) }.map(::tile)

private fun tile(m: MovieDto): Tile {
    val file = SharedShelves.movieFile(m)
    return Tile(m.id, m.title, m.year, m.poster, file?.watch?.takeIf { !it.completed }?.let { if (it.durationMs > 0) it.positionMs.toFloat() / it.durationMs else 0f } ?: 0f, m.files.any { it.watch?.completed == true })
}

private fun tile(s: SeriesDto): Tile {
    val done = s.episodes.count { it.isCompleted() }
    return Tile(s.id, s.title, s.year, s.poster, if (s.episodes.isEmpty()) 0f else done.toFloat() / s.episodes.size, s.episodes.isNotEmpty() && done == s.episodes.size)
}

@Composable
private fun Grid(query: String, onQuery: (String) -> Unit, tiles: List<Tile>, art: (String?) -> String?, onOpen: (Long) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(112.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            OutlinedTextField(
                query, onQuery, singleLine = true, leadingIcon = { Icon(Icons.Rounded.Search, null) }, placeholder = { Text("Rechercher") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (tiles.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Aucun titre.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
        }
        items(tiles, key = { it.id }) { t -> PosterTile(t, art(t.poster)) { onOpen(t.id) } }
    }
}

@Composable
private fun PosterTile(tile: Tile, url: String?, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))) {
            PosterImage(url, tile.title, Modifier.fillMaxSize())
            if (tile.watched) {
                Icon(Icons.Rounded.CheckCircle, "Vu", tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp))
            } else if (tile.progress > 0f) {
                LinearProgressIndicator(progress = { tile.progress }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp), trackColor = Color.Black.copy(alpha = 0.5f))
            }
        }
        Text(tile.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp))
        tile.year?.let { Text(it.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 2.dp)) }
    }
}

@Composable
private fun Shelves(continueWatching: List<ShelfCard>, nextUp: List<ShelfCard>, recent: List<ShelfCard>, art: (String?) -> String?, onClick: (ShelfCard) -> Unit) {
    if (continueWatching.isEmpty() && nextUp.isEmpty() && recent.isEmpty()) {
        EmptyState(Icons.Rounded.CloudOff, "Bibliothèque vide", "Ce PC ne partage encore aucun film ni série. Ajoutez des dossiers dans Lecteur Média sur le PC.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (continueWatching.isNotEmpty()) item { Shelf("Reprendre", continueWatching, wide = true, art, onClick) }
        if (nextUp.isNotEmpty()) item { Shelf("Épisodes suivants", nextUp, wide = true, art, onClick) }
        if (recent.isNotEmpty()) item { Shelf("Ajoutés récemment", recent, wide = false, art, onClick) }
    }
}

@Composable
private fun Shelf(title: String, cards: List<ShelfCard>, wide: Boolean, art: (String?) -> String?, onClick: (ShelfCard) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(cards, key = { it.key }) { card ->
                Column(Modifier.width(if (wide) 220.dp else 112.dp).clip(RoundedCornerShape(8.dp)).clickable { onClick(card) }) {
                    Box(Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 2f / 3f).clip(RoundedCornerShape(8.dp))) {
                        PosterImage(art(card.image), card.title, Modifier.fillMaxSize())
                        if (card.progress > 0f) {
                            LinearProgressIndicator(progress = { card.progress }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp), trackColor = Color.Black.copy(alpha = 0.5f))
                        }
                    }
                    Text(card.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    card.subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

@Composable
private fun Failure(failed: CatalogState.Failed, onRetry: () -> Unit, onForgotten: () -> Unit) {
    if (failed.kind == LibraryException.Kind.UNAUTHORIZED) {
        EmptyState(Icons.Rounded.CloudOff, "Accès retiré", failed.message, actionLabel = "Associer à nouveau", onAction = onForgotten)
    } else {
        EmptyState(Icons.Rounded.CloudOff, "PC injoignable", failed.message, actionLabel = "Réessayer", onAction = onRetry)
    }
}
