package com.lecteur.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lecteur.core.model.Formatters
import com.lecteur.core.model.HomeLayout
import com.lecteur.core.model.ThemeMode
import com.lecteur.core.player.tracks.SubtitleMode
import com.lecteur.desktop.BuildConfig
import com.lecteur.desktop.background.Autostart
import com.lecteur.desktop.data.AppDirs
import com.lecteur.desktop.data.LibrarySnapshot
import com.lecteur.desktop.data.ResumeBehavior
import com.lecteur.desktop.data.SubtitleColor
import com.lecteur.desktop.data.SubtitleSize
import com.lecteur.desktop.data.label

private val METADATA_LANGUAGES = listOf("fr-FR" to "Français", "fr-CA" to "Français (Canada)", "en-US" to "English", "es-ES" to "Español", "de-DE" to "Deutsch", "it-IT" to "Italiano", "pt-BR" to "Português (Brasil)")
private val TRACK_LANGUAGES = listOf("fr", "en", "es", "de", "it", "pt", "ja", "ko", "zh", "ar", "ru", "nl")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val a = app()
    val s by a.services.settings.state.collectAsState()
    val update = a.services.settings::update

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 40.dp)) {
        ScreenTitle("Paramètres")
        Column(Modifier.padding(horizontal = 32.dp).widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Section("Apparence") {
                Choice("Thème", listOf(ThemeMode.DARK to "Sombre", ThemeMode.LIGHT to "Clair", ThemeMode.SYSTEM to "Comme Windows"), s.themeMode) { m -> update { it.copy(themeMode = m) } }
            }

            Section("Lecture") {
                Choice("Reprise", ResumeBehavior.entries.map { it to it.label }, s.resumeBehavior) { r -> update { it.copy(resumeBehavior = r) } }
                Toggle("Enchaîner automatiquement", "Lit l'épisode suivant, ou le fichier suivant d'un dossier", s.autoPlayNext) { v -> update { it.copy(autoPlayNext = v) } }
                Choice("Compte à rebours avant le suivant", listOf(0 to "Aucun (à la toute fin)", 5 to "5 s", 10 to "10 s", 15 to "15 s", 20 to "20 s", 30 to "30 s"), s.upNextSeconds) { v -> update { it.copy(upNextSeconds = v) } }
                Toggle("Amplifier le volume au-delà de 100 %", "Jusqu'à 200 % pour les fichiers trop faibles", s.volumeBoost) { v -> update { it.copy(volumeBoost = v) } }
                Toggle("Mode nuit", "Réduit l'écart entre dialogues et explosions", s.nightMode) { v -> update { it.copy(nightMode = v) } }
                Choice("Saut avant / arrière (flèches)", listOf(5, 10, 15, 30).map { it to "$it s" }, s.seekStepSeconds) { v -> update { it.copy(seekStepSeconds = v) } }
                Toggle("Décodage matériel (GPU)", "Plus fluide et moins gourmand ; à couper en cas d'image corrompue", s.hardwareDecoding) { v -> update { it.copy(hardwareDecoding = v) } }
                Toggle("Audio passthrough (ampli HDMI / S/PDIF)", "Envoie Dolby / DTS tels quels à l'amplificateur. Pris en compte au prochain démarrage.", s.passthrough) { v -> update { it.copy(passthrough = v) } }
            }

            Section("Langues des pistes") {
                LanguageOrder("Audio préféré (dans l'ordre)", s.audioLanguages) { list -> update { it.copy(audioLanguages = list) } }
                LanguageOrder("Sous-titres préférés (dans l'ordre)", s.subtitleLanguages) { list -> update { it.copy(subtitleLanguages = list) } }
                Choice("Afficher les sous-titres", SubtitleMode.entries.map { it to it.label }, s.subtitleMode) { m -> update { it.copy(subtitleMode = m) } }
                Text("Une série retient la langue choisie pendant la lecture (prioritaire sur ces réglages).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Section("Apparence des sous-titres") {
                Choice("Taille", SubtitleSize.entries.map { it to it.label }, s.subtitleSize) { v -> update { it.copy(subtitleSize = v) } }
                Choice("Couleur du texte", SubtitleColor.entries.map { it to it.label }, s.subtitleColor) { v -> update { it.copy(subtitleColor = v) } }
                Toggle("Fond semi-transparent", "Plus lisible sur les images claires", s.subtitleBackground) { v -> update { it.copy(subtitleBackground = v) } }
                Choice("Position verticale", listOf(0 to "En bas", 40 to "Un peu plus haut", 100 to "Plus haut", 180 to "Bien plus haut"), s.subtitleMargin) { v -> update { it.copy(subtitleMargin = v) } }
                Text("Pris en compte au prochain démarrage de l'application. Les animations ASS avancées ne sont pas toutes rendues.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Section("Bibliothèque et métadonnées") {
                Choice("Langue des fiches (TMDB)", METADATA_LANGUAGES, s.metadataLanguage) { l -> update { it.copy(metadataLanguage = l) } }
                Toggle("Analyser les dossiers au démarrage", "Détecte les fichiers ajoutés, déplacés ou supprimés", s.scanOnStart) { v -> update { it.copy(scanOnStart = v) } }
                Choice("Analyse périodique", listOf(0 to "Jamais", 1 to "Toutes les heures", 3 to "Toutes les 3 heures", 6 to "Toutes les 6 heures", 12 to "Toutes les 12 heures", 24 to "Une fois par jour"), s.periodicScanHours) { v -> update { it.copy(periodicScanHours = v) } }
                var key by remember { mutableStateOf(s.tmdbApiKey) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        key, { key = it.trim() }, singleLine = true, modifier = Modifier.weight(1f),
                        label = { Text(if (BuildConfig.TMDB_API_KEY.isNotBlank()) "Clé API TMDB (facultatif : une clé est déjà intégrée)" else "Clé API TMDB") },
                        visualTransformation = PasswordVisualTransformation()
                    )
                    TextButton(enabled = key != s.tmdbApiKey, onClick = { update { it.copy(tmdbApiKey = key) }; a.services.scanner.identifyOnly() }) { Text("Enregistrer") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { a.root(Screen.Folders) }) { Text("Gérer les dossiers") }
                    OutlinedButton(onClick = { a.services.scanner.identifyOnly() }) { Text("Relancer l'identification") }
                }
            }

            Section("Écran d'accueil") {
                val layout = HomeLayout.decode(s.homeLayout)
                layout.rows.forEach { row ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(row.visible, { v -> update { it.copy(homeLayout = HomeLayout.encode(layout.setVisible(row.row, v))) } })
                        Text(row.row.title, modifier = Modifier.weight(1f))
                        IconButton(onClick = { update { it.copy(homeLayout = HomeLayout.encode(layout.move(row.row, -1))) } }) { Icon(Icons.Filled.ArrowUpward, "Monter") }
                        IconButton(onClick = { update { it.copy(homeLayout = HomeLayout.encode(layout.move(row.row, 1))) } }) { Icon(Icons.Filled.ArrowDownward, "Descendre") }
                    }
                }
            }

            Section("Diffusion") {
                Toggle("Recevoir depuis le téléphone", "Ce PC apparaît comme un écran dans l'application du téléphone", s.receiverEnabled) { v ->
                    update { it.copy(receiverEnabled = v) }
                    if (v) a.services.receiver.start() else a.services.receiver.stop()
                }
                OutlinedButton(onClick = { a.root(Screen.Cast) }) { Text("Code d'appairage et télécommande") }
            }

            Section("Arrière-plan") {
                Toggle("Démarrer avec Windows", if (Autostart.available) "Dans la zone de notification, sans fenêtre : partage et réception prêts dès l'ouverture de session" else "Disponible dans la version installée", a.startWithWindows) { v -> a.changeStartWithWindows(v) }
                Toggle("Rester actif à la fermeture de la fenêtre", "L'application continue près de l'horloge ; clic droit sur l'icône → Quitter pour l'arrêter", s.closeToTray) { v -> update { it.copy(closeToTray = v) } }
            }

            Section("Stockage") {
                var cache by remember { mutableStateOf(a.services.images.diskUsageBytes()) }
                Text("Images en cache : ${Formatters.size(cache)}")
                Text("Dossier des données : ${AppDirs.root.absolutePath}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { a.services.images.clear(); cache = 0 }) { Text("Vider le cache d'images") }
                    OutlinedButton(onClick = { runCatching { java.awt.Desktop.getDesktop().open(AppDirs.root) } }) { Text("Ouvrir le dossier") }
                }
            }

            Section("À propos") {
                Text("Lecteur Média pour Windows ${BuildConfig.VERSION}")
                Text("Lecture : libVLC ${runCatching { uk.co.caprica.vlcj.binding.lib.LibVlc.libvlc_get_version() }.getOrNull() ?: "indisponible"} · Métadonnées : TMDB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Raccourcis du lecteur : Espace lecture/pause · ←/→ saut · ↑/↓ volume · F ou double-clic plein écran · M muet · S sous-titres · A audio · N suivant · Échap quitter", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, onChange)
    }
}

@Composable
private fun <T> Choice(title: String, options: List<Pair<T, String>>, value: T, onChange: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }) { Text(options.firstOrNull { it.first == value }?.second ?: value.toString()) }
            DropdownMenu(open, onDismissRequest = { open = false }) {
                options.forEach { (v, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onChange(v) }) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageOrder(title: String, value: List<String>, onChange: (List<String>) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TRACK_LANGUAGES.forEach { code ->
                val index = value.indexOf(code)
                FilterChip(
                    selected = index >= 0,
                    onClick = { onChange(if (index >= 0) value - code else value + code) },
                    label = { Text((if (index >= 0) "${index + 1}. " else "") + LibrarySnapshot.languageName(code)) }
                )
            }
        }
    }
}

