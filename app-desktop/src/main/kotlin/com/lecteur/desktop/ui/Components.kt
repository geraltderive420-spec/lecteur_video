package com.lecteur.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lecteur.core.model.ImageUrls
import com.lecteur.core.model.LibraryItem
import com.lecteur.core.model.MatchState
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.WatchStatus
import com.lecteur.desktop.data.HomeCard
import kotlinx.coroutines.launch

/** An image from TMDB or from disk, loaded off the UI thread, faded in. */
@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: @Composable BoxScope.() -> Unit = {}
) {
    val cache = app().services.images
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(url?.let(cache::cached)) }
    LaunchedEffect(url) {
        if (url != null && bitmap == null) bitmap = cache.load(url)
    }
    Box(modifier) {
        val b = bitmap
        if (b == null) placeholder()
        AnimatedVisibility(b != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = contentScale)
        }
    }
}

@Composable
fun PosterPlaceholder(title: String, kind: MediaKind) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceContainerHighest))
        ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(10.dp)) {
            Icon(if (kind == MediaKind.MOVIE) Icons.Filled.Movie else Icons.Filled.Tv, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(6.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Right-click menu of a poster, the same actions as the long-press sheet of the phone. */
@Composable
fun itemMenu(item: LibraryItem): () -> List<ContextMenuItem> {
    val a = app()
    return {
        val snap = a.services.store.snapshot.value
        val fav = snap.isFavorite(item.kind, item.id)
        listOfNotNull(
            ContextMenuItem("Lire") { a.play(item.kind, item.id) },
            ContextMenuItem("Lire depuis le début") { a.play(item.kind, item.id, fromStart = true) }.takeIf { item.kind == MediaKind.MOVIE },
            ContextMenuItem("Ouvrir la fiche") { a.open(item) },
            ContextMenuItem(if (item.watch == WatchStatus.WATCHED) "Marquer comme non vu" else "Marquer comme vu") {
                a.setWatched(item.kind, item.id, item.watch != WatchStatus.WATCHED)
            },
            ContextMenuItem(if (fav) "Retirer des favoris" else "Ajouter aux favoris") { a.toggleFavorite(item.kind, item.id) },
            ContextMenuItem("Ajouter à une liste…") { a.dialog = AppDialog.AddToList(item.kind, item.id, item.title) },
            ContextMenuItem("Diffuser sur un écran…") { a.castItem(item.kind, item.id) },
            ContextMenuItem("Corriger l'identification…") { a.dialog = AppDialog.FixMatch(item.kind, item.id, item.title, item.year) }
        )
    }
}

@Composable
fun PosterCard(
    item: LibraryItem,
    width: Dp,
    subtitle: String? = null,
    progress: Float = item.progress,
    onPlay: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(if (hovered) 1.04f else 1f)
    ContextMenuArea(items = itemMenu(item)) {
        Column(
            Modifier.width(width).hoverable(interaction).pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick)
        ) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(2f / 3f).scale(scale).clip(RoundedCornerShape(8.dp))
                    .then(if (hovered) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)
            ) {
                RemoteImage(ImageUrls.poster(item.posterPath), Modifier.fillMaxSize()) { PosterPlaceholder(item.title, item.kind) }
                if (!item.isAvailable) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                        Text("Hors ligne", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                }
                when {
                    item.watch == WatchStatus.WATCHED -> Icon(
                        Icons.Filled.CheckCircle, "Vu", tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    )
                    item.kind == MediaKind.SERIES && item.unitCount > 0 && item.watchedCount < item.unitCount -> Surface(
                        color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                    ) {
                        Text("${item.unitCount - item.watchedCount}", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
                if (item.matchState == MatchState.TO_VERIFY) {
                    Icon(Icons.Outlined.HelpOutline, "À vérifier", tint = Color(0xFFFFD54F), modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(20.dp))
                }
                if (hovered && item.isAvailable && onPlay != null) {
                    FilledIconButton(
                        onClick = onPlay, modifier = Modifier.align(Alignment.Center).size(52.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f))
                    ) { Icon(Icons.Filled.PlayArrow, "Lire", modifier = Modifier.size(32.dp)) }
                }
                if (progress > 0f) {
                    LinearProgressIndicator(
                        progress = { progress }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                        color = MaterialTheme.colorScheme.primary, trackColor = Color.Black.copy(alpha = 0.5f)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle ?: listOfNotNull(item.year?.toString(), if (item.kind == MediaKind.SERIES && item.unitCount > 0) "${item.unitCount} ép." else null).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** A titled horizontal row of posters with arrow buttons (a mouse has no horizontal swipe). */
@Composable
fun PosterRow(title: String, cards: List<HomeCard>, width: Dp = 150.dp, onSeeAll: (() -> Unit)? = null, onCard: (HomeCard) -> Unit, onPlay: (HomeCard) -> Unit) {
    if (cards.isEmpty()) return
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(bottom = 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (onSeeAll != null) {
                Text(
                    "Tout voir", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onSeeAll).padding(8.dp)
                )
            }
            SmallIcon(Icons.Filled.ChevronLeft, "Précédent") { scope.launch { state.animateScrollToItem((state.firstVisibleItemIndex - 5).coerceAtLeast(0)) } }
            SmallIcon(Icons.Filled.ChevronRight, "Suivant") { scope.launch { state.animateScrollToItem((state.firstVisibleItemIndex + 5).coerceAtMost(cards.lastIndex)) } }
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(state = state, contentPadding = PaddingValues(horizontal = 32.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(cards, key = { it.item.key + (it.episodeId ?: "") }) { card ->
                PosterCard(card.item, width, card.subtitle, card.progress, onPlay = { onPlay(card) }) { onCard(card) }
            }
        }
    }
}

@Composable
fun SmallIcon(icon: ImageVector, description: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center
    ) { Icon(icon, description, tint = tint, modifier = Modifier.size(22.dp)) }
}

@Composable
fun Badge(text: String, highlight: Boolean = false) {
    Surface(
        color = if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(5.dp)
    ) {
        Text(
            text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            color = if (highlight) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun PersonAvatar(name: String, profile: String?, role: String?, onClick: () -> Unit) {
    Column(
        Modifier.width(110.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(90.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
            RemoteImage(ImageUrls.profile(profile), Modifier.fillMaxSize()) {
                Icon(Icons.Filled.Person, null, Modifier.align(Alignment.Center).size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (role != null) Text(role, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, action: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null) {
                Spacer(Modifier.height(20.dp))
                action()
            }
        }
    }
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 32.dp, end = 24.dp, top = 24.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

/** Hover tooltip, desktop style. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Tip(text: String, content: @Composable () -> Unit) {
    androidx.compose.foundation.TooltipArea(
        tooltip = {
            Surface(color = MaterialTheme.colorScheme.inverseSurface, shape = RoundedCornerShape(6.dp)) {
                Text(text, color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        },
        delayMillis = 500
    ) { content() }
}

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
