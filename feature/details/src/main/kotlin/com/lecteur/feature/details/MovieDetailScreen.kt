package com.lecteur.feature.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MovieFilter
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveRedEye
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lecteur.core.designsystem.components.Badge
import com.lecteur.core.designsystem.components.EmptyState
import com.lecteur.core.designsystem.components.LoadingBox
import com.lecteur.core.designsystem.components.PosterCard
import com.lecteur.core.designsystem.components.ListPickerSheet
import com.lecteur.core.designsystem.components.PosterImage
import com.lecteur.core.designsystem.components.RatingLabel
import com.lecteur.core.designsystem.components.SectionHeader
import com.lecteur.core.model.ExternalLinks
import com.lecteur.core.model.Formatters
import com.lecteur.core.model.ImageUrls
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.MovieDetail
import com.lecteur.core.model.PlayPlan
import com.lecteur.core.model.WatchStatus

/** Film page: everything TMDB says, what the file really is, and the actions (play, seen, favourite, correct, refresh). */
@Composable
fun MovieDetailScreen(
    onBack: () -> Unit,
    onPlay: (PlayPlan) -> Unit,
    onOpenPerson: (Long, String) -> Unit,
    onOpenGenre: (Long, String) -> Unit,
    onOpenCollection: (Long, String) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenYear: (Int) -> Unit,
    onCorrect: (MediaKind, Long) -> Unit,
    modifier: Modifier = Modifier,
    /** Opens the cast picker for a file; null hides the entry (screens that cannot cast). */
    onCast: ((Long) -> Unit)? = null,
    viewModel: MovieDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val links = LocalUriHandler.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is DetailEvent.Play -> onPlay(event.plan)
                is DetailEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

    val picker by viewModel.listPicker.state.collectAsStateWithLifecycle()
    picker?.let {
        ListPickerSheet(it, onToggle = viewModel.listPicker::toggle, onCreate = viewModel.listPicker::create, onDismiss = viewModel.listPicker::close)
    }

    Scaffold(modifier = modifier, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (val current = state) {
                DetailState.Loading -> LoadingBox()
                DetailState.Missing -> EmptyState(
                    icon = Icons.Rounded.MovieFilter,
                    title = "Ce film n'existe plus",
                    message = "Il a été fusionné avec un autre titre, ou son dossier a été retiré de la bibliothèque.",
                    actionLabel = "Retour",
                    onAction = onBack
                )
                is DetailState.Ready -> MovieContent(
                    detail = current.value,
                    refreshing = refreshing,
                    onBack = onBack,
                    onPlay = viewModel::play,
                    onToggleWatched = viewModel::toggleWatched,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onAddToList = viewModel::openListPicker,
                    onRefresh = viewModel::refresh,
                    onCorrect = { onCorrect(MediaKind.MOVIE, current.value.movie.id) },
                    onCast = onCast,
                    onOpenLink = links::openUri,
                    onOpenPerson = onOpenPerson,
                    onOpenGenre = onOpenGenre,
                    onOpenCollection = onOpenCollection,
                    onOpenMovie = onOpenMovie,
                    onOpenYear = onOpenYear
                )
            }
        }
    }
}

@Composable
private fun MovieContent(
    detail: MovieDetail,
    refreshing: Boolean,
    onBack: () -> Unit,
    onPlay: (Long?) -> Unit,
    onToggleWatched: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToList: () -> Unit,
    onRefresh: () -> Unit,
    onCorrect: () -> Unit,
    onCast: ((Long) -> Unit)?,
    onOpenLink: (String) -> Unit,
    onOpenPerson: (Long, String) -> Unit,
    onOpenGenre: (Long, String) -> Unit,
    onOpenCollection: (Long, String) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenYear: (Int) -> Unit
) {
    val movie = detail.movie
    var chosenId by remember(detail.versions.map { it.mediaFileId }) { mutableStateOf<Long?>(null) }
    val version = detail.versions.firstOrNull { it.mediaFileId == chosenId && it.isAvailable } ?: detail.defaultVersion
    val canPlay = version != null

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DetailHeader(movie.backdropPath, movie.logoPath, movie.title, onBack)

        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PosterImage(
                ImageUrls.poster(movie.posterPath), movie.title,
                Modifier.width(POSTER_WIDTH).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(movie.title, style = MaterialTheme.typography.headlineSmall)
                if (!movie.originalTitle.isNullOrBlank() && movie.originalTitle != movie.title) {
                    Text(movie.originalTitle!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val year = movie.year ?: Formatters.yearOf(movie.releaseDate)
                    if (year != null) YearLink(year, onOpenYear)
                    val runtime = Formatters.runtime(movie.runtimeMinutes) ?: Formatters.runtimeOfMs(version?.durationMs)
                    if (runtime != null) Text(runtime, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    movie.certification?.takeIf { it.isNotBlank() }?.let { Badge(it) }
                    RatingLabel(movie.rating)
                }
                BadgesLine(version)
                if (detail.watch == WatchStatus.IN_PROGRESS) {
                    LinearProgressIndicator(progress = { detail.progress }, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { onPlay(version?.mediaFileId) }, enabled = canPlay, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Text(
                    when {
                        !canPlay -> "Indisponible"
                        detail.watch == WatchStatus.IN_PROGRESS -> "Reprendre"
                        detail.watch == WatchStatus.WATCHED -> "Revoir"
                        else -> "Lire"
                    },
                    Modifier.padding(start = 8.dp)
                )
            }
            FilledTonalIconButton(onClick = onToggleFavorite) {
                Icon(
                    if (detail.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (detail.isFavorite) "Retirer des favoris" else "Ajouter aux favoris"
                )
            }
            FilledTonalIconButton(onClick = onToggleWatched) {
                val seen = detail.watch == WatchStatus.WATCHED
                Icon(if (seen) Icons.Rounded.VisibilityOff else Icons.Rounded.RemoveRedEye, contentDescription = if (seen) "Marquer comme non vu" else "Marquer comme vu")
            }
            OverflowMenu(
                listOfNotNull(
                    if (onCast != null && version != null) MenuEntry("Diffuser sur un écran") { onCast(version.mediaFileId) } else null,
                    MenuEntry("Ajouter à une liste", onClick = onAddToList),
                    MenuEntry("Corriger l'association", onClick = onCorrect),
                    MenuEntry(if (refreshing) "Actualisation…" else "Actualiser les informations", enabled = !refreshing, onClick = onRefresh),
                    ExternalLinks.imdb(movie.imdbId)?.let { url -> MenuEntry("Voir sur IMDb") { onOpenLink(url) } },
                    ExternalLinks.trailer(movie.trailerKey)?.let { url -> MenuEntry("Bande-annonce") { onOpenLink(url) } },
                    ExternalLinks.tmdb(MediaKind.MOVIE, movie.tmdbId)?.let { url -> MenuEntry("Voir sur TMDB") { onOpenLink(url) } }
                )
            )
        }

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!movie.overview.isNullOrBlank()) ExpandableText(movie.overview!!)
            GenreChips(detail.genres, onClick = { onOpenGenre(it.id, it.name) })
            if (detail.directors.isNotEmpty()) {
                Text(
                    "Réalisé par " + detail.directors.joinToString(", ") { it.name },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (detail.versions.size > 1) VersionPicker(detail.versions, version?.mediaFileId, onSelect = { chosenId = it })
            version?.let { FileInfoBlock(it) }
        }

        Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            CastRow("Réalisation", detail.directors, onOpenPerson)
            CastRow("Distribution", detail.cast, onOpenPerson)
            detail.collection?.takeIf { it.others.isNotEmpty() }?.let { collection ->
                Column {
                    SectionHeader(collection.name, actionLabel = "Tout voir", onAction = { onOpenCollection(collection.id, collection.name) })
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(collection.others, key = { it.key }) { item ->
                            PosterCard(item, Modifier.width(110.dp), onClick = { onOpenMovie(item.id) })
                        }
                    }
                }
            }
        }
        AttributionFooter()
    }
}

private val POSTER_WIDTH = 110.dp
