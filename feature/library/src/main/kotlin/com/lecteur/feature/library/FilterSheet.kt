package com.lecteur.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lecteur.core.model.FilterOptions
import com.lecteur.core.model.Formatters
import com.lecteur.core.model.HdrFilter
import com.lecteur.core.model.LibraryFilters
import com.lecteur.core.model.ResolutionTier
import com.lecteur.core.model.WatchFilter

/** All the combinable filters of a shelf. Every tap applies at once: the grid behind the sheet updates as the user goes. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    filters: LibraryFilters,
    options: FilterOptions?,
    onChange: ((LibraryFilters) -> LibraryFilters) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Filtres", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (filters.activeCount > 0) TextButton(onClick = onClear) { Text("Tout effacer") }
                TextButton(onClick = onDismiss) { Text("Fermer") }
            }

            Group("État") {
                WatchFilter.entries.forEach { watch ->
                    FilterChip(filters.watch == watch, { onChange { it.copy(watch = watch) } }, { Text(LibraryLabels.watch(watch)) })
                }
            }

            if (options != null && options.genres.isNotEmpty()) Group("Genre") {
                options.genres.forEach { genre ->
                    FilterChip(filters.genreId == genre.id, { onChange { it.copy(genreId = if (it.genreId == genre.id) null else genre.id) } }, { Text(genre.name) })
                }
            }

            if (options != null && options.decades.isNotEmpty()) Group("Année") {
                options.decades.forEach { decade ->
                    val selected = filters.yearFrom == decade && filters.yearTo == decade + 9
                    FilterChip(
                        selected,
                        { onChange { if (selected) it.copy(yearFrom = null, yearTo = null) else it.copy(yearFrom = decade, yearTo = decade + 9) } },
                        { Text(Formatters.decade(decade)) }
                    )
                }
            }

            Group("Résolution") {
                ResolutionTier.entries.forEach { tier ->
                    FilterChip(filters.resolution == tier, { onChange { it.copy(resolution = tier) } }, { Text(tier.label) })
                }
            }

            Group("HDR") {
                HdrFilter.entries.forEach { hdr ->
                    FilterChip(filters.hdr == hdr, { onChange { it.copy(hdr = hdr) } }, { Text(LibraryLabels.hdr(hdr)) })
                }
            }

            if (options != null && options.certifications.isNotEmpty()) Group("Classification d'âge") {
                options.certifications.forEach { certification ->
                    FilterChip(
                        filters.certification == certification,
                        { onChange { it.copy(certification = if (it.certification == certification) null else certification) } },
                        { Text(certification) }
                    )
                }
            }

            if (options != null && options.audioLanguages.isNotEmpty()) Group("Langue audio") {
                options.audioLanguages.forEach { language ->
                    FilterChip(
                        filters.audioLanguage == language.code,
                        { onChange { it.copy(audioLanguage = if (it.audioLanguage == language.code) null else language.code) } },
                        { Text(language.name.replaceFirstChar(Char::uppercase)) }
                    )
                }
            }

            if (options != null && options.folders.size > 1) Group("Dossier source") {
                options.folders.forEach { folder ->
                    FilterChip(
                        filters.folderId == folder.id,
                        { onChange { it.copy(folderId = if (it.folderId == folder.id) null else folder.id) } },
                        { Text(folder.displayPath.trimEnd('/').substringAfterLast('/').ifEmpty { folder.displayPath }) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Group(title: String, chips: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
}
