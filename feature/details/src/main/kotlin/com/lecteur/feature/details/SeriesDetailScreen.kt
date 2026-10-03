package com.lecteur.feature.details

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lecteur.core.designsystem.components.Badge
import com.lecteur.core.designsystem.components.EmptyState
import com.lecteur.core.designsystem.components.ListPickerSheet
import com.lecteur.core.designsystem.components.LoadingBox
import com.lecteur.core.designsystem.components.PosterImage
import com.lecteur.core.designsystem.components.RatingLabel
import com.lecteur.core.model.EpisodeItem
import com.lecteur.core.model.ExternalLinks
import com.lecteur.core.model.Formatters
import com.lecteur.core.model.ImageUrls
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.PlayPlan
import com.lecteur.core.model.SeasonDetail
import com.lecteur.core.model.SeriesDetail
import com.lecteur.core.model.SeriesPlayKind
import com.lecteur.core.model.WatchStatus

/** Series page: a tab per season, each episode with its picture, progress and seen state, and one "Lire" that knows where you are. */
@Composable
fun SeriesDetailScreen(
    onBack: () -> Unit,
    onPlay: (PlayPlan) -> Unit,
    onOpenPerson: (Long, String) -> Unit,
    onOpenGenre: (Long, String) -> Unit,
    onOpenYear: (Int) -> Unit,
    onCorrect: (MediaKind, Long) -> Unit,
    modifier: Modifier = Modifier,
    /** Opens the cast picker for a file; null hides the entry. */
    onCast: ((Long) -> Unit)? = null,
    viewModel: SeriesDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val picker by viewModel.listPicker.state.collectAsStateWithLifecycle()
    picker?.let {
        ListPickerSheet(it, onToggle = viewModel.listPicker::toggle, onCreate = viewModel.listPicker::create, onDismiss = viewModel.listPicker::close)
    }
    val links = LocalUriHandler.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is DetailEvent.Play -> onPlay(event.plan)
                is DetailEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

    Scaffold(modifier = modifier, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (val current = state) {
                DetailState.Loading -> LoadingBox()
                DetailState.Missing -> EmptyState(
                    icon = Icons.Rounded.Tv,
                    title = "Cette série n'existe plus",
                    message = "Elle a été fusionnée avec une autre, ou son dossier a été retiré de la bibliothèque.",
                    actionLabel = "Retour",
                    onAction = onBack
                )
                is DetailState.Ready -> SeriesContent(
                    detail = current.value,
                    refreshing = refreshing,
                    viewModel = viewModel,
                    onBack = onBack,
                    onCorrect = { onCorrect(MediaKind.SERIES, current.value.series.id) },
                    onCast = onCast,
                    onAddToList = viewModel::openListPicker,
                    onOpenLink = links::openUri,
                    onOpenPerson = onOpenPerson,
                    onOpenGenre = onOpenGenre,
                    onOpenYear = onOpenYear
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeriesContent(
    detail: SeriesDetail,
    refreshing: Boolean,
    viewModel: SeriesDetailViewModel,
    onBack: () -> Unit,
    onCorrect: () -> Unit,
    onCast: ((Long) -> Unit)?,
    onAddToList: () -> Unit,
    onOpenLink: (String) -> Unit,
    onOpenPerson: (Long, String) -> Unit,
    onOpenGenre: (Long, String) -> Unit,
    onOpenYear: (Int) -> Unit
) {
    val series = detail.series
    val seasons = detail.seasons
    // Opens on the season of the episode "Lire" leads to, the first real season otherwise
    val startSeason = detail.playTarget?.episode?.seasonNumber ?: seasons.firstOrNull { it.seasonNumber > 0 }?.seasonNumber ?: seasons.firstOrNull()?.seasonNumber
    var selectedNumber by rememberSaveable { mutableIntStateOf(startSeason ?: 0) }
    val season = seasons.firstOrNull { it.seasonNumber == selectedNumber } ?: seasons.firstOrNull()

    var episodeMenu by remember { mutableStateOf<EpisodeItem?>(null) }
    var showLanguages by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DetailHeader(series.backdropPath, series.logoPath, series.title, onBack)

        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PosterImage(ImageUrls.poster(series.posterPath), series.title, Modifier.width(110.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(series.title, style = MaterialTheme.typography.headlineSmall)
                if (!series.originalTitle.isNullOrBlank() && series.originalTitle != series.title) {
                    Text(series.originalTitle!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val real = seasons.filter { it.seasonNumber > 0 }
                    Formatters.yearOf(series.firstAirDate)?.let { YearLink(it, onOpenYear) }
                    val line = listOfNotNull(
                        if (real.isNotEmpty()) Formatters.seasons(real.size) else null,
                        series.status?.takeIf { it.isNotBlank() }?.let(::statusLabel)
                    ).joinToString(" · ")
                    if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    series.certification?.takeIf { it.isNotBlank() }?.let { Badge(it) }
                    RatingLabel(series.rating)
                }
                val inLibrary = seasons.sumOf { it.inLibraryCount }
                val watched = seasons.sumOf { it.watchedCount }
                if (inLibrary > 0) {
                    Text("$watched / $inLibrary épisodes vus", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(progress = { watched.toFloat() / inLibrary }, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val target = detail.playTarget
            Button(onClick = viewModel::playSeries, enabled = target != null, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Text(playLabel(target?.kind, target?.episode), Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FilledTonalIconButton(onClick = viewModel::toggleFavorite) {
                Icon(
                    if (detail.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (detail.isFavorite) "Retirer des favoris" else "Ajouter aux favoris"
                )
            }
            OverflowMenu(
                listOfNotNull(
                    MenuEntry("Langues préférées de la série") { showLanguages = true },
                    MenuEntry("Marquer toute la série comme vue") { viewModel.setSeriesWatched(true) },
                    MenuEntry("Marquer toute la série comme non vue") { viewModel.setSeriesWatched(false) },
                    MenuEntry("Ajouter à une liste", onClick = onAddToList),
                    MenuEntry("Corriger l'association", onClick = onCorrect),
                    MenuEntry(if (refreshing) "Actualisation…" else "Actualiser les informations", enabled = !refreshing, onClick = viewModel::refresh),
                    ExternalLinks.imdb(series.imdbId)?.let { url -> MenuEntry("Voir sur IMDb") { onOpenLink(url) } },
                    ExternalLinks.tmdb(MediaKind.SERIES, series.tmdbId)?.let { url -> MenuEntry("Voir sur TMDB") { onOpenLink(url) } }
                )
            )
        }

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!series.overview.isNullOrBlank()) ExpandableText(series.overview!!)
            GenreChips(detail.genres, onClick = { onOpenGenre(it.id, it.name) })
            detail.preference?.let { pref ->
                val parts = listOfNotNull(
                    pref.preferredAudioLanguage?.let { "audio : ${languageName(it)}" },
                    pref.preferredSubtitleLanguage?.let { "sous-titres : ${languageName(it)}" }
                )
                if (parts.isNotEmpty()) Text("Langues de cette série — " + parts.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (seasons.isNotEmpty() && season != null) {
            Column(Modifier.padding(top = 20.dp)) {
                ScrollableTabRow(selectedTabIndex = seasons.indexOf(season).coerceAtLeast(0), edgePadding = 8.dp) {
                    seasons.forEach { s ->
                        Tab(selected = s.seasonNumber == season.seasonNumber, onClick = { selectedNumber = s.seasonNumber }, text = { Text(s.title) })
                    }
                }
                SeasonHeader(season, viewModel)
                season.episodes.forEach { episode ->
                    EpisodeRow(
                        episode = episode,
                        onPlay = { viewModel.playEpisode(episode) },
                        onMenu = { episodeMenu = episode }
                    )
                    HorizontalDivider()
                }
            }
        }

        CastRow("Distribution", detail.cast, onOpenPerson)
        AttributionFooter()
    }

    episodeMenu?.let { episode ->
        EpisodeActionsSheet(
            episode = episode,
            onPlay = { fileId -> viewModel.playEpisode(episode, fileId) },
            onToggleWatched = { viewModel.setEpisodeWatched(episode, episode.watch != WatchStatus.WATCHED) },
            onCast = onCast,
            onDismiss = { episodeMenu = null }
        )
    }

    if (showLanguages) {
        LanguagesDialog(
            audio = detail.preference?.preferredAudioLanguage,
            subtitles = detail.preference?.preferredSubtitleLanguage,
            onSave = { audio, subtitles ->
                viewModel.setLanguages(audio, subtitles)
                showLanguages = false
            },
            onDismiss = { showLanguages = false }
        )
    }
}

@Composable
private fun SeasonHeader(season: SeasonDetail, viewModel: SeriesDetailViewModel) {
    val allWatched = season.inLibraryCount > 0 && season.watchedCount == season.inLibraryCount
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${Formatters.episodes(season.episodes.size)} · ${season.watchedCount} / ${season.inLibraryCount} vus",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (season.inLibraryCount > 0) {
            TextButton(onClick = { viewModel.setSeasonWatched(season, !allWatched) }) {
                Text(if (allWatched) "Tout marquer non vu" else "Tout marquer vu")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(episode: EpisodeItem, onPlay: () -> Unit, onMenu: () -> Unit) {
    val playable = episode.isAvailable
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(enabled = episode.isInLibrary, onClick = { if (playable) onPlay() else onMenu() }, onLongClick = onMenu)
            .alpha(if (episode.isInLibrary) 1f else 0.45f)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.width(132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp))) {
            PosterImage(ImageUrls.still(episode.stillPath), episode.label, Modifier.fillMaxSize())
            if (episode.progress > 0f) {
                LinearProgressIndicator(
                    progress = { episode.progress },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    trackColor = Color.Black.copy(alpha = 0.5f)
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${episode.episodeNumber}. ${episode.title ?: "Épisode ${episode.episodeNumber}"}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (episode.watch == WatchStatus.WATCHED) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = "Vu", tint = MaterialTheme.colorScheme.primary)
                }
            }
            val facts = listOfNotNull(
                Formatters.runtime(episode.runtimeMinutes),
                episode.airDate?.takeIf { it.isNotBlank() },
                episode.versions.firstOrNull()?.badges?.resolution,
                when {
                    !episode.isInLibrary -> "Absent de la bibliothèque"
                    !episode.isAvailable -> "Indisponible"
                    else -> null
                }
            ).joinToString(" · ")
            if (facts.isNotEmpty()) Text(facts, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!episode.overview.isNullOrBlank()) {
                Text(episode.overview!!, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpisodeActionsSheet(episode: EpisodeItem, onPlay: (Long?) -> Unit, onToggleWatched: () -> Unit, onCast: ((Long) -> Unit)?, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text("${episode.label} · ${episode.title.orEmpty()}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            val playable = episode.versions.filter { it.isAvailable }
            if (playable.size > 1) {
                playable.forEach { version ->
                    TextButton(onClick = { onDismiss(); onPlay(version.mediaFileId) }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Lire · ${version.shortLabel}") }
                }
            } else if (playable.size == 1) {
                TextButton(onClick = { onDismiss(); onPlay(null) }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Lire") }
            }
            val castable = playable.firstOrNull()
            if (onCast != null && castable != null) {
                TextButton(onClick = { onDismiss(); onCast(castable.mediaFileId) }, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(if (playable.size > 1) "Diffuser · ${castable.shortLabel}" else "Diffuser sur un écran")
                }
            }
            if (episode.isInLibrary) {
                TextButton(onClick = { onDismiss(); onToggleWatched() }, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(if (episode.watch == WatchStatus.WATCHED) "Marquer comme non vu" else "Marquer comme vu")
                }
            }
        }
    }
}

private val LANGUAGE_CHOICES = listOf("fr" to "Français", "en" to "Anglais", "ja" to "Japonais", "es" to "Espagnol", "de" to "Allemand", "it" to "Italien", "ko" to "Coréen", "pt" to "Portugais")

private fun languageName(code: String): String = LANGUAGE_CHOICES.firstOrNull { it.first == code }?.second ?: code

@Composable
private fun LanguagesDialog(audio: String?, subtitles: String?, onSave: (String?, String?) -> Unit, onDismiss: () -> Unit) {
    var audioChoice by remember { mutableStateOf(audio) }
    var subtitleChoice by remember { mutableStateOf(subtitles) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Langues de la série") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Remplacent vos langues habituelles pour cette série seulement.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LanguageGroup("Audio", audioChoice) { audioChoice = it }
                LanguageGroup("Sous-titres", subtitleChoice) { subtitleChoice = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(audioChoice, subtitleChoice) }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageGroup(title: String, selected: String?, onSelect: (String?) -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected == null, { onSelect(null) }, { Text("Réglage habituel") })
        LANGUAGE_CHOICES.forEach { (code, name) ->
            FilterChip(selected == code, { onSelect(code) }, { Text(name) })
        }
    }
}

private fun playLabel(kind: SeriesPlayKind?, episode: EpisodeItem?): String = when {
    kind == null || episode == null -> "Indisponible"
    kind == SeriesPlayKind.START -> "Lire ${episode.label}"
    kind == SeriesPlayKind.RESUME -> "Reprendre ${episode.label}"
    kind == SeriesPlayKind.NEXT -> "Épisode suivant ${episode.label}"
    else -> "Revoir depuis ${episode.label}"
}

private fun statusLabel(status: String): String = when (status.lowercase()) {
    "returning series" -> "En cours"
    "ended" -> "Terminée"
    "canceled", "cancelled" -> "Annulée"
    "in production" -> "En production"
    "planned" -> "Prévue"
    else -> status
}
