package com.lecteur.tv.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.lecteur.core.designsystem.components.BadgeRow
import com.lecteur.core.designsystem.components.PosterImage
import com.lecteur.core.model.CastCredit
import com.lecteur.core.model.EpisodeItem
import com.lecteur.core.model.ExternalLinks
import com.lecteur.core.model.FileVersion
import com.lecteur.core.model.Formatters
import com.lecteur.core.model.ImageUrls
import com.lecteur.core.model.SeriesPlayKind
import com.lecteur.core.model.WatchStatus
import com.lecteur.core.model.label
import com.lecteur.tv.TvDetail
import com.lecteur.tv.TvLibraryViewModel
import com.lecteur.tv.TvPage
import com.lecteur.tv.TvRef
import com.lecteur.tv.TvTile

/** A title's page, the same for a film of this TV and a film of a PC: only the source mention differs. */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TvDetailScreen(viewModel: TvLibraryViewModel, ref: TvRef, onOpen: (TvPage) -> Unit) {
    val detail by remember(ref) { viewModel.detail(ref) }.collectAsState(initial = null)
    val favorite by remember(ref) { viewModel.isFavorite(ref) }.collectAsState(initial = false)
    val first = remember(ref) { FocusRequester() }
    var loaded by remember(ref) { mutableStateOf(false) }
    // The opening card is gone: the first button takes the focus as soon as the page has content
    LaunchedEffect(detail != null) {
        if (detail != null && !loaded) {
            loaded = true
            runCatching { first.requestFocus() }
        }
    }

    val current = detail
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Chargement…", style = MaterialTheme.typography.titleMedium) }
        return
    }
    Box(Modifier.fillMaxSize()) {
        val backdrop = when (current) {
            is TvDetail.Film -> current.detail.movie.backdropPath ?: current.detail.movie.posterPath
            is TvDetail.Show -> current.detail.series.backdropPath ?: current.detail.series.posterPath
        }
        if (backdrop != null) {
            PosterImage(ImageUrls.backdrop(backdrop), "", Modifier.fillMaxWidth(0.72f).aspectRatio(16f / 9f).align(Alignment.TopEnd))
            Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0.3f to BACKGROUND, 0.7f to BACKGROUND.copy(alpha = 0.7f), 1f to BACKGROUND.copy(alpha = 0.25f))))
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 0.75f to BACKGROUND)))
        }
        // TV lists pull the focused element to a third of the screen, which pushes the title off the top: scroll only as needed
        CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScroll) {
            when (current) {
                is TvDetail.Film -> FilmPage(viewModel, current, favorite, first, onOpen)
                is TvDetail.Show -> ShowPage(viewModel, current, favorite, first, onOpen)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FilmPage(viewModel: TvLibraryViewModel, page: TvDetail.Film, favorite: Boolean, first: FocusRequester, onOpen: (TvPage) -> Unit) {
    val d = page.detail
    val m = d.movie
    val version = d.defaultVersion
    var versionsOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val resumable = d.progress > 0f && d.watch != WatchStatus.WATCHED

    LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp)) {
        // One item for the header and the buttons: the focus on "Lire" keeps the title on screen
        item("header") {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Header(
                poster = m.posterPath, title = m.title, original = m.originalTitle?.takeIf { it != m.title },
                meta = listOfNotNull(
                    m.year?.toString(), (Formatters.runtimeOfMs(version?.durationMs) ?: Formatters.runtime(m.runtimeMinutes)),
                    m.certification, Formatters.rating(m.rating)?.let { "★ $it" }
                ),
                source = page.source, badges = version?.let(::badgesOf).orEmpty(), genres = d.genres.map { it.name },
                overview = m.overview, progress = if (resumable) d.progress else 0f
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Action(if (resumable) "Reprendre" else "Lire", Icons.Rounded.PlayArrow, primary = true, enabled = version != null, modifier = Modifier.focusRequester(first)) {
                    viewModel.playMovie(page.ref)
                }
                if (resumable) Action("Depuis le début", Icons.Rounded.Replay) { viewModel.playMovie(page.ref, fromStart = true) }
                if (d.versions.size > 1) Action("Versions (${d.versions.size})", Icons.Rounded.VideoLibrary) { versionsOpen = true }
                val seen = d.watch == WatchStatus.WATCHED
                Action(if (seen) "Marquer non vu" else "Marquer vu", if (seen) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility) { viewModel.setWatched(page, !seen) }
                if (page.ref.serverId == null) {
                    Action(if (favorite) "Favori" else "Ajouter aux favoris", if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder) { viewModel.toggleFavorite(page) }
                }
                ExternalLinks.trailer(m.trailerKey)?.let { url -> Action("Bande-annonce", Icons.Rounded.Movie) { openUrl(context, url) } }
            }
            }
        }
        item("info") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (d.directors.isNotEmpty()) InfoLine("Réalisation", d.directors.joinToString { it.name })
                d.collection?.let { InfoLine("Saga", it.name) }
                version?.let { v ->
                    InfoLine("Fichier", v.shortLabel + (Formatters.size(v.sizeBytes).let { " · $it" }))
                    if (v.badges.audioLanguages.isNotEmpty()) InfoLine("Audio", v.badges.audioLanguages.joinToString { languageName(it) })
                    if (v.badges.subtitleLanguages.isNotEmpty()) InfoLine("Sous-titres", v.badges.subtitleLanguages.joinToString { languageName(it) })
                }
            }
        }
        if (d.cast.isNotEmpty()) item("cast") { CastRow(d.directors + d.cast, page.ref, onOpen) }
        d.collection?.takeIf { it.others.isNotEmpty() }?.let { saga ->
            item("saga") {
                TvShelf("Dans la saga ${saga.name}") {
                    posters(saga.others.map { TvTile(TvRef(null, it.kind, it.id), it, null) }) { onOpen(TvPage.Detail(it.ref)) }
                }
            }
        }
    }

    if (versionsOpen) {
        VersionPicker(d.versions, onDismiss = { versionsOpen = false }) { v ->
            versionsOpen = false
            viewModel.playMovie(page.ref, v.mediaFileId)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ShowPage(viewModel: TvLibraryViewModel, page: TvDetail.Show, favorite: Boolean, first: FocusRequester, onOpen: (TvPage) -> Unit) {
    val d = page.detail
    val s = d.series
    val target = d.playTarget
    val seasons = d.seasons.filter { it.inLibraryCount > 0 }
    var seasonNumber by rememberSaveable(page.ref.key) { mutableIntStateOf(target?.episode?.seasonNumber ?: seasons.firstOrNull()?.seasonNumber ?: 1) }
    val season = seasons.firstOrNull { it.seasonNumber == seasonNumber } ?: seasons.firstOrNull()
    var optionsFor by remember { mutableStateOf<EpisodeItem?>(null) }
    var versionsFor by remember { mutableStateOf<EpisodeItem?>(null) }
    val owned = seasons.sumOf { it.inLibraryCount }
    val watchedCount = seasons.sumOf { it.watchedCount }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp)) {
        // One item for the header and the buttons: the focus on "Lire" keeps the title on screen
        item("header") {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Header(
                poster = s.posterPath, title = s.title, original = s.originalTitle?.takeIf { it != s.title },
                meta = listOfNotNull(
                    s.firstAirDate?.take(4), Formatters.episodes(owned) + " · $watchedCount vu" + (if (watchedCount > 1) "s" else ""),
                    s.status, s.certification, Formatters.rating(s.rating)?.let { "★ $it" }
                ),
                source = page.source, badges = emptyList(), genres = d.genres.map { it.name }, overview = s.overview, progress = 0f
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val label = when (target?.kind) {
                    SeriesPlayKind.RESUME -> "Reprendre ${target.episode.label}"
                    SeriesPlayKind.NEXT -> "Lire ${target.episode.label}"
                    SeriesPlayKind.REWATCH -> "Revoir depuis ${target.episode.label}"
                    SeriesPlayKind.START, null -> "Lire" + (target?.episode?.label?.let { " $it" } ?: "")
                }
                Action(label, Icons.Rounded.PlayArrow, primary = true, enabled = target != null, modifier = Modifier.focusRequester(first)) { viewModel.playSeries(page) }
                val seen = d.watch == WatchStatus.WATCHED
                Action(if (seen) "Série non vue" else "Série vue", if (seen) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility) { viewModel.setWatched(page, !seen) }
                if (page.ref.serverId == null) {
                    Action(if (favorite) "Favori" else "Ajouter aux favoris", if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder) { viewModel.toggleFavorite(page) }
                }
            }
            }
        }
        if (seasons.size > 1) item("seasons") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)) {
                items(seasons, key = { it.seasonNumber }) { sd ->
                    FilterChip(selected = sd.seasonNumber == season?.seasonNumber, onClick = { seasonNumber = sd.seasonNumber }) {
                        Text("${sd.title} · ${sd.watchedCount}/${sd.inLibraryCount}")
                    }
                }
            }
        }
        season?.let { sd ->
            item("season-actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(sd.title, style = MaterialTheme.typography.titleLarge)
                    val all = sd.watchedCount == sd.inLibraryCount && sd.inLibraryCount > 0
                    OutlinedButton(onClick = { viewModel.setSeasonWatched(page, sd, !all) }) { Text(if (all) "Saison non vue" else "Saison vue") }
                }
            }
            items(sd.episodes.filter { it.isInLibrary }, key = { "ep-${it.episodeId}" }) { episode ->
                EpisodeCard(episode, onPlay = { viewModel.playEpisode(page.ref, episode) }, onOptions = { optionsFor = episode })
            }
        }
        if (d.cast.isNotEmpty()) item("cast") { CastRow(d.cast, page.ref, onOpen) }
    }

    optionsFor?.let { episode ->
        val seen = episode.watch == WatchStatus.WATCHED
        OptionsDialog(
            title = "${episode.label} · ${episode.title ?: ""}",
            options = buildList {
                add("Lire" to { viewModel.playEpisode(page.ref, episode); Unit })
                if (episode.progress > 0f) add("Depuis le début" to { viewModel.playEpisode(page.ref, episode, fromStart = true); Unit })
                if (episode.versions.size > 1) add("Versions (${episode.versions.size})" to { versionsFor = episode })
                add((if (seen) "Marquer non vu" else "Marquer vu") to { viewModel.setEpisodeWatched(page, episode, !seen); Unit })
            },
            onDismiss = { optionsFor = null }
        )
    }
    versionsFor?.let { episode ->
        VersionPicker(episode.versions, onDismiss = { versionsFor = null }) { v ->
            versionsFor = null
            viewModel.playEpisode(page.ref, episode, v.mediaFileId)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Header(
    poster: String?, title: String, original: String?, meta: List<String>, source: String?, badges: List<String>,
    genres: List<String>, overview: String?, progress: Float
) {
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Box(Modifier.width(200.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp))) {
            PosterImage(ImageUrls.poster(poster), title, Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            original?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.7f)) }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (meta.isNotEmpty()) Text(meta.joinToString("  ·  "), style = MaterialTheme.typography.titleSmall)
                SourcePill(source)
            }
            if (badges.isNotEmpty()) BadgeRow(badges)
            if (genres.isNotEmpty()) Text(genres.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
            if (progress > 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(320.dp).height(4.dp))
            overview?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.8f))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Action(label: String, icon: ImageVector, primary: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        Icon(icon, null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
    if (primary) Button(onClick = onClick, enabled = enabled, modifier = modifier, content = content)
    else OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier, content = content)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text("$label : ", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.6f))
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Actors and directors; a card opens everything they are in, on this TV and on the PCs. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CastRow(cast: List<CastCredit>, ref: TvRef, onOpen: (TvPage) -> Unit) {
    TvShelf("Distribution") {
        items(cast.distinctBy { it.name + it.isDirector }, key = { "${it.personId}-${it.isDirector}-${it.name}" }) { c ->
            Column(Modifier.width(130.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Card(
                    onClick = { onOpen(TvPage.Person(c.name, c.personId.takeIf { ref.serverId == null })) },
                    shape = androidx.tv.material3.CardDefaults.shape(CircleShape),
                    modifier = Modifier.size(110.dp).remembersFocus("cast-${c.personId}-${c.name}")
                ) {
                    PosterImage(ImageUrls.profile(c.profilePath), c.name, Modifier.fillMaxSize())
                }
                Text(c.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                Text(if (c.isDirector) "Réalisation" else c.role.orEmpty(), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White.copy(alpha = 0.6f))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodeCard(episode: EpisodeItem, onPlay: () -> Unit, onOptions: () -> Unit) {
    val version = episode.versions.firstOrNull { it.isAvailable } ?: episode.versions.firstOrNull()
    Card(onClick = { if (episode.isAvailable) onPlay() }, onLongClick = onOptions, modifier = Modifier.fillMaxWidth(0.92f).remembersFocus("ep-${episode.episodeId}")) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(220.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp))) {
                PosterImage(ImageUrls.still(episode.stillPath), episode.title ?: episode.label, Modifier.fillMaxSize())
                if (episode.progress > 0f) LinearProgressIndicator(progress = { episode.progress }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp))
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${episode.episodeNumber}. ${episode.title ?: "Épisode ${episode.episodeNumber}"}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (episode.watch == WatchStatus.WATCHED) {
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Rounded.CheckCircle, "Vu", tint = SourceColor, modifier = Modifier.size(18.dp))
                    }
                }
                Text(
                    listOfNotNull(
                        Formatters.runtimeOfMs(version?.durationMs) ?: Formatters.runtime(episode.runtimeMinutes),
                        version?.badges?.resolution, version?.badges?.hdr?.label(), if (!episode.isAvailable) "indisponible" else null
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f)
                )
                episode.overview?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun VersionPicker(versions: List<FileVersion>, onDismiss: () -> Unit, onPick: (FileVersion) -> Unit) {
    OptionsDialog(
        title = "Choisir une version",
        options = versions.map { v -> (v.shortLabel + " · " + Formatters.size(v.sizeBytes) + if (!v.isAvailable) " (indisponible)" else "") to { onPick(v) } },
        onDismiss = onDismiss
    )
}

/** A list of choices for the remote: one button per line. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun OptionsDialog(title: String, options: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEachIndexed { i, (label, action) ->
                    OutlinedButton(onClick = { onDismiss(); action() }, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) { Text(label) }
                }
            }
        },
        confirmButton = {}
    )
}

private fun badgesOf(v: FileVersion): List<String> = listOfNotNull(v.badges.resolution, v.badges.hdr.label(), v.badges.videoCodec.label(), v.badges.audio)

private fun languageName(code: String): String =
    java.util.Locale(code).getDisplayLanguage(java.util.Locale.FRENCH).takeIf { it.isNotBlank() && it != code }?.replaceFirstChar { it.titlecase() } ?: code

private fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No YouTube nor browser on this TV: nothing to open it with
    }
}

val BACKGROUND = Color(0xFF0F0F12)

/** Brings a focused element into view by the shortest scroll, like on a phone. */
@OptIn(ExperimentalFoundationApi::class)
private val MinimalScroll = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
        offset >= 0 && offset + size <= containerSize -> 0f
        offset < 0 || size > containerSize -> offset
        else -> offset + size - containerSize
    }
}
