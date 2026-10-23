package com.lecteur.feature.shared.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lecteur.core.common.library.EpisodeDto
import com.lecteur.core.common.library.FileDto
import com.lecteur.core.common.library.best
import com.lecteur.core.common.library.progress
import com.lecteur.core.designsystem.components.LoadingBox
import com.lecteur.core.designsystem.components.PosterImage
import com.lecteur.core.model.PlayPlan
import com.lecteur.feature.shared.SharedLibraryViewModel
import com.lecteur.feature.shared.SharedShelves

/** "4K HDR10", "1080p", for the line under the title. */
fun technicalLabel(file: FileDto?): String? {
    file ?: return null
    val height = file.height ?: return null
    val resolution = when {
        height >= 2000 -> "4K"
        height >= 1000 -> "1080p"
        height >= 700 -> "720p"
        else -> "${height}p"
    }
    val hdr = file.hdr?.let { if (it == "DOLBY_VISION") "Dolby Vision" else it.replace('_', ' ') }
    return listOfNotNull(resolution, hdr).joinToString(" ")
}

fun durationLabel(ms: Long?): String? {
    if (ms == null || ms <= 0) return null
    val minutes = ms / 60_000
    return if (minutes >= 60) "${minutes / 60} h ${"%02d".format(minutes % 60)}" else "$minutes min"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedMovieScreen(movieId: Long, onBack: () -> Unit, onPlay: (PlayPlan) -> Unit, viewModel: SharedLibraryViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val movie = ui.catalog?.movies?.firstOrNull { it.id == movieId }
    Scaffold(topBar = { TopAppBar(title = {}, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Retour") } }) }) { padding ->
        if (movie == null) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        val file = SharedShelves.movieFile(movie)
        val watch = file?.watch
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                    PosterImage(viewModel.art(movie.backdrop ?: movie.poster), movie.title, Modifier.fillMaxSize())
                }
            }
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(movie.title, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        listOfNotNull(movie.year?.toString(), durationLabel(file?.durationMs ?: movie.runtimeMinutes?.let { it * 60_000L }), technicalLabel(file), movie.genres.take(3).joinToString(", ").ifBlank { null })
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (watch != null && watch.progress > 0f) {
                        LinearProgressIndicator(progress = { watch.progress }, modifier = Modifier.fillMaxWidth())
                    }
                    Button(enabled = file?.available == true, onClick = { viewModel.planForMovie(movie)?.let(onPlay) }) {
                        Icon(Icons.Rounded.PlayArrow, null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                file?.available != true -> "Indisponible (disque débranché sur le PC)"
                                watch != null && watch.progress > 0f -> "Reprendre"
                                else -> "Lire"
                            }
                        )
                    }
                    if (watch?.completed == true) Text("Déjà vu", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    movie.overview?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedSeriesScreen(seriesId: Long, onBack: () -> Unit, onPlay: (PlayPlan) -> Unit, viewModel: SharedLibraryViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val series = ui.catalog?.series?.firstOrNull { it.id == seriesId }
    Scaffold(topBar = { TopAppBar(title = { Text(series?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Retour") } }) }) { padding ->
        if (series == null) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        val seasons = series.episodes.map { it.season }.distinct().sorted()
        val target = SharedShelves.seriesTarget(series)
        var season by rememberSaveable(seriesId) { mutableIntStateOf(target?.season ?: seasons.firstOrNull() ?: 1) }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) { PosterImage(viewModel.art(series.backdrop ?: series.poster), series.title, Modifier.fillMaxSize()) }
            }
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        listOfNotNull(series.year?.toString(), "${series.episodes.size} épisodes", series.genres.take(3).joinToString(", ").ifBlank { null }).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (target != null) {
                        Button(onClick = { viewModel.planForEpisode(series, target)?.let(onPlay) }) {
                            Icon(Icons.Rounded.PlayArrow, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Lire " + "S%02dE%02d".format(target.season, target.number))
                        }
                    }
                    series.overview?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis) }
                    if (seasons.size > 1) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            seasons.forEach { n ->
                                val name = series.seasons.firstOrNull { it.number == n }?.name ?: if (n == 0) "Épisodes spéciaux" else "Saison $n"
                                FilterChip(selected = season == n, onClick = { season = n }, label = { Text(name) })
                            }
                        }
                    }
                }
            }
            items(series.episodes.filter { it.season == season }, key = { it.id }) { episode ->
                EpisodeRow(episode, viewModel.art(episode.still ?: series.backdrop)) { viewModel.planForEpisode(series, episode)?.let(onPlay) }
            }
        }
    }
}

@Composable
private fun EpisodeRow(episode: EpisodeDto, still: String?, onPlay: () -> Unit) {
    val file = episode.files.filter { it.available }.best() ?: episode.files.best()
    val watch = file?.watch
    Row(
        Modifier.fillMaxWidth().clickable(enabled = file?.available == true, onClick = onPlay).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(140.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp))) {
            PosterImage(still, episode.title ?: "${episode.number}", Modifier.fillMaxSize())
            if (watch != null && watch.progress > 0f) {
                LinearProgressIndicator(progress = { watch.progress }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${episode.number}. ${episode.title ?: "Épisode ${episode.number}"}", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(durationLabel(file?.durationMs), technicalLabel(file), if (file?.available == false) "indisponible" else null).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (watch?.completed == true) Icon(Icons.Rounded.CheckCircle, "Vu", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    }
}
