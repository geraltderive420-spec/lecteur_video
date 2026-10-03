package com.lecteur.core.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveRedEye
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.lecteur.core.model.LibraryItem
import com.lecteur.core.model.WatchStatus

/**
 * Long-press menu of a poster. [isFavorite] is null until it is known: the favourite line then waits instead of offering a
 * toggle that could do the wrong thing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemActionsSheet(
    item: LibraryItem,
    isFavorite: Boolean?,
    onPlay: () -> Unit,
    onToggleWatched: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenDetails: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), maxLines = 2)
            ActionRow(Icons.Rounded.PlayArrow, if (item.watch == WatchStatus.IN_PROGRESS) "Reprendre" else "Lire", item.isAvailable) {
                onDismiss(); onPlay()
            }
            val watched = item.watch == WatchStatus.WATCHED
            ActionRow(
                if (watched) Icons.Rounded.VisibilityOff else Icons.Rounded.RemoveRedEye,
                if (watched) "Marquer comme non vu" else "Marquer comme vu",
                true
            ) { onDismiss(); onToggleWatched() }
            ActionRow(
                if (isFavorite == true) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                if (isFavorite == true) "Retirer des favoris" else "Ajouter aux favoris",
                isFavorite != null
            ) { onDismiss(); onToggleFavorite() }
            ActionRow(Icons.Rounded.Info, "Voir la fiche", true) { onDismiss(); onOpenDetails() }
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Text(label, color = tint, modifier = Modifier.padding(start = 20.dp))
    }
}
