package com.lecteur.desktop.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lecteur.core.model.ImageUrls
import com.lecteur.core.model.LibraryFilters
import com.lecteur.core.model.LibraryQuery
import com.lecteur.core.model.LibrarySection
import com.lecteur.desktop.data.HomeCard
import kotlinx.coroutines.delay

@Composable
fun SearchScreen() {
    val a = app()
    val snapshot by a.services.store.snapshot.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(text) {
        delay(150)
        query = text
    }
    val results = remember(snapshot, query) { snapshot.search(query) }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true,
            placeholder = { Text("Titre, acteur, réalisateur, épisode…") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (text.isNotEmpty()) IconButton(onClick = { text = "" }) { Icon(Icons.Filled.Close, "Effacer") } },
            modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 28.dp, bottom = 12.dp).focusRequester(focus)
        )
        when {
            query.isBlank() -> EmptyState(Icons.Filled.Search, "Rechercher", "Tapez quelques lettres : la recherche ignore accents et majuscules.")
            results.isEmpty -> EmptyState(Icons.Filled.Search, "Aucun résultat", "Rien ne correspond à « $query » dans la bibliothèque.")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                contentPadding = PaddingValues(start = 32.dp, end = 32.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                if (results.people.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            Text("Personnes", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(8.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(results.people, key = { it.personId }) { p ->
                                    PersonAvatar(p.name, p.profile, "${p.movieIds.size + p.seriesIds.size} titre(s)") { a.go(Screen.Person(p.personId)) }
                                }
                            }
                        }
                    }
                }
                if (results.items.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { Text("Films et séries", style = MaterialTheme.typography.titleLarge) }
                    items(results.items, key = { it.key }) { item -> PosterCard(item, 160.dp, onPlay = { a.play(item.kind, item.id) }) { a.open(item) } }
                }
                if (results.episodes.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            Text("Épisodes", style = MaterialTheme.typography.titleLarge)
                            results.episodes.forEach { (s, ep) ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { a.playEpisode(s.series.id, ep.episode.id) }.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(Modifier.width(120.dp).height(68.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                        RemoteImage(ImageUrls.still(ep.episode.stillPath), Modifier.fillMaxSize())
                                    }
                                    Spacer(Modifier.width(14.dp))
                                    Column {
                                        Text(ep.episode.title ?: "", style = MaterialTheme.typography.titleMedium)
                                        Text("${s.series.title} · S%02dE%02d".format(ep.episode.seasonNumber, ep.episode.episodeNumber), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PersonScreen(id: Long) {
    val a = app()
    val snapshot by a.services.store.snapshot.collectAsState()
    val person = snapshot.people.firstOrNull { it.personId == id } ?: return EmptyState(Icons.Filled.Person, "Personne inconnue", "")
    val movies = remember(snapshot, id) { snapshot.query(LibraryQuery(LibrarySection.MOVIES, filters = LibraryFilters(personId = id))) }
    val series = remember(snapshot, id) { snapshot.query(LibraryQuery(LibrarySection.SERIES, filters = LibraryFilters(personId = id))) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(start = 24.dp, top = 24.dp, end = 32.dp, bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = a::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(110.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    RemoteImage(ImageUrls.url(person.profile, "h632"), Modifier.fillMaxSize()) { Icon(Icons.Filled.Person, null, Modifier.align(Alignment.Center).size(48.dp)) }
                }
                Spacer(Modifier.width(24.dp))
                Column {
                    Text(person.name, style = MaterialTheme.typography.headlineMedium)
                    Text("${movies.size} film(s) · ${series.size} série(s) dans la bibliothèque", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { PosterRow("Films", movies.map { HomeCard(it) }, onCard = { a.open(it.item) }, onPlay = { a.play(it.item.kind, it.item.id) }) }
        item { PosterRow("Séries", series.map { HomeCard(it) }, onCard = { a.open(it.item) }, onPlay = { a.play(it.item.kind, it.item.id) }) }
    }
}

@Composable
fun ListsScreen() {
    val a = app()
    val snapshot by a.services.store.snapshot.collectAsState()
    val lists = remember(snapshot) { snapshot.listSummaries() }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Long?>(null) }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Mes listes", "Favoris et listes personnelles") {
            Button(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(6.dp))
                Text("Nouvelle liste")
            }
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
            items(lists, key = { it.id }) { list ->
                val members = snapshot.members.filter { it.listId == list.id }.sortedByDescending { it.addedAt }
                val cards = members.mapNotNull { m ->
                    m.movieId?.let { snapshot.movies[it] }?.let { HomeCard(snapshot.item(it)) } ?: m.seriesId?.let { snapshot.series[it] }?.let { HomeCard(snapshot.item(it)) }
                }
                val menu = {
                    if (list.isFavorites) emptyList() else listOf(
                        ContextMenuItem("Renommer") { renaming = list.id },
                        ContextMenuItem("Supprimer la liste") {
                            a.dialog = AppDialog.Confirm("Supprimer « ${list.name} » ?", "Les titres restent dans la bibliothèque.", "Supprimer") { a.background { a.services.store.deleteList(list.id) } }
                        }
                    )
                }
                ContextMenuArea(items = menu) {
                    Column {
                        Row(Modifier.padding(horizontal = 32.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (list.isFavorites) Icons.Filled.Favorite else Icons.AutoMirrored.Filled.List, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text("${list.name}  ·  ${list.itemCount}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            if (!list.isFavorites) {
                                androidx.compose.material3.TextButton(onClick = { renaming = list.id }) { Text("Renommer") }
                                androidx.compose.material3.TextButton(onClick = {
                                    a.dialog = AppDialog.Confirm("Supprimer « ${list.name} » ?", "Les titres restent dans la bibliothèque.", "Supprimer") { a.background { a.services.store.deleteList(list.id) } }
                                }) { Text("Supprimer") }
                            }
                        }
                        if (cards.isEmpty()) {
                            Text(
                                if (list.isFavorites) "Aucun favori : cliquez sur ♥ dans une fiche ou faites un clic droit sur une affiche." else "Liste vide : ajoutez des titres depuis leur fiche ou par un clic droit.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp)
                            )
                            Spacer(Modifier.height(16.dp))
                        } else {
                            PosterRow(list.name, cards, onCard = { a.open(it.item) }, onPlay = { a.play(it.item.kind, it.item.id) })
                        }
                    }
                }
            }
        }
    }
    if (creating || renaming != null) {
        val existing = lists.map { it.name }
        NameDialog(
            title = if (creating) "Nouvelle liste" else "Renommer la liste",
            initial = renaming?.let { id -> lists.firstOrNull { it.id == id }?.name }.orEmpty(),
            existing = existing.filter { it != lists.firstOrNull { l -> l.id == renaming }?.name },
            onDismiss = { creating = false; renaming = null }
        ) { name ->
            val target = renaming
            a.background { if (target == null) a.services.store.createList(name) else a.services.store.renameList(target, name) }
            creating = false
            renaming = null
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, existing: List<String>, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val check = com.lecteur.core.common.lists.ListNames.check(name, existing, "Favoris")
    val error = (check as? com.lecteur.core.common.lists.ListNameCheck.Invalid)?.problem?.message
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Nom") }, isError = error != null && name.isNotEmpty(),
                supportingText = { if (error != null && name.isNotEmpty()) Text(error) })
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onConfirm(name.trim()) }, enabled = error == null) { Text("Valider") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

