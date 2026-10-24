package com.lecteur.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lecteur.core.designsystem.components.PosterImage
import com.lecteur.core.model.ImageUrls
import com.lecteur.tv.TvLibraryViewModel
import com.lecteur.tv.TvPage

/** Titles and people of every source, without accents or capitals. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvSearchScreen(viewModel: TvLibraryViewModel, onOpen: (TvPage) -> Unit) {
    val ui by viewModel.searchUi.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf(ui.query) }
    val focus = LocalFocusManager.current
    LazyVerticalGrid(
        columns = GridCells.FixedSize(POSTER_WIDTH),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(8.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            OutlinedTextField(
                value = text, onValueChange = { text = it; viewModel.search(it) }, singleLine = true,
                label = { androidx.compose.material3.Text("Titre, acteur, réalisateur…") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.moveFocus(FocusDirection.Down) }),
                // A text field keeps the remote's arrows for its cursor: down leaves it for the results
                modifier = Modifier.fillMaxWidth(0.6f).onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) focus.moveFocus(FocusDirection.Down) else false
                }
            )
        }
        if (ui.people.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                TvShelf("Personnes") {
                    items(ui.people, key = { it.name }) { p ->
                        Column(Modifier.width(130.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Card(
                                onClick = { onOpen(TvPage.Person(p.name, p.personId)) }, shape = CardDefaults.shape(CircleShape),
                                modifier = Modifier.size(110.dp).remembersFocus("person-${p.name}")
                            ) { PosterImage(ImageUrls.profile(p.profilePath), p.name, Modifier.fillMaxWidth()) }
                            Text(p.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
        if (ui.titles.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("Titres", style = MaterialTheme.typography.titleLarge) }
        if (ui.query.isNotBlank() && ui.titles.isEmpty() && ui.people.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { Text("Aucun résultat pour « ${ui.query} ».", style = MaterialTheme.typography.titleMedium) }
        }
        items(ui.titles, key = { it.ref.key }) { tile -> TvPoster(tile, onClick = { onOpen(TvPage.Detail(tile.ref)) }) }
    }
}

/** Everything someone is in, on this TV and on the PCs. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvPersonScreen(viewModel: TvLibraryViewModel, page: TvPage.Person, onOpen: (TvPage) -> Unit) {
    val tiles by remember(page) { viewModel.person(page) }.collectAsState(initial = emptyList())
    // The cast card that opened this page is gone: the first poster takes the focus once the titles are found
    val first = remember(page) { FocusRequester() }
    LaunchedEffect(tiles.isNotEmpty()) { if (tiles.isNotEmpty()) runCatching { first.requestFocus() } }
    LazyVerticalGrid(
        columns = GridCells.FixedSize(POSTER_WIDTH),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(8.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text(page.name, style = MaterialTheme.typography.displaySmall)
                Text(
                    if (tiles.isEmpty()) "Recherche dans les bibliothèques…" else "${tiles.size} titre" + (if (tiles.size > 1) "s" else "") + " dans vos bibliothèques",
                    style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.7f)
                )
            }
        }
        itemsIndexed(tiles, key = { _, t -> t.ref.key }) { i, tile ->
            TvPoster(tile, onClick = { onOpen(TvPage.Detail(tile.ref)) }, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier)
        }
    }
}
