package com.lecteur.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lecteur.core.data.library.ContinueItem
import com.lecteur.core.data.library.NextUpItem
import com.lecteur.core.designsystem.components.PosterImage
import com.lecteur.core.model.ImageUrls
import com.lecteur.core.model.LibraryItem
import com.lecteur.tv.TvViewModel
import kotlinx.coroutines.flow.StateFlow

private val CARD_WIDTH = 160.dp
private val WIDE_CARD_WIDTH = 280.dp

/**
 * Home rows for the remote: continue, next episodes, recently added. Cards are tv-material [Card]s, which draw their own
 * focus border and scale, so the focused item is always visible from across the room.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvHome(viewModel: TvViewModel) {
    val ui by viewModel.homeUi.collectAsStateWithLifecycle()

    if (ui.loaded && ui.continueItems.isEmpty() && ui.nextUp.isEmpty() && ui.recent.isEmpty()) {
        EmptyLibrary(hasFolders = ui.hasFolders)
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(24.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
        if (ui.continueItems.isNotEmpty()) item("continue") {
            Row("Reprendre la lecture") {
                items(ui.continueItems, key = { it.mediaFileId }) { WideCard(it, onClick = { viewModel.playFile(it.mediaFileId) }) }
            }
        }
        if (ui.nextUp.isNotEmpty()) item("next") {
            Row("Prochains épisodes") {
                items(ui.nextUp, key = { it.episodeId }) { NextUpCard(it, onClick = { viewModel.playFile(it.mediaFileId) }) }
            }
        }
        if (ui.recent.isNotEmpty()) item("recent") {
            Row("Ajoutés récemment") {
                items(ui.recent, key = { it.key }) { PosterTile(it, onClick = { viewModel.playItem(it) }) }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Row(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 12.dp, horizontal = 8.dp), content = content)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun WideCard(item: ContinueItem, onClick: () -> Unit) {
    Column(Modifier.width(WIDE_CARD_WIDTH)) {
        Card(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            Box {
                PosterImage(ImageUrls.backdrop(item.imagePath), item.title, Modifier.fillMaxSize())
                LinearProgressIndicator(
                    progress = { item.progress },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                )
            }
        }
        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        item.subtitle?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall) }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NextUpCard(item: NextUpItem, onClick: () -> Unit) {
    Column(Modifier.width(WIDE_CARD_WIDTH)) {
        Card(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            PosterImage(ImageUrls.still(item.imagePath), item.seriesTitle, Modifier.fillMaxSize())
        }
        Text(item.seriesTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PosterTile(item: LibraryItem, onClick: () -> Unit) {
    Column(Modifier.width(CARD_WIDTH)) {
        Card(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            PosterImage(ImageUrls.poster(item.posterPath), item.title, Modifier.fillMaxSize())
        }
        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
    }
}

/** A poster grid for a whole section (films or series). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvGrid(items: StateFlow<List<LibraryItem>>, emptyText: String, onPlay: (LibraryItem) -> Unit) {
    val list by items.collectAsStateWithLifecycle()
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(emptyText, style = MaterialTheme.typography.titleMedium)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(CARD_WIDTH),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(8.dp)
    ) {
        items(list, key = { it.key }) { PosterTile(it, onClick = { onPlay(it) }) }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EmptyLibrary(hasFolders: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (hasFolders) "Analyse de la bibliothèque en cours…" else "La bibliothèque de cette TV est vide.",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                "Vous pouvez ajouter des dossiers dans l'onglet « Dossiers », ou envoyer un film depuis votre téléphone : " +
                    "choisissez « Diffuser sur un écran » et saisissez le code affiché en haut à droite.",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
