package com.lecteur.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lecteur.core.data.library.ContinueItem
import com.lecteur.core.data.library.NextUpItem
import com.lecteur.core.designsystem.components.EmptyState
import com.lecteur.core.designsystem.components.ItemActionsSheet
import com.lecteur.core.designsystem.components.ListPickerSheet
import com.lecteur.core.designsystem.components.LandscapeCard
import com.lecteur.core.designsystem.components.LoadingBox
import com.lecteur.core.designsystem.components.PosterCard
import com.lecteur.core.designsystem.components.SectionHeader
import com.lecteur.core.model.HomeRow
import com.lecteur.core.model.ImageUrls
import com.lecteur.core.model.LibraryItem
import com.lecteur.core.model.LibrarySection
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.PlayPlan

/**
 * The home screen: the rows the user chose, in their order. Every launch goes through [onPlay] (the app starts the player),
 * every navigation through the other callbacks: the screen knows no other screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenMovie: (Long) -> Unit,
    onOpenSeries: (Long) -> Unit,
    onSeeAll: (LibrarySection) -> Unit,
    onPlay: (PlayPlan) -> Unit,
    onOpenSearch: () -> Unit,
    onAddFolder: () -> Unit,
    modifier: Modifier = Modifier,
    banner: @Composable () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var sheetItem by remember { mutableStateOf<LibraryItem?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is HomeEvent.Play -> onPlay(event.plan)
                is HomeEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

    val openItem: (LibraryItem) -> Unit = { if (it.kind == MediaKind.MOVIE) onOpenMovie(it.id) else onOpenSeries(it.id) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Lecteur Média") },
                actions = { IconButton(onClick = onOpenSearch) { Icon(Icons.Rounded.Search, contentDescription = "Rechercher") } }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            banner()
            when {
                !ui.loaded -> LoadingBox()
                ui.folderCount == 0 -> EmptyState(
                    icon = Icons.Rounded.CreateNewFolder,
                    title = "Votre bibliothèque est vide",
                    message = "Ajoutez le dossier où se trouvent vos films et vos séries : l'application les range et télécharge les affiches toute seule.",
                    actionLabel = "Ajouter un dossier",
                    onAction = onAddFolder
                )
                ui.fileCount == 0 -> EmptyState(
                    icon = Icons.Rounded.VideoLibrary,
                    title = "Aucune vidéo trouvée",
                    message = "Les dossiers ajoutés ne contiennent pas encore de fichier vidéo, ou l'analyse n'est pas terminée. Vérifiez que le support est branché.",
                    actionLabel = "Gérer les dossiers",
                    onAction = onAddFolder
                )
                else -> HomeRows(
                    ui = ui,
                    onOpenItem = openItem,
                    onLongPress = { sheetItem = it },
                    onSeeAll = onSeeAll,
                    onPlayContinue = viewModel::play,
                    onPlayNextUp = viewModel::play,
                    onOpenMovie = onOpenMovie,
                    onOpenSeries = onOpenSeries
                )
            }
        }
    }

    sheetItem?.let { item ->
        var favorite by remember(item) { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(item) { favorite = viewModel.isFavorite(item) }
        ItemActionsSheet(
            item = item,
            isFavorite = favorite,
            onPlay = { viewModel.play(item) },
            onToggleWatched = { viewModel.setWatched(item, item.watch != com.lecteur.core.model.WatchStatus.WATCHED) },
            onToggleFavorite = { viewModel.toggleFavorite(item) },
            onOpenDetails = { openItem(item) },
            onDismiss = { sheetItem = null },
            onAddToList = { viewModel.addToList(item) }
        )
    }

    val picker by viewModel.listPicker.state.collectAsStateWithLifecycle()
    picker?.let {
        ListPickerSheet(it, onToggle = viewModel.listPicker::toggle, onCreate = viewModel.listPicker::create, onDismiss = viewModel.listPicker::close)
    }
}

@Composable
private fun HomeRows(
    ui: HomeUi,
    onOpenItem: (LibraryItem) -> Unit,
    onLongPress: (LibraryItem) -> Unit,
    onSeeAll: (LibrarySection) -> Unit,
    onPlayContinue: (ContinueItem) -> Unit,
    onPlayNextUp: (NextUpItem) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenSeries: (Long) -> Unit
) {
    val shown = ui.rows.filter(ui::hasContent)
    if (shown.isEmpty()) {
        EmptyState(
            icon = Icons.Rounded.VideoLibrary,
            title = "Rien à afficher",
            message = "Toutes les rangées de l'accueil sont masquées ou vides. Vous pouvez les réafficher dans Réglages > Accueil."
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        items(shown, key = { it.name }) { row ->
            Column {
                SectionHeader(
                    title = row.title,
                    actionLabel = if (row == HomeRow.MOVIES || row == HomeRow.SERIES) "Tout voir" else null,
                    onAction = {
                        onSeeAll(if (row == HomeRow.MOVIES) LibrarySection.MOVIES else LibrarySection.SERIES)
                    }
                )
                when (row) {
                    HomeRow.CONTINUE -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(ui.continueItems, key = { "c${it.mediaFileId}" }) { item ->
                            LandscapeCard(
                                imageUrl = ImageUrls.backdrop(item.imagePath),
                                title = item.title,
                                subtitle = item.subtitle,
                                detail = if (item.remainingMinutes > 0) "Encore ${item.remainingMinutes} min" else null,
                                progress = item.progress,
                                isAvailable = item.isAvailable,
                                modifier = Modifier.width(240.dp),
                                onClick = { onPlayContinue(item) },
                                onLongClick = {
                                    val id = item.titleId
                                    if (id != null && item.kind == MediaKind.MOVIE) onOpenMovie(id)
                                    else if (id != null && item.kind == MediaKind.SERIES) onOpenSeries(id)
                                }
                            )
                        }
                    }
                    HomeRow.NEXT_UP -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(ui.nextUp, key = { "n${it.episodeId}" }) { item ->
                            LandscapeCard(
                                imageUrl = ImageUrls.still(item.imagePath),
                                title = item.seriesTitle,
                                subtitle = item.label,
                                modifier = Modifier.width(240.dp),
                                onClick = { onPlayNextUp(item) },
                                onLongClick = { onOpenSeries(item.seriesId) }
                            )
                        }
                    }
                    else -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(ui.itemsOf(row), key = { it.key }) { item ->
                            PosterCard(
                                item = item,
                                modifier = Modifier.width(118.dp),
                                onClick = { onOpenItem(item) },
                                onLongClick = { onLongPress(item) }
                            )
                        }
                    }
                }
            }
        }
    }
}
