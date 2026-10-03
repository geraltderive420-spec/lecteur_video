package com.lecteur.feature.details

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lecteur.core.designsystem.components.Badge
import com.lecteur.core.designsystem.components.PersonCard
import com.lecteur.core.designsystem.components.PosterImage
import com.lecteur.core.designsystem.components.SectionHeader
import com.lecteur.core.model.CastCredit
import com.lecteur.core.model.FileVersion
import com.lecteur.core.model.Formatters
import com.lecteur.core.model.Genre
import com.lecteur.core.model.ImageUrls
import com.lecteur.core.model.label

/** Backdrop with the title logo on it when TMDB has one, and a round back button that stays readable on any picture. */
@Composable
fun DetailHeader(backdropPath: String?, logoPath: String?, title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().aspectRatio(16f / 9f).heightIn(max = 320.dp)) {
        PosterImage(ImageUrls.backdrop(backdropPath), title, Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent, MaterialTheme.colorScheme.background)))
        )
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape)
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Retour", tint = Color.White)
        }
        ImageUrls.logo(logoPath)?.let { logo ->
            AsyncImage(
                model = logo,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 12.dp).size(width = 200.dp, height = 70.dp)
            )
        }
    }
}

/** A synopsis that shows a few lines and opens fully on a tap. */
@Composable
fun ExpandableText(text: String, modifier: Modifier = Modifier, collapsedLines: Int = 4) {
    var expanded by remember(text) { mutableStateOf(false) }
    var overflowing by remember(text) { mutableStateOf(false) }
    Column(modifier.clickable(enabled = overflowing || expanded) { expanded = !expanded }) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow }
        )
        if (overflowing || expanded) {
            Text(if (expanded) "Réduire" else "Lire la suite", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GenreChips(genres: List<Genre>, onClick: (Genre) -> Unit, modifier: Modifier = Modifier) {
    if (genres.isEmpty()) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        genres.forEach { genre -> SuggestionChip(onClick = { onClick(genre) }, label = { Text(genre.name) }) }
    }
}

@Composable
fun CastRow(title: String, credits: List<CastCredit>, onOpenPerson: (Long, String) -> Unit) {
    if (credits.isEmpty()) return
    Column {
        SectionHeader(title)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(credits, key = { "${it.personId}-${it.role}" }) { credit ->
                PersonCard(name = credit.name, role = credit.role, profilePath = credit.profilePath, onClick = { onOpenPerson(credit.personId, credit.name) })
            }
        }
    }
}

/** What the file really holds, read from it by the scan: nothing here comes from the network. */
@Composable
fun FileInfoBlock(version: FileVersion, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Fichier", style = MaterialTheme.typography.titleSmall)
        Text(version.fileName, style = MaterialTheme.typography.bodyMedium)
        Text(version.displayPath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val facts = listOfNotNull(Formatters.size(version.sizeBytes), Formatters.runtimeOfMs(version.durationMs)).joinToString(" · ")
        Text(facts, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val languages = buildList {
            if (version.badges.audioLanguages.isNotEmpty()) add("Audio : " + version.badges.audioLanguages.joinToString(", "))
            if (version.badges.subtitleLanguages.isNotEmpty()) add("Sous-titres : " + version.badges.subtitleLanguages.joinToString(", "))
        }
        if (languages.isNotEmpty()) Text(languages.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!version.isAvailable) Text("Indisponible : le support est débranché", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/** The copies of a film (1080p, 4K...): the chosen one is what "Lire" plays. */
@Composable
fun VersionPicker(versions: List<FileVersion>, selectedId: Long?, onSelect: (Long) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text("Versions", style = MaterialTheme.typography.titleSmall)
        versions.forEach { version ->
            Row(
                Modifier.fillMaxWidth().clickable(enabled = version.isAvailable) { onSelect(version.mediaFileId) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = version.mediaFileId == selectedId, onClick = { onSelect(version.mediaFileId) }, enabled = version.isAvailable)
                Column(Modifier.weight(1f)) {
                    Text(version.shortLabel, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        listOfNotNull(Formatters.size(version.sizeBytes), if (version.isAvailable) null else "indisponible").joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

fun FileVersion.badgeTexts(): List<String> =
    listOfNotNull(badges.resolution, badges.hdr.label(), badges.videoCodec.label(), badges.audio)

@Composable
fun BadgesLine(version: FileVersion?, modifier: Modifier = Modifier) {
    val texts = version?.badgeTexts().orEmpty()
    if (texts.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) { texts.forEach { Badge(it) } }
}

/** The year as a link: it opens everything of the same decade in the library. */
@Composable
fun YearLink(year: Int, onOpenYear: (Int) -> Unit, modifier: Modifier = Modifier) {
    Text(
        "$year",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.clickable { onOpenYear(year / 10 * 10) }
    )
}

class MenuEntry(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

@Composable
fun OverflowMenu(entries: List<MenuEntry>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Plus d'actions") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            entries.forEach { entry ->
                DropdownMenuItem(text = { Text(entry.label) }, enabled = entry.enabled, onClick = { open = false; entry.onClick() })
            }
        }
    }
}

/** TMDB's required notice: any screen showing data from the service has to credit it. */
@Composable
fun AttributionFooter(modifier: Modifier = Modifier) {
    Text(
        "Ce produit utilise l'API TMDB mais n'est ni approuvé ni certifié par TMDB.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp)
    )
}
