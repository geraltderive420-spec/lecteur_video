package com.lecteur.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.lecteur.core.designsystem.components.EmptyState
import com.lecteur.core.designsystem.components.ItemActionsSheet
import com.lecteur.core.designsystem.components.LibraryListRow
import com.lecteur.core.designsystem.components.ListPickerSheet
import com.lecteur.core.designsystem.components.LoadingBox
import com.lecteur.core.designsystem.components.PosterCard
import com.lecteur.core.model.LibraryFilters
import com.lecteur.core.model.LibraryItem
import com.lecteur.core.model.LibraryView
import com.lecteur.core.model.LibrarySection
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.PlayPlan
import com.lecteur.core.model.PosterSize
import com.lecteur.core.model.SectionPrefs
import com.lecteur.core.model.SortField
import com.lecteur.core.model.SortOrder

/**
 * The library: one page per shelf (films, series), each with its own sort, view and filters. Opened from the bottom bar it
 * shows the whole library; opened from an actor, a genre or a saga, [scope] narrows both shelves and [scopeLabel] says to what.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryHost(
    initialSection: LibrarySection,
    onOpenMovie: (Long) -> Unit,
    onOpenSeries: (Long) -> Unit,
    onOpenSearch: () -> Unit,
    onPlay: (PlayPlan) -> Unit,
    modifier: Modifier = Modifier,
    scope: LibraryFilters = LibraryFilters(),
    scopeLabel: String? = null,
    onBack: (() -> Unit)? = null,
    /** Shows the "my lists" button; the top-level library passes it, scoped screens do not. */
    onOpenLists: (() -> Unit)? = null
) {
    // A saga holds films only: a series tab under it would list every series of the library
    val sections = if (scope.collectionId != null) listOf(LibrarySection.MOVIES) else LibrarySection.entries
    var selected by rememberSaveable { mutableStateOf(initialSection.takeIf { it in sections } ?: sections.first()) }
    val snackbar = remember { SnackbarHostState() }

    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(scopeLabel ?: "Bibliothèque") },
                    navigationIcon = {
                        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Retour") }
                    },
                    actions = {
                        if (onOpenLists != null) IconButton(onClick = onOpenLists) { Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, contentDescription = "Mes listes") }
                        IconButton(onClick = onOpenSearch) { Icon(Icons.Rounded.Search, contentDescription = "Rechercher") }
                    }
                )
                if (sections.size > 1) {
                    TabRow(selectedTabIndex = sections.indexOf(selected)) {
                        sections.forEach { section ->
                            Tab(
                                selected = section == selected,
                                onClick = { selected = section },
                                text = { Text(LibraryLabels.section(section)) },
                                icon = { Icon(if (section == LibrarySection.MOVIES) Icons.Rounded.Movie else Icons.Rounded.Tv, contentDescription = null) }
                            )
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LibraryPage(
                section = selected,
                scope = scope,
                snackbar = snackbar,
                onOpenMovie = onOpenMovie,
                onOpenSeries = onOpenSeries,
                onPlay = onPlay
            )
        }
    }
}

@Composable
private fun LibraryPage(
    section: LibrarySection,
    scope: LibraryFilters,
    snackbar: SnackbarHostState,
    onOpenMovie: (Long) -> Unit,
    onOpenSeries: (Long) -> Unit,
    onPlay: (PlayPlan) -> Unit
) {
    val viewModel: LibraryViewModel = hiltViewModel(key = "${section.name}:${scope.hashCode()}")
    LaunchedEffect(section, scope) { viewModel.bind(section, scope) }

    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val options by viewModel.options.collectAsStateWithLifecycle()
    val items = viewModel.items.collectAsLazyPagingItems()

    var showFilters by remember { mutableStateOf(false) }
    var sheetItem by remember { mutableStateOf<LibraryItem?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is LibraryEvent.Play -> onPlay(event.plan)
                is LibraryEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

    val open: (LibraryItem) -> Unit = { if (it.kind == MediaKind.MOVIE) onOpenMovie(it.id) else onOpenSeries(it.id) }

    Column(Modifier.fillMaxSize()) {
        Toolbar(
            prefs = prefs,
            activeFilters = filters.activeCount,
            onSort = viewModel::sortBy,
            onView = viewModel::setView,
            onPosterSize = viewModel::setPosterSize,
            onFilters = { showFilters = true }
        )
        val chips = LibraryLabels.activeChips(filters, options)
        if (chips.isNotEmpty()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(chips, key = { it.label }) { chip ->
                    InputChip(
                        selected = true,
                        onClick = { viewModel.setFilters(chip.remove(filters)) },
                        label = { Text(chip.label) },
                        trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = "Retirer le filtre ${chip.label}") }
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))

        Box(Modifier.fillMaxSize()) {
            Content(
                items = items,
                prefs = prefs,
                section = section,
                filtersActive = filters.activeCount > 0 || filters.hasScope,
                onOpen = open,
                onLongPress = { sheetItem = it },
                onClearFilters = viewModel::clearFilters
            )
        }
    }

    if (showFilters) {
        FilterSheet(
            filters = filters,
            options = options,
            onChange = viewModel::updateFilters,
            onClear = viewModel::clearFilters,
            onDismiss = { showFilters = false }
        )
    }

    sheetItem?.let { item ->
        var favorite by remember(item) { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(item) { favorite = viewModel.isFavorite(item) }
        ItemActionsSheet(
            item = item,
            isFavorite = favorite,
            onPlay = { viewModel.play(item) },
            onToggleWatched = { viewModel.toggleWatched(item) },
            onToggleFavorite = { viewModel.toggleFavorite(item) },
            onOpenDetails = { open(item) },
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
private fun Toolbar(
    prefs: SectionPrefs,
    activeFilters: Int,
    onSort: (SortField) -> Unit,
    onView: (LibraryView) -> Unit,
    onPosterSize: (PosterSize) -> Unit,
    onFilters: () -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        var sortOpen by remember { mutableStateOf(false) }
        var viewOpen by remember { mutableStateOf(false) }

        Box {
            IconButton(onClick = { sortOpen = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = "Trier") }
            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                SortField.entries.forEach { field ->
                    val current = prefs.sort.field == field
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(LibraryLabels.sort(field))
                                if (current) {
                                    Text(LibraryLabels.sortOrder(field, prefs.sort.order), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        },
                        trailingIcon = {
                            if (current) {
                                Icon(if (prefs.sort.order == SortOrder.ASC) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward, contentDescription = null)
                            }
                        },
                        onClick = {
                            onSort(field)
                            // Choosing the current field only flips the order: leave the menu open so both taps are visible
                            if (!current) sortOpen = false
                        }
                    )
                }
            }
        }
        Text(
            LibraryLabels.sort(prefs.sort.field) + if (prefs.sort.order == SortOrder.ASC) " ↑" else " ↓",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )

        Box {
            IconButton(onClick = { viewOpen = true }) {
                Icon(if (prefs.view == LibraryView.GRID) Icons.Rounded.GridView else Icons.Rounded.ViewList, contentDescription = "Affichage")
            }
            DropdownMenu(expanded = viewOpen, onDismissRequest = { viewOpen = false }) {
                DropdownMenuItem(text = { Text("Grille d'affiches") }, leadingIcon = { Icon(Icons.Rounded.GridView, null) }, onClick = { onView(LibraryView.GRID); viewOpen = false })
                DropdownMenuItem(text = { Text("Liste") }, leadingIcon = { Icon(Icons.Rounded.ViewList, null) }, onClick = { onView(LibraryView.LIST); viewOpen = false })
                if (prefs.view == LibraryView.GRID) {
                    HorizontalDivider()
                    PosterSize.entries.forEach { size ->
                        DropdownMenuItem(
                            text = { Text("Affiches ${size.label.lowercase()}", color = if (size == prefs.posterSize) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                            onClick = { onPosterSize(size); viewOpen = false }
                        )
                    }
                }
            }
        }

        IconButton(onClick = onFilters) {
            BadgedBox(badge = { if (activeFilters > 0) Badge { Text("$activeFilters") } }) {
                Icon(Icons.Rounded.FilterList, contentDescription = "Filtres")
            }
        }
    }
}

@Composable
private fun Content(
    items: LazyPagingItems<LibraryItem>,
    prefs: SectionPrefs,
    section: LibrarySection,
    filtersActive: Boolean,
    onOpen: (LibraryItem) -> Unit,
    onLongPress: (LibraryItem) -> Unit,
    onClearFilters: () -> Unit
) {
    val refreshing = items.loadState.refresh is LoadState.Loading
    when {
        items.itemCount == 0 && refreshing -> LoadingBox()
        items.itemCount == 0 && filtersActive -> EmptyState(
            icon = Icons.Rounded.SearchOff,
            title = "Aucun résultat",
            message = "Aucun titre ne correspond à ces filtres.",
            actionLabel = "Effacer les filtres",
            onAction = onClearFilters
        )
        items.itemCount == 0 -> EmptyState(
            icon = if (section == LibrarySection.MOVIES) Icons.Rounded.Movie else Icons.Rounded.Tv,
            title = LibraryLabels.emptyShelf(section),
            message = "Ajoutez un dossier dans Réglages > Dossiers, ou attendez la fin de l'analyse. Les dossiers de catégorie « ${if (section == LibrarySection.MOVIES) "Films" else "Séries"} » alimentent cette page."
        )
        prefs.view == LibraryView.GRID -> LazyVerticalGrid(
            columns = GridCells.Adaptive(prefs.posterSize.dp.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(count = items.itemCount, key = items.itemKey { it.key }, contentType = { "poster" }) { index ->
                items[index]?.let { item ->
                    PosterCard(item = item, onClick = { onOpen(item) }, onLongClick = { onLongPress(item) })
                }
            }
        }
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(count = items.itemCount, key = items.itemKey { it.key }, contentType = { "row" }) { index ->
                items[index]?.let { item ->
                    LibraryListRow(item = item, onClick = { onOpen(item) }, onLongClick = { onLongPress(item) })
                }
            }
        }
    }
}
