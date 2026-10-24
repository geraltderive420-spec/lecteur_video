package com.lecteur.tv.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.lecteur.core.designsystem.components.PosterImage
import com.lecteur.core.model.ImageUrls
import com.lecteur.core.model.WatchStatus
import com.lecteur.tv.TvTile
import com.lecteur.tv.TvWide

val POSTER_WIDTH = 150.dp
val WIDE_WIDTH = 280.dp

/** Accent of the "on the PC" mention under posters. */
val SourceColor = Color(0xFF8AB4F8)

/**
 * Which card had the focus, so that closing a page above the tabs puts it back on that card rather than on the tab row
 * (whose focused tab opens at once).
 */
class TvFocusMemory {
    var lastKey by mutableStateOf<String?>(null)
    val requester = FocusRequester()

    fun restore() {
        runCatching { requester.requestFocus() }
    }
}

val LocalTvFocus = staticCompositionLocalOf { TvFocusMemory() }

fun Modifier.remembersFocus(key: String): Modifier = composed {
    val memory = LocalTvFocus.current
    onFocusChanged { if (it.isFocused) memory.lastKey = key }
        .then(if (memory.lastKey == key) Modifier.focusRequester(memory.requester) else Modifier)
}

/** A poster with its state (seen, progress, unavailable) and, for a PC's title, the PC's name under it. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvPoster(tile: TvTile, onClick: () -> Unit, width: Dp = POSTER_WIDTH, modifier: Modifier = Modifier) {
    val item = tile.item
    Column(modifier.width(width)) {
        Card(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).remembersFocus(tile.ref.key)) {
            Box {
                PosterImage(ImageUrls.poster(item.posterPath), item.title, Modifier.fillMaxSize().alpha(if (item.isAvailable) 1f else 0.45f))
                if (item.watch == WatchStatus.WATCHED) {
                    Icon(Icons.Rounded.CheckCircle, "Vu", tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp))
                }
                val fraction = when {
                    item.progress > 0f -> item.progress
                    item.unitCount > 1 && item.watchedCount in 1 until item.unitCount -> item.watchedCount.toFloat() / item.unitCount
                    else -> 0f
                }
                if (fraction > 0f) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp))
            }
        }
        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        SourceLine(listOfNotNull(item.year?.toString()).joinToString(), tile.source)
    }
}

/** "2019", then the PC's name in the accent colour when the title is a PC's. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SourceLine(text: String, source: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        if (source != null) {
            if (text.isNotEmpty()) Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.Computer, null, tint = SourceColor, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(3.dp))
            Text(source, style = MaterialTheme.typography.labelSmall, color = SourceColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The pill of a detail page: "Sur PC SALON" or "Sur cette TV". */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SourcePill(source: String?) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.10f)).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Computer, null, tint = if (source != null) SourceColor else Color.White, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (source != null) "Sur $source" else "Sur cette TV", style = MaterialTheme.typography.labelMedium, color = if (source != null) SourceColor else Color.White)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvWideCard(card: TvWide, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Column(Modifier.width(WIDE_WIDTH)) {
        Card(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).remembersFocus(card.key)) {
            Box {
                PosterImage(ImageUrls.backdrop(card.image), card.title, Modifier.fillMaxSize())
                if (card.progress > 0f) LinearProgressIndicator(progress = { card.progress }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp))
            }
        }
        Text(card.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        SourceLine(card.subtitle.orEmpty(), card.source)
    }
}

/** A titled horizontal row of cards. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvShelf(title: String, content: LazyListScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(vertical = 12.dp, horizontal = 8.dp), content = content)
    }
}

fun LazyListScope.posters(tiles: List<TvTile>, onOpen: (TvTile) -> Unit) {
    items(tiles, key = { it.ref.key }) { tile -> TvPoster(tile, onClick = { onOpen(tile) }) }
}
