package com.lecteur.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.lecteur.core.common.library.LibraryException
import com.lecteur.core.common.library.SeriesDto
import com.lecteur.core.common.library.best
import com.lecteur.core.common.library.progress
import com.lecteur.core.data.shared.CatalogState
import com.lecteur.core.designsystem.components.PosterImage
import com.lecteur.core.model.PlayPlan
import com.lecteur.feature.shared.SharedLibraryViewModel
import com.lecteur.feature.shared.SharedServersViewModel
import com.lecteur.feature.shared.SharedShelves
import com.lecteur.feature.shared.SharedShelves.isCompleted
import com.lecteur.feature.shared.ShelfCard
import com.lecteur.feature.shared.ui.PairingDialog
import com.lecteur.feature.shared.ui.technicalLabel

private val POSTER_WIDTH = 150.dp
private val WIDE_WIDTH = 270.dp

/**
 * The libraries shared by the PCs of the house, on the TV: pick a PC (pairing with the code it shows the first time),
 * then its shelves, movies and series. The TV streams the PC's original file and plays it with its own engine.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvShared(onPlay: (PlayPlan) -> Unit) {
    val servers: SharedServersViewModel = hiltViewModel()
    val library: SharedLibraryViewModel = hiltViewModel()
    val serversUi by servers.ui.collectAsStateWithLifecycle()
    val libraryUi by library.ui.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var seriesId by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(serversUi.openedServerId) {
        serversUi.openedServerId?.let {
            servers.consumeOpened()
            selected = it
        }
    }
    LaunchedEffect(selected) { selected?.let(library::select) }
    BackHandler(enabled = selected != null) { if (seriesId != null) seriesId = null else selected = null }

    // Read from the collected state, so the episodes' progress follows what was just watched
    val series = seriesId?.let { id -> libraryUi.catalog?.series?.firstOrNull { it.id == id } }
    when {
        selected == null -> ServerList(servers, onOpen = { selected = it })
        series != null -> SeriesEpisodes(series, library, onPlay)
        else -> Library(library, onPlay = onPlay, onOpenSeries = { seriesId = it }, onForgotten = { library.forget(); selected = null })
    }
    serversUi.pairing?.let { PairingDialog(it, onSubmit = servers::submitCode, onDismiss = servers::cancelPairing) }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ServerList(viewModel: SharedServersViewModel, onOpen: (String) -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(8.dp)) {
        item { Text("Bibliothèques partagées par vos PC", style = MaterialTheme.typography.headlineSmall) }
        items(ui.paired, key = { it.server.serverId }) { row ->
            Button(onClick = { onOpen(row.server.serverId) }) {
                Text(row.server.name + if (row.online) "" else "  (non détecté)")
            }
        }
        items(ui.nearby, key = { it.serviceName }) { server ->
            OutlinedButton(onClick = { viewModel.startPairing(server) }) { Text("Associer « ${server.name} »") }
        }
        if (ui.paired.isEmpty() && ui.nearby.isEmpty()) item {
            Text(
                "Aucun PC trouvé. Sur le PC, ouvrez Lecteur Média → « Partage » et activez le partage. La TV et le PC doivent être sur le même réseau.",
                style = MaterialTheme.typography.bodyLarge
            )
        }
        item { OutlinedButton(onClick = viewModel::rescan) { Text("Rechercher à nouveau") } }
        ui.message?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium) } }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Library(viewModel: SharedLibraryViewModel, onPlay: (PlayPlan) -> Unit, onOpenSeries: (Long) -> Unit, onForgotten: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val catalog = ui.catalog
    if (catalog == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (val state = ui.state) {
                is CatalogState.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(state.message, style = MaterialTheme.typography.titleMedium)
                    if (state.kind == LibraryException.Kind.UNAUTHORIZED) Button(onClick = onForgotten) { Text("Associer à nouveau") }
                    else Button(onClick = viewModel::refresh) { Text("Réessayer") }
                }
                else -> Text("Chargement de la bibliothèque de ${ui.serverName}…", style = MaterialTheme.typography.titleMedium)
            }
        }
        return
    }
    fun open(card: ShelfCard) {
        when {
            card.episodeId != null || card.movieId != null -> viewModel.planFor(card)?.let(onPlay)
            card.seriesId != null -> onOpenSeries(card.seriesId!!)
        }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { Text(ui.serverName, style = MaterialTheme.typography.headlineSmall) }
        if (ui.continueWatching.isNotEmpty()) item("continue") { Shelf("Reprendre") { cards(ui.continueWatching, wide = true, viewModel::art, ::open) } }
        if (ui.nextUp.isNotEmpty()) item("next") { Shelf("Épisodes suivants") { cards(ui.nextUp, wide = true, viewModel::art, ::open) } }
        if (catalog.movies.isNotEmpty()) item("movies") {
            Shelf("Films (${catalog.movies.size})") {
                cards(catalog.movies.map { m -> ShelfCard("m${m.id}", m.title, m.year?.toString(), m.poster, SharedShelves.movieFile(m)?.watch?.progress ?: 0f, movieId = m.id) }, wide = false, viewModel::art, ::open)
            }
        }
        if (catalog.series.isNotEmpty()) item("series") {
            Shelf("Séries (${catalog.series.size})") {
                cards(catalog.series.map { s -> ShelfCard("s${s.id}", s.title, "${s.episodes.size} épisodes", s.poster, 0f, seriesId = s.id) }, wide = false, viewModel::art, ::open)
            }
        }
        if (catalog.movies.isEmpty() && catalog.series.isEmpty()) item { Text("Ce PC ne partage encore aucun film ni série.", style = MaterialTheme.typography.titleMedium) }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Shelf(title: String, content: LazyListScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 12.dp, horizontal = 8.dp), content = content)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
private fun LazyListScope.cards(cards: List<ShelfCard>, wide: Boolean, art: (String?) -> String?, onClick: (ShelfCard) -> Unit) {
    items(cards, key = { it.key }) { card ->
        Column(Modifier.width(if (wide) WIDE_WIDTH else POSTER_WIDTH)) {
            Card(onClick = { onClick(card) }, modifier = Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 2f / 3f)) {
                Box {
                    PosterImage(art(card.image), card.title, Modifier.fillMaxSize())
                    if (card.progress > 0f) LinearProgressIndicator(progress = { card.progress }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                }
            }
            Text(card.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            card.subtitle?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SeriesEpisodes(series: SeriesDto, viewModel: SharedLibraryViewModel, onPlay: (PlayPlan) -> Unit) {
    val target = SharedShelves.seriesTarget(series)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(series.title, style = MaterialTheme.typography.headlineSmall)
                series.overview?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                if (target != null) Button(onClick = { viewModel.planForEpisode(series, target)?.let(onPlay) }) {
                    Text("Lire " + SharedShelves.episodeLabel(target))
                }
                Spacer(Modifier.height(4.dp))
            }
        }
        series.episodes.groupBy { it.season }.forEach { (season, episodes) ->
            item("season-$season") {
                Text(series.seasons.firstOrNull { it.number == season }?.name ?: if (season == 0) "Épisodes spéciaux" else "Saison $season", style = MaterialTheme.typography.titleLarge)
            }
            items(episodes, key = { it.id }) { episode ->
                val file = episode.files.filter { it.available }.best()
                Card(onClick = { viewModel.planForEpisode(series, episode)?.let(onPlay) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(180.dp).aspectRatio(16f / 9f)) {
                            PosterImage(viewModel.art(episode.still ?: series.backdrop), episode.title ?: "${episode.number}", Modifier.fillMaxSize())
                            val progress = file?.watch?.progress ?: 0f
                            if (progress > 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${episode.number}. ${episode.title ?: "Épisode ${episode.number}"}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(technicalLabel(file), if (episode.isCompleted()) "vu" else null, if (file == null) "indisponible" else null).joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
        }
    }
}
