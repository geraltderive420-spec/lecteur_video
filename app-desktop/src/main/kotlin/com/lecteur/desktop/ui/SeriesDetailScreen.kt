package com.lecteur.desktop.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lecteur.core.model.ExternalLinks
import com.lecteur.core.model.Formatters
import com.lecteur.core.model.ImageUrls
import com.lecteur.core.model.MatchState
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.SeriesPlayKind
import com.lecteur.desktop.data.EpisodeEntry
import com.lecteur.desktop.data.SeriesEntry
import com.lecteur.desktop.data.seriesPlayTarget

@Composable
fun SeriesDetailScreen(id: Long) {
    val a = app()
    val snapshot by a.services.store.snapshot.collectAsState()
    val entry = snapshot.series[id] ?: return EmptyState(Icons.Outlined.Tv, "Série introuvable", "Elle a peut-être été retirée de la bibliothèque.")
    val series = entry.series
    val target = remember(entry) { seriesPlayTarget(entry) }
    val seasons = remember(entry) { entry.episodes.groupBy { it.episode.seasonNumber }.toSortedMap(compareBy { if (it == 0) Int.MAX_VALUE else it }) }
    var season by remember(id) { mutableStateOf(target?.episode?.episode?.seasonNumber ?: seasons.keys.firstOrNull() ?: 1) }
    val credits = remember(id, snapshot) { a.services.store.credits(seriesId = id) }
    val fav = snapshot.isFavorite(MediaKind.SERIES, id)
    val genres = entry.genreIds.mapNotNull { snapshot.genres[it] }
    val trailer = remember(id, snapshot) { a.services.store.seriesTrailer(id) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DetailHeader(series.backdropPath) {
            Row(Modifier.align(Alignment.BottomStart).padding(start = 40.dp, end = 40.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                DetailPoster(series.posterPath, series.title, MediaKind.SERIES)
                Spacer(Modifier.width(32.dp))
                Column(Modifier.weight(1f)) {
                    if (series.matchState == MatchState.TO_VERIFY) {
                        TextButton(onClick = { a.dialog = AppDialog.FixMatch(MediaKind.SERIES, id, series.title, entry.year) }) { Text("Identification à vérifier — corriger") }
                    }
                    Text(series.title, style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(8.dp))
                    MetaLine(
                        listOf(entry.year?.toString(), Formatters.seasons(seasons.keys.count { it > 0 }), "${entry.watchedCount}/${entry.owned.size} vus", series.certification, genres.take(3).joinToString(", "), statusLabel(series.status)),
                        series.rating
                    )
                    Spacer(Modifier.height(18.dp))
                    val bestFile = target?.episode?.bestFile
                    PlayButtons(
                        progress = if (target?.kind == SeriesPlayKind.RESUME) bestFile?.progress ?: 0f else 0f,
                        positionMs = bestFile?.watch?.positionMs ?: 0,
                        available = target != null,
                        playLabel = target?.let {
                            val label = "S%02dE%02d".format(it.episode.episode.seasonNumber, it.episode.episode.episodeNumber)
                            when (it.kind) {
                                SeriesPlayKind.START -> "Lire $label"
                                SeriesPlayKind.RESUME -> "Reprendre $label"
                                SeriesPlayKind.NEXT -> "Épisode suivant · $label"
                                SeriesPlayKind.REWATCH -> "Revoir depuis $label"
                            }
                        },
                        onPlay = { target?.let { a.playEpisode(id, it.episode.episode.id) } },
                        onRestart = { target?.let { a.playEpisode(id, it.episode.episode.id, fromStart = true) } }
                    ) {
                        val allWatched = entry.owned.isNotEmpty() && entry.owned.all { it.watched }
                        ActionIcon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, if (fav) "Retirer des favoris" else "Ajouter aux favoris") { a.toggleFavorite(MediaKind.SERIES, id) }
                        ActionIcon(if (allWatched) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle, if (allWatched) "Tout marquer non vu" else "Tout marquer vu") { a.setWatched(MediaKind.SERIES, id, !allWatched) }
                        ActionIcon(Icons.AutoMirrored.Filled.PlaylistAdd, "Ajouter à une liste") { a.dialog = AppDialog.AddToList(MediaKind.SERIES, id, series.title) }
                        ActionIcon(Icons.Filled.Cast, "Diffuser sur un écran") { a.castItem(MediaKind.SERIES, id) }
                        MoreMenu(
                            listOfNotNull(
                                trailer?.let { "Bande-annonce" to { openInBrowser(ExternalLinks.trailer(it)) } },
                                "Corriger l'identification" to { a.dialog = AppDialog.FixMatch(MediaKind.SERIES, id, series.title, entry.year) },
                                series.tmdbId?.let { "Actualiser les métadonnées" to { a.background { a.services.identifier.refresh(MediaKind.SERIES, id); a.say("Métadonnées actualisées") } } },
                                series.imdbId?.let { "Voir sur IMDb" to { openInBrowser(ExternalLinks.imdb(it)) } },
                                series.tmdbId?.let { "Voir sur TMDB" to { openInBrowser(ExternalLinks.tmdb(MediaKind.SERIES, it)) } }
                            )
                        )
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = 40.dp)) {
            Spacer(Modifier.height(16.dp))
            Overview(series.overview)
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(seasons.keys.toList()) { n ->
                        val name = entry.seasons.firstOrNull { it.seasonNumber == n }?.name?.takeIf { it.isNotBlank() } ?: if (n == 0) "Épisodes spéciaux" else "Saison $n"
                        val eps = seasons[n].orEmpty().filter { it.inLibrary }
                        FilterChip(season == n, { season = n }, { Text(if (eps.isEmpty()) name else "$name  ${eps.count { it.watched }}/${eps.size}") })
                    }
                }
                val current = seasons[season].orEmpty().filter { it.inLibrary }
                val seasonWatched = current.isNotEmpty() && current.all { it.watched }
                TextButton(onClick = { a.setEpisodesWatched(current.flatMap { e -> e.files.map { it.file.id } }, !seasonWatched) }) {
                    Text(if (seasonWatched) "Saison non vue" else "Marquer la saison vue")
                }
            }
            Spacer(Modifier.height(12.dp))
            seasons[season].orEmpty().forEach { ep -> EpisodeRow(entry, ep, isTarget = ep.episode.id == target?.episode?.episode?.id) }
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
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun EpisodeRow(series: SeriesEntry, ep: EpisodeEntry, isTarget: Boolean) {
    val a = app()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var expanded by remember { mutableStateOf(false) }
    val best = ep.bestFile
    val e = ep.episode
    val menu = {
        listOfNotNull(
            ContextMenuItem("Lire") { a.playEpisode(series.series.id, e.id) }.takeIf { ep.available },
            ContextMenuItem("Lire depuis le début") { a.playEpisode(series.series.id, e.id, fromStart = true) }.takeIf { ep.available },
            ContextMenuItem(if (ep.watched) "Marquer comme non vu" else "Marquer comme vu") { a.setEpisodesWatched(ep.files.map { it.file.id }, !ep.watched) }.takeIf { ep.inLibrary },
            ContextMenuItem("Diffuser sur un écran…") { best?.let { a.dialog = AppDialog.CastTo(it.file.id, "${series.series.title} ${LibrarySnapshotLabel(ep)}") } }.takeIf { ep.available },
            ContextMenuItem("Afficher dans l'explorateur") { best?.let { showInExplorer(it.file.uri) } }.takeIf { ep.inLibrary }
        )
    }
    ContextMenuArea(items = menu) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp))
                .background(if (hovered || isTarget) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent)
                .hoverable(interaction).clickable { if (ep.available) a.playEpisode(series.series.id, e.id) else expanded = !expanded }
                .alpha(if (ep.inLibrary) 1f else 0.45f).padding(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(200.dp).height(112.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    RemoteImage(ImageUrls.still(e.stillPath), Modifier.fillMaxSize())
                    if (hovered && ep.available) Icon(Icons.Filled.PlayArrow, "Lire", tint = Color.White, modifier = Modifier.align(Alignment.Center).size(44.dp).background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(22.dp)))
                    if (ep.progress > 0f) LinearProgressIndicator(progress = { ep.progress }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp))
                }
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${e.episodeNumber}. ${e.title ?: "Épisode ${e.episodeNumber}"}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (ep.watched) {
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Filled.CheckCircle, "Vu", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    }
                    Text(
                        listOfNotNull(e.airDate, Formatters.runtime(e.runtimeMinutes) ?: Formatters.runtimeOfMs(best?.file?.durationMs),
                            if (!ep.inLibrary) "Absent de la bibliothèque" else if (!ep.available) "Hors ligne" else null,
                            if (ep.files.size > 1) "${ep.files.size} versions" else null).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    e.overview?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f))
                    }
                }
            }
        }
    }
}

/** TMDB gives the status in English whatever the language asked. */
private fun statusLabel(status: String?): String? = when (status) {
    "Ended" -> "Terminée"
    "Returning Series" -> "En cours"
    "Canceled" -> "Annulée"
    "In Production" -> "En production"
    "Planned" -> "Annoncée"
    "Pilot" -> "Pilote"
    else -> status
}

@Suppress("FunctionName")
private fun LibrarySnapshotLabel(ep: EpisodeEntry) = "S%02dE%02d".format(ep.episode.seasonNumber, ep.episode.episodeNumber)
