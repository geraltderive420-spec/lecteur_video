package com.lecteur.core.designsystem.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lecteur.core.model.Formatters
import com.lecteur.core.model.ImageUrls
import com.lecteur.core.model.LibraryItem
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.WatchStatus

/** A picture from the shared image cache, or the first letter of the title on a flat background when there is none. */
@Composable
fun PosterImage(url: String?, fallbackText: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (url == null) {
            Text(
                fallbackText.trim().take(1).uppercase(),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            AsyncImage(model = url, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Poster with the state of the title on it: seen tick, progress bar, unseen episode count, "unavailable" dimming. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(
    item: LibraryItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    showSubtitle: Boolean = true
) {
    val description = buildString {
        append(item.title)
        item.year?.let { append(", ").append(it) }
        when (item.watch) {
            WatchStatus.WATCHED -> append(", vu")
            WatchStatus.IN_PROGRESS -> append(", en cours")
            WatchStatus.UNWATCHED -> Unit
        }
        if (!item.isAvailable) append(", indisponible")
    }
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))) {
            PosterImage(ImageUrls.poster(item.posterPath), item.title, Modifier.fillMaxSize().alpha(if (item.isAvailable) 1f else 0.4f))
            if (!item.isAvailable) {
                Icon(
                    Icons.Rounded.LinkOff, contentDescription = null, tint = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(28.dp)
                )
            }
            WatchBadge(item, Modifier.align(Alignment.TopEnd).padding(6.dp))
            val fraction = progressOf(item)
            if (fraction > 0f) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                    trackColor = Color.Black.copy(alpha = 0.5f)
                )
            }
        }
        Text(
            item.title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp)
        )
        if (showSubtitle) {
            val subtitle = listOfNotNull(item.year?.toString(), Formatters.rating(item.rating)?.let { "★ $it" }).joinToString("  ")
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
        }
    }
}

/** What fills the bar under a poster: a film's progress, or the share of a series' episodes already seen. */
fun progressOf(item: LibraryItem): Float = when {
    item.watch == WatchStatus.WATCHED -> 0f
    item.kind == MediaKind.MOVIE -> item.progress
    item.watch == WatchStatus.IN_PROGRESS && item.unitCount > 0 -> item.watchedCount.toFloat() / item.unitCount
    else -> 0f
}

@Composable
private fun WatchBadge(item: LibraryItem, modifier: Modifier) {
    when {
        item.watch == WatchStatus.WATCHED -> Box(
            modifier.size(22.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(15.dp))
        }
        item.kind == MediaKind.SERIES && item.unitCount > item.watchedCount && item.watchedCount > 0 -> Box(
            modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(11.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("${item.unitCount - item.watchedCount}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

/** Wide card of the "Reprendre" and "Prochains épisodes" rows: picture, a progress bar when started, two lines of text. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LandscapeCard(
    imageUrl: String?,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    progress: Float = 0f,
    detail: String? = null,
    isAvailable: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(title, subtitle, detail, if (isAvailable) null else "indisponible").joinToString(", ")
            }
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
            PosterImage(imageUrl, title, Modifier.fillMaxSize().alpha(if (isAvailable) 1f else 0.4f))
            if (!isAvailable) {
                Icon(Icons.Rounded.LinkOff, contentDescription = null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(28.dp))
            }
            if (progress > 0f) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                    trackColor = Color.Black.copy(alpha = 0.5f)
                )
            }
        }
        Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp))
        if (subtitle != null) {
            Text(
                subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp)
            )
        }
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(horizontal = 2.dp))
        }
    }
}

/** Round photo of an actor or director, name and role under it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PersonCard(name: String, role: String?, profilePath: String?, modifier: Modifier = Modifier, width: Dp = 84.dp, onClick: () -> Unit) {
    Column(
        modifier.width(width).clip(RoundedCornerShape(8.dp)).combinedClickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PosterImage(ImageUrls.profile(profilePath), name, Modifier.size(width - 12.dp).clip(CircleShape))
        Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        if (!role.isNullOrBlank()) {
            Text(role, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

/** One short line of badges ("4K", "Dolby Vision", "TrueHD 7.1"...). */
@Composable
fun BadgeRow(badges: List<String>, modifier: Modifier = Modifier) {
    if (badges.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        badges.forEach { Badge(it) }
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
fun RatingLabel(rating: Float?, modifier: Modifier = Modifier) {
    val text = Formatters.rating(rating) ?: return
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 3.dp))
    }
}
