package com.lecteur.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lecteur.core.common.text.NaturalOrder
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.WatchStatus
import com.lecteur.tv.TvLibraryViewModel
import com.lecteur.tv.TvPage
import com.lecteur.tv.TvTile
import com.lecteur.tv.TvWide

/** Home rows of the merged library: what is being watched first, wherever it is. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvHomeScreen(viewModel: TvLibraryViewModel, onOpen: (TvPage) -> Unit) {
    val ui by viewModel.homeUi.collectAsStateWithLifecycle()
    if (ui.loaded && ui.continueWatching.isEmpty() && ui.nextUp.isEmpty() && ui.recent.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (ui.hasFolders) "Analyse de la bibliothèque en cours…" else "Rien à regarder pour l'instant.", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Associez un PC dans l'onglet « Serveurs » pour voir sa bibliothèque ici, ajoutez des dossiers de cette TV dans « Dossiers », " +
                        "ou envoyez un film depuis le téléphone (« Diffuser sur un écran »).",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
        return
    }
    fun openWide(card: TvWide) = viewModel.play(card)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        if (ui.continueWatching.isNotEmpty()) item("continue") {
            TvShelf("Reprendre") {
                items(ui.continueWatching, key = { it.key }) { card ->
                    TvWideCard(card, onClick = { openWide(card) }, onLongClick = card.ref?.let { ref -> { onOpen(TvPage.Detail(ref)) } })
                }
            }
        }
        if (ui.nextUp.isNotEmpty()) item("next") {
            TvShelf("Épisodes suivants") {
                items(ui.nextUp, key = { it.key }) { card ->
                    TvWideCard(card, onClick = { openWide(card) }, onLongClick = card.ref?.let { ref -> { onOpen(TvPage.Detail(ref)) } })
                }
            }
        }
        if (ui.recent.isNotEmpty()) item("recent") { TvShelf("Ajoutés récemment") { posters(ui.recent) { onOpen(TvPage.Detail(it.ref)) } } }
        if (ui.unwatched.isNotEmpty()) item("unwatched") { TvShelf("Films non vus") { posters(ui.unwatched) { onOpen(TvPage.Detail(it.ref)) } } }
    }
}

private enum class GridSort(val label: String) { TITLE("Titre"), ADDED("Ajout récent"), YEAR("Année"), RATING("Note") }

/** Every film (or series) of every source, with the sorts and filters of the phone's library. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvGridScreen(viewModel: TvLibraryViewModel, kind: MediaKind, onOpen: (TvPage) -> Unit) {
    val all by (if (kind == MediaKind.MOVIE) viewModel.movies else viewModel.series).collectAsStateWithLifecycle()
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    var sort by rememberSaveable(kind) { mutableStateOf(GridSort.TITLE) }
    var source by rememberSaveable(kind) { mutableStateOf<String?>(ALL) }
    var unwatchedOnly by rememberSaveable(kind) { mutableStateOf(false) }
    var genre by rememberSaveable(kind) { mutableStateOf<String?>(null) }

    val genres = remember(all) { all.flatMap { it.genres }.distinct().sorted() }
    val shown = remember(all, sort, source, unwatchedOnly, genre) {
        all.filter { t ->
            (source == ALL || t.sourceKey == source) && (!unwatchedOnly || t.item.watch != WatchStatus.WATCHED) && (genre == null || genre in t.genres)
        }.sortedWith(
            when (sort) {
                GridSort.TITLE -> compareBy(NaturalOrder) { it.item.title }
                GridSort.ADDED -> compareByDescending<TvTile> { it.item.addedAt }
                GridSort.YEAR -> compareByDescending<TvTile> { it.item.year ?: 0 }
                GridSort.RATING -> compareByDescending<TvTile> { it.item.rating ?: 0f }
            }
        )
    }

    LazyVerticalGrid(
        columns = GridCells.FixedSize(POSTER_WIDTH),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        contentPadding = PaddingValues(8.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Trier", style = MaterialTheme.typography.labelLarge)
                    GridSort.entries.forEach { s -> FilterChip(selected = sort == s, onClick = { sort = s }) { Text(s.label) } }
                    Text("  ${shown.size} titres", style = MaterialTheme.typography.labelLarge)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Source", style = MaterialTheme.typography.labelLarge)
                    FilterChip(selected = source == ALL, onClick = { source = ALL }) { Text("Tout") }
                    sources.forEach { (id, name) -> FilterChip(selected = source == (id ?: LOCAL), onClick = { source = id ?: LOCAL }) { Text(name) } }
                    FilterChip(selected = unwatchedOnly, onClick = { unwatchedOnly = !unwatchedOnly }) { Text("Non vus") }
                }
                if (genres.isNotEmpty()) {
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(selected = genre == null, onClick = { genre = null }) { Text("Tous les genres") } }
                        items(genres) { g -> FilterChip(selected = genre == g, onClick = { genre = if (genre == g) null else g }) { Text(g) } }
                    }
                }
            }
        }
        if (shown.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    if (all.isEmpty()) "Aucun titre : ajoutez des dossiers à cette TV ou associez un PC (onglet « Serveurs »)." else "Aucun titre avec ces filtres.",
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp)
                )
            }
        }
        items(shown, key = { it.ref.key }) { tile -> TvPoster(tile, onClick = { onOpen(TvPage.Detail(tile.ref)) }) }
    }
}

/** Filter values: every source, or this TV (whose server id is null). */
private const val ALL = "*"
private const val LOCAL = "tv"

private val TvTile.sourceKey: String get() = ref.serverId ?: LOCAL
