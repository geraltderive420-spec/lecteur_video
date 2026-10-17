package com.lecteur.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.lecteur.core.model.ExternalLinks
import com.lecteur.core.model.Formatters
import com.lecteur.core.model.LibraryFilters
import com.lecteur.core.model.LibrarySection
import com.lecteur.core.model.MatchState
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.ResolutionTier
import com.lecteur.core.model.label
import com.lecteur.desktop.data.HomeCard

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MovieDetailScreen(id: Long) {
    val a = app()
    val snapshot by a.services.store.snapshot.collectAsState()
    val entry = snapshot.movies[id] ?: return EmptyState(Icons.Outlined.Movie, "Film introuvable", "Il a peut-être été retiré de la bibliothèque.")
    val movie = entry.movie
    var selected by remember(id) { mutableStateOf(entry.files.firstOrNull { it.inProgress && it.file.isAvailable }?.file?.id ?: entry.files.firstOrNull()?.file?.id) }
    val file = entry.files.firstOrNull { it.file.id == selected } ?: entry.files.firstOrNull()
    val tracks = rememberTracks(file?.file?.id)
    val credits = remember(id, snapshot) { a.services.store.credits(movieId = id) }
    val fav = snapshot.isFavorite(MediaKind.MOVIE, id)
    val genres = entry.genreIds.mapNotNull { snapshot.genres[it] }
    val saga = movie.collectionId?.let { cid -> snapshot.movies.values.filter { it.movie.collectionId == cid && it.movie.id != id }.sortedBy { it.movie.year ?: 0 } }.orEmpty()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DetailHeader(movie.backdropPath) {
            Row(Modifier.align(Alignment.BottomStart).padding(start = 40.dp, end = 40.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                DetailPoster(movie.posterPath, movie.title, MediaKind.MOVIE)
                Spacer(Modifier.width(32.dp))
                Column(Modifier.weight(1f)) {
                    if (movie.matchState == MatchState.TO_VERIFY) {
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(6.dp), modifier = Modifier.clickable { a.dialog = AppDialog.FixMatch(MediaKind.MOVIE, id, movie.title, movie.year) }) {
                            Text("Identification à vérifier — cliquer pour corriger", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(movie.title, style = MaterialTheme.typography.displaySmall)
                    movie.originalTitle?.takeIf { it != movie.title }?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Spacer(Modifier.height(8.dp))
                    MetaLine(listOf(movie.year?.toString(), Formatters.runtime(movie.runtimeMinutes) ?: Formatters.runtimeOfMs(file?.file?.durationMs), movie.certification, genres.take(3).joinToString(", ")), movie.rating)
                    Spacer(Modifier.height(12.dp))
                    if (file != null) FileBadges(file, tracks)
                    Spacer(Modifier.height(18.dp))
                    PlayButtons(
                        progress = file?.progress ?: 0f,
                        positionMs = file?.watch?.positionMs ?: 0,
                        available = file?.file?.isAvailable == true,
                        onPlay = { file?.let { a.playFile(it.file.id, fromStart = if (it.progress > 0f) false else null) } },
                        onRestart = { file?.let { a.playFile(it.file.id, fromStart = true) } }
                    ) {
                        ActionIcon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, if (fav) "Retirer des favoris" else "Ajouter aux favoris") { a.toggleFavorite(MediaKind.MOVIE, id) }
                        ActionIcon(if (entry.watched) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle, if (entry.watched) "Marquer comme non vu" else "Marquer comme vu") { a.setWatched(MediaKind.MOVIE, id, !entry.watched) }
                        ActionIcon(Icons.AutoMirrored.Filled.PlaylistAdd, "Ajouter à une liste") { a.dialog = AppDialog.AddToList(MediaKind.MOVIE, id, movie.title) }
                        ActionIcon(Icons.Filled.Cast, "Diffuser sur un écran") { file?.let { a.dialog = AppDialog.CastTo(it.file.id, movie.title) } }
                        MoreMenu(
                            listOfNotNull(
                                movie.trailerKey?.let { "Bande-annonce" to { openInBrowser(ExternalLinks.trailer(it)) } },
                                "Corriger l'identification" to { a.dialog = AppDialog.FixMatch(MediaKind.MOVIE, id, movie.title, movie.year) },
                                movie.tmdbId?.let { "Actualiser les métadonnées" to { a.background { a.services.identifier.refresh(MediaKind.MOVIE, id); a.say("Métadonnées actualisées") } } },
                                movie.imdbId?.let { "Voir sur IMDb" to { openInBrowser(ExternalLinks.imdb(it)) } },
                                movie.tmdbId?.let { "Voir sur TMDB" to { openInBrowser(ExternalLinks.tmdb(MediaKind.MOVIE, it)) } },
                                file?.let { f -> "Afficher dans l'explorateur" to { showInExplorer(f.file.uri) } }
                            )
                        )
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = 40.dp)) {
            Spacer(Modifier.height(16.dp))
            Overview(movie.overview)
            val directors = credits.filter { it.isDirector }
            if (directors.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Réalisation : " + directors.joinToString { it.name }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (entry.files.size > 1) {
                Spacer(Modifier.height(24.dp))
                Text("Versions", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    entry.files.forEach { f ->
                        androidx.compose.material3.FilterChip(
                            selected = f.file.id == file?.file?.id, onClick = { selected = f.file.id },
                            label = { Text(listOfNotNull(ResolutionTier.badge(f.file.width, f.file.height), f.file.hdrType.label(), f.file.videoCodec.label(), Formatters.size(f.file.sizeBytes)).joinToString(" · ")) }
                        )
                    }
                }
            }
        }
        val cast = credits.filter { !it.isDirector }
        if (cast.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            Text("Distribution", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 40.dp))
            Spacer(Modifier.height(10.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 36.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(cast, key = { it.personId.toString() + it.role }) { c -> PersonAvatar(c.name, c.profile, c.role) { a.go(Screen.Person(c.personId)) } }
            }
        }
        if (saga.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            PosterRow(
                title = snapshot.collections[movie.collectionId] ?: "Saga",
                cards = saga.map { HomeCard(snapshot.item(it)) },
                onSeeAll = { a.go(Screen.Library(LibrarySection.MOVIES, LibraryFilters(collectionId = movie.collectionId), snapshot.collections[movie.collectionId])) },
                onCard = { a.open(it.item) },
                onPlay = { a.play(it.item.kind, it.item.id) }
            )
        }
        if (file != null) {
            Column(Modifier.padding(horizontal = 40.dp)) {
                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text("Informations techniques", style = MaterialTheme.typography.titleLarge)
                TechSheet(file, tracks)
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

@Composable
fun PlayButtons(
    progress: Float,
    positionMs: Long,
    available: Boolean,
    playLabel: String? = null,
    onPlay: () -> Unit,
    onRestart: () -> Unit,
    actions: @Composable () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onPlay, enabled = available, contentPadding = PaddingValues(horizontal = 26.dp, vertical = 12.dp)) {
            Icon(Icons.Filled.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text(playLabel ?: if (progress > 0f) "Reprendre à ${formatTime(positionMs)}" else "Lire", style = MaterialTheme.typography.titleMedium)
        }
        if (progress > 0f) {
            FilledTonalButton(onClick = onRestart, enabled = available, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) {
                Icon(Icons.Filled.Replay, null)
                Spacer(Modifier.width(6.dp))
                Text("Depuis le début")
            }
        }
        actions()
    }
    if (progress > 0f) {
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(340.dp).height(4.dp).clip(RoundedCornerShape(2.dp)))
    }
}

@Composable
fun ActionIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Tip(label) { FilledTonalIconButton(onClick = onClick) { Icon(icon, label) } }
}

@Composable
fun MoreMenu(entries: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalIconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "Plus") }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            entries.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() }) }
        }
    }
}

