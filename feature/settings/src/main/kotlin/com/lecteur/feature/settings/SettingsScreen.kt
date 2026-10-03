package com.lecteur.feature.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lecteur.core.data.settings.LibrarySettings
import com.lecteur.core.model.Formatters
import com.lecteur.core.model.HomeRow
import com.lecteur.core.model.PosterSize
import com.lecteur.core.model.ThemeMode
import com.lecteur.core.player.settings.DecoderMode
import com.lecteur.core.player.settings.PassthroughMode
import com.lecteur.core.player.tracks.LanguageCodes
import com.lecteur.core.player.tracks.SubtitleMode
import java.util.Locale

private val SCAN_INTERVALS = listOf(0, 3, 6, 12, 24)
private val SEEK_STEPS = listOf(5, 10, 15, 30, 60)
private val LONG_PRESS_SPEEDS = listOf(1.5f, 2f, 3f, 4f)
private val COUNTDOWNS = listOf(0, 5, 10, 15, 30)
private val LANGUAGE_CODES = listOf("fr", "en", "ja", "es", "de", "it", "ko", "pt", "ru", "zh", "ar", "nl", "pl", "tr")

/**
 * Every setting in one scrolling list: the library (folders, scan, language), the player, the appearance, the home rows, the
 * image cache. Folders and the "to verify" list are screens of their own, reached through [onOpenFolders] and [onOpenReview].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenFolders: () -> Unit,
    onOpenReview: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val cache by viewModel.cache.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }

    // The cache fills while the user browses: read its size again each time the screen comes back
    LaunchedEffect(Unit) { viewModel.refreshCache() }

    Scaffold(modifier = modifier, topBar = { TopAppBar(title = { Text("Réglages") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {

            SettingsSection("Bibliothèque") {
                ActionSetting(
                    "Dossiers et catégories",
                    if (ui.folderCount == 0) "Aucun dossier : ajoutez celui de vos films et séries" else "${ui.folderCount} dossier${if (ui.folderCount > 1) "s" else ""} surveillé${if (ui.folderCount > 1) "s" else ""} · analyse, pause, catégorie",
                    onClick = onOpenFolders
                )
                ActionSetting(
                    "Titres à vérifier",
                    if (ui.reviewCount == 0) "Tout est identifié" else "${ui.reviewCount} titre${if (ui.reviewCount > 1) "s" else ""} à confirmer ou à identifier",
                    onClick = onOpenReview
                )
                if (!ui.metadataConfigured) {
                    Text(
                        "Clé API TMDB absente : ajoutez tmdb.apiKey dans local.properties pour télécharger affiches et synopsis.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                SwitchSetting("Analyser au lancement", "Cherche les nouveaux fichiers à chaque ouverture de l'application", ui.library.scanOnLaunch, onChange = viewModel::setScanOnLaunch)
                ActionSetting("Analyse périodique", intervalLabel(ui.library.scanIntervalHours)) { dialog = SettingsDialog.SCAN_INTERVAL }
                ActionSetting("Langue des informations", languageTag(ui.library.metadataLanguage)) { dialog = SettingsDialog.METADATA_LANGUAGE }
            }

            SettingsSection("Lecteur") {
                SwitchSetting("Gestes tactiles", "Luminosité, volume, recherche, double appui", ui.player.gesturesEnabled) { v -> viewModel.updatePlayer { it.copy(gesturesEnabled = v) } }
                SwitchSetting("Amplifier le volume au-delà de 100 %", null, ui.player.volumeBoostEnabled) { v -> viewModel.updatePlayer { it.copy(volumeBoostEnabled = v) } }
                ActionSetting("Saut du double appui", "${ui.player.seekStepSeconds} secondes") { dialog = SettingsDialog.SEEK_STEP }
                ActionSetting("Vitesse de l'appui long", "×${trim(ui.player.longPressSpeed)}") { dialog = SettingsDialog.LONG_PRESS }
                SwitchSetting("Enchaîner automatiquement", "Lit l'épisode suivant, ou le fichier suivant d'un dossier", ui.player.autoPlayNext) { v -> viewModel.updatePlayer { it.copy(autoPlayNext = v) } }
                ActionSetting(
                    "Compte à rebours avant le suivant",
                    if (ui.player.nextCountdownSeconds == 0) "Aucun" else "${ui.player.nextCountdownSeconds} secondes avant la fin",
                    enabled = ui.player.autoPlayNext
                ) { dialog = SettingsDialog.COUNTDOWN }
                ActionSetting("Langues audio préférées", languageList(ui.player.preferredAudioLanguages)) { dialog = SettingsDialog.AUDIO_LANGUAGES }
                ActionSetting("Langues de sous-titres préférées", languageList(ui.player.preferredSubtitleLanguages)) { dialog = SettingsDialog.SUBTITLE_LANGUAGES }
                ActionSetting("Sous-titres", subtitleModeLabel(ui.player.subtitleMode)) { dialog = SettingsDialog.SUBTITLE_MODE }
                ActionSetting("Décodage vidéo", decoderLabel(ui.player.decoderMode)) { dialog = SettingsDialog.DECODER }
                ActionSetting("Sortie audio numérique (passthrough)", passthroughLabel(ui.player.passthrough)) { dialog = SettingsDialog.PASSTHROUGH }
                SwitchSetting("Tunneling", "Peut améliorer la synchronisation sur certains téléviseurs", ui.player.tunneling) { v -> viewModel.updatePlayer { it.copy(tunneling = v) } }
                SwitchSetting("Mode nuit", "Réduit l'écart entre dialogues et explosions", ui.player.nightMode) { v -> viewModel.updatePlayer { it.copy(nightMode = v) } }
                SwitchSetting("Lecture en arrière-plan", "Le son continue quand vous quittez l'application", ui.player.backgroundAudio) { v -> viewModel.updatePlayer { it.copy(backgroundAudio = v) } }
                SwitchSetting("Verrouiller la rotation", "Garde l'écran en paysage pendant la lecture", ui.player.lockedRotation) { v -> viewModel.updatePlayer { it.copy(lockedRotation = v) } }
                Text(
                    "Le style des sous-titres, les délais et l'égaliseur se règlent pendant la lecture.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            SettingsSection("Apparence") {
                ActionSetting("Thème", themeLabel(ui.appearance.themeMode)) { dialog = SettingsDialog.THEME }
                SwitchSetting(
                    "Couleurs dynamiques",
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "Reprend les couleurs de votre fond d'écran" else "Disponible à partir d'Android 12",
                    ui.appearance.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                    onChange = viewModel::setDynamicColor
                )
                ActionSetting("Taille des affiches", "${ui.posterSize.label} · grille de la bibliothèque") { dialog = SettingsDialog.POSTER_SIZE }
            }

            SettingsSection("Accueil") {
                Text(
                    "Choisissez les rangées de l'accueil et leur ordre. Une rangée vide n'apparaît pas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                val rows = ui.homeLayout.rows
                rows.forEachIndexed { index, config ->
                    HomeRowSetting(
                        row = config.row,
                        visible = config.visible,
                        canMoveUp = index > 0,
                        canMoveDown = index < rows.lastIndex,
                        onMove = { delta -> viewModel.moveHomeRow(config.row, delta) },
                        onVisible = { viewModel.setHomeRowVisible(config.row, it) }
                    )
                }
                TextButton(onClick = viewModel::resetHome, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Rétablir l'ordre d'origine") }
            }

            SettingsSection("Stockage") {
                ActionSetting(
                    "Cache des images",
                    "${Formatters.size(cache.first)} utilisés sur ${Formatters.size(cache.second)} · affiches et fonds, rechargés au besoin",
                    onClick = { dialog = SettingsDialog.CLEAR_CACHE }
                )
            }

            SettingsSection("À propos") {
                val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?" }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Lecteur Média $version", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Bibliothèque de films et de séries locale, sans serveur ni compte. Vos fichiers ne sont jamais modifiés.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text("Informations sur les films et les séries", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Ce produit utilise l'API TMDB mais n'est ni approuvé ni certifié par TMDB. Affiches, synopsis, distributions et notes proviennent de The Movie Database (themoviedb.org).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("Composants libres", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Lecture : AndroidX Media3 / ExoPlayer (Apache 2.0). Décodeurs audio (DTS, TrueHD, AC3, E-AC3, FLAC) : FFmpeg via nextlib, compilé sous licence LGPL 2.1 — la bibliothèque FFmpeg peut être remplacée par une version modifiée. Images : Coil (Apache 2.0).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    SettingsDialogs(dialog, ui, viewModel, onDismiss = { dialog = null })
}

@Composable
private fun HomeRowSetting(row: HomeRow, visible: Boolean, canMoveUp: Boolean, canMoveDown: Boolean, onMove: (Int) -> Unit, onVisible: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            row.title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (visible) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) { Icon(Icons.Rounded.ArrowUpward, contentDescription = "Monter ${row.title}") }
        IconButton(onClick = { onMove(1) }, enabled = canMoveDown) { Icon(Icons.Rounded.ArrowDownward, contentDescription = "Descendre ${row.title}") }
        IconButton(onClick = { onVisible(!visible) }) {
            Icon(if (visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, contentDescription = if (visible) "Masquer ${row.title}" else "Afficher ${row.title}")
        }
    }
}

private enum class SettingsDialog {
    SCAN_INTERVAL, METADATA_LANGUAGE, SEEK_STEP, LONG_PRESS, COUNTDOWN, AUDIO_LANGUAGES, SUBTITLE_LANGUAGES, SUBTITLE_MODE,
    DECODER, PASSTHROUGH, THEME, POSTER_SIZE, CLEAR_CACHE
}

@Composable
private fun SettingsDialogs(dialog: SettingsDialog?, ui: SettingsUi, vm: SettingsViewModel, onDismiss: () -> Unit) {
    when (dialog) {
        null -> Unit
        SettingsDialog.SCAN_INTERVAL -> SingleChoiceDialog("Analyse périodique", SCAN_INTERVALS, ui.library.scanIntervalHours, ::intervalLabel, vm::setScanInterval, onDismiss)
        SettingsDialog.METADATA_LANGUAGE -> SingleChoiceDialog(
            "Langue des informations", LibrarySettings.SUPPORTED_LANGUAGES, ui.library.metadataLanguage, ::languageTag, vm::setMetadataLanguage, onDismiss
        )
        SettingsDialog.SEEK_STEP -> SingleChoiceDialog("Saut du double appui", SEEK_STEPS, ui.player.seekStepSeconds, { "$it secondes" }, { v -> vm.updatePlayer { it.copy(seekStepSeconds = v) } }, onDismiss)
        SettingsDialog.LONG_PRESS -> SingleChoiceDialog("Vitesse de l'appui long", LONG_PRESS_SPEEDS, ui.player.longPressSpeed, { "×${trim(it)}" }, { v -> vm.updatePlayer { it.copy(longPressSpeed = v) } }, onDismiss)
        SettingsDialog.COUNTDOWN -> SingleChoiceDialog(
            "Compte à rebours", COUNTDOWNS, ui.player.nextCountdownSeconds, { if (it == 0) "Aucun" else "$it secondes avant la fin" },
            { v -> vm.updatePlayer { it.copy(nextCountdownSeconds = v) } }, onDismiss
        )
        SettingsDialog.AUDIO_LANGUAGES -> MultiChoiceDialog(
            "Langues audio", "Cochez dans l'ordre de préférence : la première disponible dans le fichier est choisie.",
            LANGUAGE_CODES.map { it to languageName(it) }, ui.player.preferredAudioLanguages,
            { v -> vm.updatePlayer { it.copy(preferredAudioLanguages = v) } }, onDismiss
        )
        SettingsDialog.SUBTITLE_LANGUAGES -> MultiChoiceDialog(
            "Langues de sous-titres", "Cochez dans l'ordre de préférence.",
            LANGUAGE_CODES.map { it to languageName(it) }, ui.player.preferredSubtitleLanguages,
            { v -> vm.updatePlayer { it.copy(preferredSubtitleLanguages = v) } }, onDismiss
        )
        SettingsDialog.SUBTITLE_MODE -> SingleChoiceDialog("Sous-titres", SubtitleMode.entries, ui.player.subtitleMode, ::subtitleModeLabel, { v -> vm.updatePlayer { it.copy(subtitleMode = v) } }, onDismiss)
        SettingsDialog.DECODER -> SingleChoiceDialog("Décodage vidéo", DecoderMode.entries, ui.player.decoderMode, ::decoderLabel, { v -> vm.updatePlayer { it.copy(decoderMode = v) } }, onDismiss)
        SettingsDialog.PASSTHROUGH -> SingleChoiceDialog("Sortie audio numérique", PassthroughMode.entries, ui.player.passthrough, ::passthroughLabel, { v -> vm.updatePlayer { it.copy(passthrough = v) } }, onDismiss)
        SettingsDialog.THEME -> SingleChoiceDialog("Thème", ThemeMode.entries, ui.appearance.themeMode, ::themeLabel, vm::setThemeMode, onDismiss)
        SettingsDialog.POSTER_SIZE -> SingleChoiceDialog("Taille des affiches", PosterSize.entries, ui.posterSize, { it.label }, vm::setPosterSize, onDismiss)
        SettingsDialog.CLEAR_CACHE -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Vider le cache des images ?") },
            text = { Text("Les affiches et les images de fond seront téléchargées de nouveau quand elles seront affichées. Sans connexion, elles resteront absentes jusque-là.") },
            confirmButton = { TextButton(onClick = { vm.clearCache(); onDismiss() }) { Text("Vider") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
        )
    }
}

internal fun intervalLabel(hours: Int): String = if (hours == 0) "Désactivée" else "Toutes les $hours heures"

internal fun trim(value: Float): String = if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString().replace('.', ',')

internal fun languageName(code: String): String =
    (LanguageCodes.displayName(code, Locale.FRENCH) ?: code).replaceFirstChar { it.titlecase(Locale.FRENCH) }

/** "fr-FR" -> "Français (France)". */
internal fun languageTag(tag: String): String =
    Locale.forLanguageTag(tag).getDisplayName(Locale.FRENCH).replaceFirstChar { it.titlecase(Locale.FRENCH) }

internal fun languageList(codes: List<String>): String =
    if (codes.isEmpty()) "Aucune : piste par défaut du fichier" else codes.joinToString(", ") { languageName(it) }

internal fun subtitleModeLabel(mode: SubtitleMode): String = when (mode) {
    SubtitleMode.OFF -> "Jamais (sauf sous-titres forcés)"
    SubtitleMode.AUTO -> "Automatique : seulement si l'audio n'est pas dans votre langue"
    SubtitleMode.ALWAYS -> "Toujours, dans la langue préférée"
}

internal fun decoderLabel(mode: DecoderMode): String = when (mode) {
    DecoderMode.AUTO -> "Automatique (matériel, logiciel en secours)"
    DecoderMode.SOFTWARE_PREFERRED -> "Logiciel de préférence"
    DecoderMode.HARDWARE_ONLY -> "Matériel seulement"
}

internal fun passthroughLabel(mode: PassthroughMode): String = when (mode) {
    PassthroughMode.AUTO -> "Automatique : flux direct quand la sortie le permet"
    PassthroughMode.OFF -> "Désactivé : toujours décoder"
}

internal fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.DARK -> "Sombre"
    ThemeMode.LIGHT -> "Clair"
    ThemeMode.SYSTEM -> "Selon le système"
}
