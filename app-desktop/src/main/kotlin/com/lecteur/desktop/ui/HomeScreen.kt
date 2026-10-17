package com.lecteur.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lecteur.core.model.HomeLayout
import com.lecteur.core.model.HomeRow
import com.lecteur.core.model.ImageUrls
import com.lecteur.core.model.LibraryFilters
import com.lecteur.core.model.LibrarySection
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.WatchFilter
import com.lecteur.desktop.data.HomeCard
import com.lecteur.desktop.data.LibrarySnapshot

@Composable
fun HomeScreen() {
    val a = app()
    val snapshot by a.services.store.snapshot.collectAsState()
    val settings by a.services.settings.state.collectAsState()
    val layout = remember(settings.homeLayout) { HomeLayout.decode(settings.homeLayout) }

    if (snapshot.isEmpty) {
        val folders = snapshot.folders
        EmptyState(
            Icons.Filled.FolderOpen,
            if (folders.isEmpty()) "Bienvenue dans Lecteur Média" else "Bibliothèque vide",
            if (folders.isEmpty()) "Ajoutez les dossiers où sont rangés vos films et séries : ils seront analysés, identifiés et classés automatiquement."
            else "Aucune vidéo trouvée pour l'instant. Lancez une analyse des dossiers."
        ) {
            Button(onClick = { a.go(Screen.Folders) }) { Text(if (folders.isEmpty()) "Ajouter un dossier" else "Gérer les dossiers") }
        }
        return
    }

    val rows: List<Pair<HomeRow, List<HomeCard>>> = remember(snapshot, layout) {
        layout.visibleRows.map { row ->
            row to when (row) {
                HomeRow.CONTINUE -> snapshot.continueWatching()
                HomeRow.NEXT_UP -> snapshot.nextUp()
                HomeRow.RECENT -> snapshot.recentlyAdded()
                HomeRow.MOVIES -> snapshot.query(com.lecteur.core.model.LibraryQuery(LibrarySection.MOVIES, limit = 25)).map { HomeCard(it) }
                HomeRow.SERIES -> snapshot.query(com.lecteur.core.model.LibraryQuery(LibrarySection.SERIES, limit = 25)).map { HomeCard(it) }
                HomeRow.UNWATCHED -> snapshot.unwatched()
                HomeRow.FAVORITES -> snapshot.favorites()
            }
        }
    }
    val hero = remember(snapshot) { heroOf(snapshot) }

    LazyColumn(Modifier.fillMaxSize()) {
        if (hero != null) item { Hero(hero) }
        items(rows, key = { it.first.name }) { (row, cards) ->
            PosterRow(
                title = row.title,
                cards = cards,
                onSeeAll = when (row) {
                    HomeRow.MOVIES -> ({ a.root(Screen.Library(LibrarySection.MOVIES)) })
                    HomeRow.SERIES -> ({ a.root(Screen.Library(LibrarySection.SERIES)) })
                    HomeRow.UNWATCHED -> ({ a.go(Screen.Library(LibrarySection.MOVIES, LibraryFilters(watch = WatchFilter.UNWATCHED), "Non vus")) })
                    HomeRow.FAVORITES -> ({ a.go(Screen.Lists) })
                    else -> null
                },
                onCard = { card -> a.open(card.item) },
                onPlay = { card ->
                    when {
                        card.mediaFileId != null -> a.playFile(card.mediaFileId)
                        else -> a.play(card.item.kind, card.item.id)
                    }
                }
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private data class HeroData(val card: HomeCard, val backdrop: String?, val overview: String?, val label: String)

/** The big banner: what the user is watching, else the latest addition with artwork. */
private fun heroOf(s: LibrarySnapshot): HeroData? {
    fun data(card: HomeCard, label: String): HeroData? {
        val (backdrop, overview) = if (card.item.kind == MediaKind.MOVIE) {
            s.movies[card.item.id]?.movie?.let { it.backdropPath to it.overview }
        } else {
            s.series[card.item.id]?.series?.let { it.backdropPath to it.overview }
        } ?: (null to null)
        return if (backdrop == null) null else HeroData(card, backdrop, overview, label)
    }
    s.continueWatching(5).firstNotNullOfOrNull { data(it, "Reprendre") }?.let { return it }
    s.nextUp(5).firstNotNullOfOrNull { data(it, "Épisode suivant") }?.let { return it }
    return s.recentlyAdded(10).firstNotNullOfOrNull { data(it, "Récemment ajouté") }
}

@Composable
private fun Hero(hero: HeroData) {
    val a = app()
    Box(Modifier.fillMaxWidth().height(420.dp)) {
        RemoteImage(ImageUrls.url(hero.backdrop, "w1280"), Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to MaterialTheme.colorScheme.background, 0.55f to MaterialTheme.colorScheme.background.copy(alpha = 0.55f), 1f to Color.Transparent)
            )
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to MaterialTheme.colorScheme.background)))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 32.dp, bottom = 36.dp).widthIn(max = 620.dp)) {
            Text(hero.label.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(hero.card.item.title, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            hero.card.subtitle?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            hero.overview?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f))
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { hero.card.mediaFileId?.let { a.playFile(it) } ?: a.play(hero.card.item.kind, hero.card.item.id) }) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (hero.card.progress > 0f) "Reprendre" else "Lire")
                }
                FilledTonalButton(onClick = { a.open(hero.card.item) }) {
                    Icon(Icons.Filled.Info, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Détails")
                }
            }
        }
    }
}
