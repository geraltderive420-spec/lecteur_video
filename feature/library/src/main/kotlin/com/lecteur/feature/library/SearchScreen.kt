package com.lecteur.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lecteur.core.data.playback.PlayPlanner
import com.lecteur.core.data.search.FileHit
import com.lecteur.core.data.search.PersonHit
import com.lecteur.core.data.search.SearchRepository
import com.lecteur.core.data.search.SearchResults
import com.lecteur.core.data.search.TitleHit
import com.lecteur.core.designsystem.components.EmptyState
import com.lecteur.core.designsystem.components.PosterImage
import com.lecteur.core.model.ImageUrls
import com.lecteur.core.model.PlayPlan
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUi(val query: String = "", val results: SearchResults? = null)

sealed interface SearchEvent {
    data class Play(val plan: PlayPlan) : SearchEvent
    data class Message(val text: String) : SearchEvent
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    search: SearchRepository,
    private val planner: PlayPlanner
) : ViewModel() {

    private val query = MutableStateFlow("")

    val state: StateFlow<SearchUi> = combine(
        query,
        query.debounce(DEBOUNCE_MS).distinctUntilChanged().flatMapLatest { text ->
            if (text.isBlank()) flowOf(null) else search.search(text)
        }
    ) { text, results ->
        // Results of an earlier keystroke stay up until the new ones arrive: no flicker between letters
        SearchUi(text, results)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUi())

    private val eventChannel = Channel<SearchEvent>(Channel.BUFFERED)
    val events: Flow<SearchEvent> = eventChannel.receiveAsFlow()

    fun onQuery(text: String) {
        query.value = text
    }

    fun playFile(hit: FileHit) {
        viewModelScope.launch {
            val plan = planner.forFile(hit.mediaFileId)
            eventChannel.send(if (plan != null) SearchEvent.Play(plan) else SearchEvent.Message("Ce fichier n'est pas disponible : le support est peut-être débranché."))
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 120L
    }
}

/** Global search: titles, actors and directors, and file names, as you type. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenSeries: (Long) -> Unit,
    onOpenPerson: (personId: Long, name: String) -> Unit,
    onPlay: (PlayPlan) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SearchEvent.Play -> onPlay(event.plan)
                is SearchEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

    Scaffold(
        modifier = modifier.imePadding(),
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Retour") } },
                title = {
                    TextField(
                        value = ui.query,
                        onValueChange = viewModel::onQuery,
                        placeholder = { Text("Titre, acteur, nom de fichier") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    )
                },
                actions = {
                    if (ui.query.isNotEmpty()) IconButton(onClick = { viewModel.onQuery("") }) { Icon(Icons.Rounded.Close, contentDescription = "Effacer") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        val results = ui.results
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                ui.query.isBlank() -> EmptyState(
                    icon = Icons.Rounded.Search,
                    title = "Rechercher dans la bibliothèque",
                    message = "Tapez un titre, le nom d'un acteur ou d'un réalisateur, ou un morceau d'un nom de fichier. Les accents n'ont pas d'importance."
                )
                results == null -> Unit
                results.isEmpty -> EmptyState(
                    icon = Icons.Rounded.SearchOff,
                    title = "Aucun résultat pour « ${results.query.trim()} »",
                    message = "Essayez un autre mot, ou moins de mots."
                )
                else -> ResultsList(results, onOpenMovie, onOpenSeries, onOpenPerson, viewModel::playFile)
            }
        }
    }
}

@Composable
private fun ResultsList(
    results: SearchResults,
    onOpenMovie: (Long) -> Unit,
    onOpenSeries: (Long) -> Unit,
    onOpenPerson: (Long, String) -> Unit,
    onPlayFile: (FileHit) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (results.movies.isNotEmpty()) {
            item(key = "h-movies") { Header("Films") }
            items(results.movies, key = { "m${it.id}" }) { TitleRow(it) { onOpenMovie(it.id) } }
        }
        if (results.series.isNotEmpty()) {
            item(key = "h-series") { Header("Séries") }
            items(results.series, key = { "s${it.id}" }) { TitleRow(it) { onOpenSeries(it.id) } }
        }
        if (results.people.isNotEmpty()) {
            item(key = "h-people") { Header("Personnes") }
            items(results.people, key = { "p${it.personId}" }) { PersonRow(it) { onOpenPerson(it.personId, it.name) } }
        }
        if (results.files.isNotEmpty()) {
            item(key = "h-files") { Header("Fichiers") }
            items(results.files, key = { "f${it.mediaFileId}" }) { FileRow(it) { onPlayFile(it) } }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun TitleRow(hit: TitleHit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        PosterImage(ImageUrls.poster(hit.posterPath), hit.title, Modifier.width(44.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)))
        Column(Modifier.padding(start = 14.dp)) {
            Text(hit.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val line = listOfNotNull(hit.year?.toString(), hit.matchedOriginalTitle?.let { "Titre original : $it" }).joinToString(" · ")
            if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PersonRow(hit: PersonHit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        PosterImage(ImageUrls.profile(hit.profilePath), hit.name, Modifier.size(44.dp).clip(CircleShape))
        Column(Modifier.padding(start = 14.dp)) {
            Text(hit.name, style = MaterialTheme.typography.titleSmall)
            Text(
                (if (hit.isDirector) "Réalisateur · " else "") + "${hit.titleCount} titre${if (hit.titleCount > 1) "s" else ""} dans la bibliothèque",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FileRow(hit: FileHit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = hit.isAvailable, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.padding(start = 16.dp)) {
            Text(hit.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    !hit.isAvailable -> "Indisponible"
                    hit.kind == null -> "Non identifié · toucher pour lire"
                    else -> "Toucher pour lire"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
