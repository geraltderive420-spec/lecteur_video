package com.lecteur.desktop.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lecteur.core.model.Formatters
import com.lecteur.core.model.HdrFilter
import com.lecteur.core.model.ImageUrls
import com.lecteur.core.model.LibraryFilters
import com.lecteur.core.model.LibraryItem
import com.lecteur.core.model.LibraryQuery
import com.lecteur.core.model.LibrarySection
import com.lecteur.core.model.LibrarySort
import com.lecteur.core.model.LibraryView
import com.lecteur.core.model.PosterSize
import com.lecteur.core.model.ResolutionTier
import com.lecteur.core.model.SortField
import com.lecteur.core.model.WatchFilter
import com.lecteur.core.model.WatchStatus
import com.lecteur.core.model.defaultOrder

val SortField.label: String
    get() = when (this) {
        SortField.TITLE -> "Titre"
        SortField.ADDED -> "Date d'ajout"
        SortField.RELEASE -> "Date de sortie"
        SortField.RATING -> "Note"
        SortField.DURATION -> "Durée"
        SortField.LAST_PLAYED -> "Dernière lecture"
        SortField.FILE_SIZE -> "Taille du fichier"
    }

val WatchFilter.label: String
    get() = when (this) {
        WatchFilter.ALL -> "Tous"
        WatchFilter.UNWATCHED -> "Non vus"
        WatchFilter.IN_PROGRESS -> "En cours"
        WatchFilter.WATCHED -> "Vus"
    }

val HdrFilter.label: String
    get() = when (this) {
        HdrFilter.ANY -> "Tous"
        HdrFilter.ANY_HDR -> "HDR"
        HdrFilter.DOLBY_VISION -> "Dolby Vision"
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(screen: Screen.Library) {
    val a = app()
    val snapshot by a.services.store.snapshot.collectAsState()
    val settings by a.services.settings.state.collectAsState()
    val prefs = settings.section(screen.section)
    var filters by remember(screen) { mutableStateOf(screen.filters) }
    var showFilters by remember { mutableStateOf(false) }
    val sort = LibrarySort(prefs.sortField, prefs.sortOrder)
    val items = remember(snapshot, sort, filters, screen.section) { snapshot.query(LibraryQuery(screen.section, sort, filters)) }
    val options = remember(snapshot, screen.section) { snapshot.filterOptions(screen.section) }
    val title = screen.title ?: if (screen.section == LibrarySection.MOVIES) "Films" else "Séries"

    fun updatePrefs(transform: (com.lecteur.desktop.data.SectionSettings) -> com.lecteur.desktop.data.SectionSettings) =
        a.services.settings.update { it.withSection(screen.section, transform(it.section(screen.section))) }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            ScreenTitle(title, "${items.size} titre${if (items.size > 1) "s" else ""}") {
                if (a.canGoBack) IconButton(onClick = a::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                SortMenu(sort) { field ->
                    updatePrefs { p -> if (p.sortField == field) p.copy(sortOrder = p.sortOrder.flipped()) else p.copy(sortField = field, sortOrder = field.defaultOrder()) }
                }
                IconButton(onClick = { updatePrefs { it.copy(sortOrder = it.sortOrder.flipped()) } }) {
                    Icon(if (sort.order == com.lecteur.core.model.SortOrder.ASC) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward, "Ordre")
                }
                IconButton(onClick = { updatePrefs { it.copy(view = if (it.view == LibraryView.GRID) LibraryView.LIST else LibraryView.GRID) } }) {
                    Icon(if (prefs.view == LibraryView.GRID) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView, "Affichage")
                }
                if (prefs.view == LibraryView.GRID) PosterSizeMenu(settings.posterSize) { size -> a.services.settings.update { it.copy(posterSize = size) } }
                IconButton(onClick = { showFilters = !showFilters }) {
                    BadgedBox(badge = { if (filters.activeCount > 0) androidx.compose.material3.Badge { Text("${filters.activeCount}") } }) {
                        Icon(Icons.Filled.FilterList, "Filtres", tint = if (showFilters) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            if (filters.activeCount > 0) {
                FlowRow(Modifier.padding(horizontal = 32.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    activeChips(filters, options).forEach { (label, clear) ->
                        InputChip(selected = true, onClick = { filters = clear(filters) }, label = { Text(label) }, trailingIcon = { Icon(Icons.Filled.Close, null, Modifier.size(16.dp)) })
                    }
                    TextButton(onClick = { filters = filters.withoutSheetFilters() }) { Text("Tout effacer") }
                }
                Spacer(Modifier.height(8.dp))
            }
            if (items.isEmpty()) {
                EmptyState(Icons.Filled.SearchOff, "Rien à afficher", if (filters.isEmpty) "Cette section est vide." else "Aucun titre ne correspond aux filtres.")
            } else if (prefs.view == LibraryView.GRID) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive((settings.posterSize.dp + 30).dp),
                    contentPadding = PaddingValues(start = 32.dp, end = 32.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items, key = { it.key }) { item ->
                        PosterCard(item, (settings.posterSize.dp + 30).dp, onPlay = { a.play(item.kind, item.id) }) { a.open(item) }
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
                    items(items, key = { it.key }) { item -> LibraryRow(item) }
                }
            }
        }
        if (showFilters) {
            FilterPanel(screen.section, filters, options, onChange = { filters = it }, onClose = { showFilters = false })
        }
    }
}

@Composable
private fun LibraryRow(item: LibraryItem) {
    val a = app()
    ContextMenuArea(items = itemMenu(item)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { a.open(item) }.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(54.dp).height(80.dp).clip(RoundedCornerShape(6.dp))) {
                RemoteImage(ImageUrls.poster(item.posterPath), Modifier.fillMaxSize()) { PosterPlaceholder("", item.kind) }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(item.year?.toString(), if (item.unitCount > 1) "${item.watchedCount}/${item.unitCount} épisodes vus" else null, if (!item.isAvailable) "Hors ligne" else null).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.progress > 0f) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.width(200.dp).height(3.dp))
                }
            }
            item.rating?.let { r ->
                Icon(Icons.Filled.Star, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(Formatters.rating(r) ?: "", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(16.dp))
            }
            if (item.watch == WatchStatus.WATCHED) Icon(Icons.Filled.CheckCircle, "Vu", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SortMenu(sort: LibrarySort, onPick: (SortField) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, null)
            Spacer(Modifier.width(6.dp))
            Text(sort.field.label)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            SortField.entries.forEach { field ->
                DropdownMenuItem(
                    text = { Text(field.label) },
                    onClick = { open = false; onPick(field) },
                    trailingIcon = { if (field == sort.field) Icon(if (sort.order == com.lecteur.core.model.SortOrder.ASC) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward, null) }
                )
            }
        }
    }
}

@Composable
private fun PosterSizeMenu(size: PosterSize, onPick: (PosterSize) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.PhotoSizeSelectLarge, "Taille des affiches") }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            PosterSize.entries.forEach { s ->
                DropdownMenuItem(text = { Text(s.label) }, onClick = { open = false; onPick(s) }, trailingIcon = { if (s == size) Icon(Icons.Filled.CheckCircle, null) })
            }
        }
    }
}

private fun activeChips(f: LibraryFilters, o: com.lecteur.core.model.FilterOptions): List<Pair<String, (LibraryFilters) -> LibraryFilters>> = buildList {
    f.genreId?.let { id -> add((o.genres.firstOrNull { it.id == id }?.name ?: "Genre") to { x: LibraryFilters -> x.copy(genreId = null) }) }
    if (f.yearFrom != null || f.yearTo != null) add((f.yearFrom?.let { Formatters.decade(it) } ?: "Années") to { x: LibraryFilters -> x.copy(yearFrom = null, yearTo = null) })
    if (f.watch != WatchFilter.ALL) add(f.watch.label to { x: LibraryFilters -> x.copy(watch = WatchFilter.ALL) })
    if (f.resolution != ResolutionTier.ANY) add(f.resolution.label to { x: LibraryFilters -> x.copy(resolution = ResolutionTier.ANY) })
    if (f.hdr != HdrFilter.ANY) add(f.hdr.label to { x: LibraryFilters -> x.copy(hdr = HdrFilter.ANY) })
    f.certification?.let { add("Classement $it" to { x: LibraryFilters -> x.copy(certification = null) }) }
    f.folderId?.let { id -> add((o.folders.firstOrNull { it.id == id }?.displayPath?.substringAfterLast('\\') ?: "Dossier") to { x: LibraryFilters -> x.copy(folderId = null) }) }
    f.audioLanguage?.let { code -> add("Audio ${o.audioLanguages.firstOrNull { it.code == code }?.name ?: code}" to { x: LibraryFilters -> x.copy(audioLanguage = null) }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPanel(
    section: LibrarySection,
    f: LibraryFilters,
    o: com.lecteur.core.model.FilterOptions,
    onChange: (LibraryFilters) -> Unit,
    onClose: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.width(320.dp).fillMaxHeight()) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filtres", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Fermer") }
            }
            @Composable
            fun section(title: String, content: @Composable () -> Unit) {
                Spacer(Modifier.height(14.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
            }
            section("Visionnage") {
                WatchFilter.entries.forEach { w -> FilterChip(f.watch == w, { onChange(f.copy(watch = w)) }, { Text(w.label) }) }
            }
            if (o.genres.isNotEmpty()) section("Genre") {
                o.genres.forEach { g -> FilterChip(f.genreId == g.id, { onChange(f.copy(genreId = if (f.genreId == g.id) null else g.id)) }, { Text(g.name) }) }
            }
            if (o.decades.isNotEmpty()) section("Décennie") {
                o.decades.forEach { d ->
                    val on = f.yearFrom == d
                    FilterChip(on, { onChange(if (on) f.copy(yearFrom = null, yearTo = null) else f.copy(yearFrom = d, yearTo = d + 9)) }, { Text("$d") })
                }
            }
            section("Résolution") {
                ResolutionTier.entries.forEach { r -> FilterChip(f.resolution == r, { onChange(f.copy(resolution = r)) }, { Text(r.label) }) }
            }
            section("HDR") {
                HdrFilter.entries.forEach { h -> FilterChip(f.hdr == h, { onChange(f.copy(hdr = h)) }, { Text(h.label) }) }
            }
            if (o.audioLanguages.isNotEmpty()) section("Langue audio") {
                o.audioLanguages.forEach { l -> FilterChip(f.audioLanguage == l.code, { onChange(f.copy(audioLanguage = if (f.audioLanguage == l.code) null else l.code)) }, { Text(l.name) }) }
            }
            if (o.certifications.isNotEmpty()) section("Classification") {
                o.certifications.forEach { c -> FilterChip(f.certification == c, { onChange(f.copy(certification = if (f.certification == c) null else c)) }, { Text(c) }) }
            }
            if (o.folders.size > 1) section("Dossier") {
                o.folders.forEach { d -> FilterChip(f.folderId == d.id, { onChange(f.copy(folderId = if (f.folderId == d.id) null else d.id)) }, { Text(d.displayPath.substringAfterLast('\\'), maxLines = 1) }) }
            }
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            TextButton(onClick = { onChange(f.withoutSheetFilters()) }) { Text("Réinitialiser les filtres") }
        }
    }
}
