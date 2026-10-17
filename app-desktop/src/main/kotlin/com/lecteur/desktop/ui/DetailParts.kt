package com.lecteur.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lecteur.core.model.AudioCodec
import com.lecteur.core.model.Formatters
import com.lecteur.core.model.HdrType
import com.lecteur.core.model.ImageUrls
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.ResolutionTier
import com.lecteur.core.model.channelLabel
import com.lecteur.core.model.label
import com.lecteur.desktop.data.AudioTrackRow
import com.lecteur.desktop.data.FileEntry
import com.lecteur.desktop.data.LibrarySnapshot
import com.lecteur.desktop.data.SubtitleTrackRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.net.URI

data class FileTracks(val audio: List<AudioTrackRow>, val subtitles: List<SubtitleTrackRow>)

/** Tracks of a file, read from the database off the UI thread. */
@Composable
fun rememberTracks(fileId: Long?): FileTracks? {
    val store = app().services.store
    var tracks by remember(fileId) { mutableStateOf<FileTracks?>(null) }
    LaunchedEffect(fileId) {
        tracks = fileId?.let { id -> withContext(Dispatchers.IO) { FileTracks(store.audioTracks(id), store.subtitleTracks(id)) } }
    }
    return tracks
}

private val AUDIO_RANK = listOf(
    AudioCodec.DTS_X, AudioCodec.E_AC3_JOC, AudioCodec.TRUEHD, AudioCodec.DTS_HD_MA, AudioCodec.PCM, AudioCodec.FLAC,
    AudioCodec.DTS, AudioCodec.E_AC3, AudioCodec.AC3, AudioCodec.OPUS, AudioCodec.AAC, AudioCodec.VORBIS, AudioCodec.MP3
)

fun bestAudioLabel(tracks: List<AudioTrackRow>): String? {
    val best = tracks.minByOrNull { AUDIO_RANK.indexOf(it.codec).let { i -> if (i < 0) 99 else i } * 100 - it.channels } ?: return null
    return listOfNotNull(best.codec.label(), channelLabel(best.channels)).joinToString(" ").ifBlank { null }
}

/** "4K", "Dolby Vision", "HEVC", "TrueHD 7.1"... */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FileBadges(file: FileEntry, tracks: FileTracks?) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ResolutionTier.badge(file.file.width, file.file.height)?.let { Badge(it, highlight = it == "4K") }
        file.file.hdrType.label()?.let { Badge(it, highlight = file.file.hdrType != HdrType.NONE) }
        file.file.videoCodec.label()?.let { Badge(it) }
        tracks?.audio?.let(::bestAudioLabel)?.let { Badge(it) }
        tracks?.audio?.mapNotNull { LibrarySnapshot.normaliseLanguage(it.language ?: "") }?.distinct()?.takeIf { it.isNotEmpty() }?.let { langs ->
            Badge("Audio : " + langs.joinToString(", ") { it.uppercase() })
        }
        tracks?.subtitles?.mapNotNull { LibrarySnapshot.normaliseLanguage(it.language ?: "") }?.distinct()?.takeIf { it.isNotEmpty() }?.let { langs ->
            Badge("ST : " + langs.joinToString(", ") { it.uppercase() })
        }
    }
}

/** Backdrop + gradient + the back button: the top of every detail page. */
@Composable
fun DetailHeader(backdrop: String?, height: Int = 460, content: @Composable BoxScope.() -> Unit) {
    val a = app()
    Box(Modifier.fillMaxWidth().height(height.dp)) {
        RemoteImage(ImageUrls.url(backdrop, "w1280"), Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.25f), 0.55f to MaterialTheme.colorScheme.background.copy(alpha = 0.7f), 1f to MaterialTheme.colorScheme.background)))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to MaterialTheme.colorScheme.background.copy(alpha = 0.85f), 0.6f to Color.Transparent)))
        content()
        if (a.canGoBack) {
            IconButton(
                onClick = a::back, modifier = Modifier.padding(16.dp).align(Alignment.TopStart),
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.4f), contentColor = Color.White)
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
        }
    }
}

@Composable
fun DetailPoster(path: String?, title: String, kind: MediaKind) {
    Box(Modifier.width(210.dp).height(315.dp).clip(RoundedCornerShape(10.dp))) {
        RemoteImage(ImageUrls.poster(path), Modifier.fillMaxSize()) { PosterPlaceholder(title, kind) }
    }
}

@Composable
fun MetaLine(parts: List<String?>, rating: Float?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(parts.filterNotNull().filter { it.isNotBlank() }.joinToString("  ·  "), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Formatters.rating(rating)?.let {
            Spacer(Modifier.width(14.dp))
            Icon(Icons.Filled.Star, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(it, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun Overview(text: String?) {
    if (text.isNullOrBlank()) return
    var expanded by remember(text) { mutableStateOf(false) }
    Text(
        text, style = MaterialTheme.typography.bodyLarge, maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 820.dp).padding(vertical = 2.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { expanded = !expanded }
    )
}

/** Technical sheet of a file: where it is, how big, what it holds. */
@Composable
fun TechSheet(file: FileEntry, tracks: FileTracks?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Info("Fichier", file.file.uri)
        Info("Taille", Formatters.size(file.file.sizeBytes))
        Formatters.runtimeOfMs(file.file.durationMs)?.let { Info("Durée", it) }
        if ((file.file.width ?: 0) > 0) Info("Image", "${file.file.width} × ${file.file.height}" + (file.file.videoCodec.label()?.let { " · $it" } ?: "") + (file.file.hdrType.label()?.let { " · $it" } ?: ""))
        tracks?.audio?.takeIf { it.isNotEmpty() }?.let { list ->
            Info("Audio", list.joinToString("\n") { t -> listOfNotNull(LibrarySnapshot.languageName(t.language), t.codec.label(), channelLabel(t.channels), t.title).joinToString(" · ") })
        }
        tracks?.subtitles?.takeIf { it.isNotEmpty() }?.let { list ->
            Info("Sous-titres", list.joinToString("\n") { t -> listOfNotNull(LibrarySnapshot.languageName(t.language), t.codec?.uppercase(), if (t.isForced) "forcés" else null, if (t.externalPath != null) "externe" else null).joinToString(" · ") })
        }
        if (!file.file.isAvailable) Text("Fichier hors ligne : le disque qui le contient n'est pas branché.", color = MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

fun openInBrowser(url: String?) {
    url ?: return
    runCatching { Desktop.getDesktop().browse(URI(url)) }
}

fun showInExplorer(path: String) {
    runCatching { ProcessBuilder("explorer.exe", "/select,", File(path).absolutePath).start() }
}
