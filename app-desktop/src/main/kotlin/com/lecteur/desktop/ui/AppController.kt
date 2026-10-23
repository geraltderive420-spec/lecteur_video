package com.lecteur.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.lecteur.core.model.LibraryFilters
import com.lecteur.core.model.LibraryItem
import com.lecteur.core.model.LibrarySection
import com.lecteur.core.model.MediaKind
import com.lecteur.desktop.AppServices
import com.lecteur.desktop.data.LibrarySnapshot
import com.lecteur.desktop.data.seriesPlayTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data class Library(val section: LibrarySection, val filters: LibraryFilters = LibraryFilters(), val title: String? = null) : Screen
    data object Search : Screen
    data object Lists : Screen
    data class MovieDetail(val id: Long) : Screen
    data class SeriesDetail(val id: Long) : Screen
    data class Person(val id: Long) : Screen
    data object Folders : Screen
    data object Review : Screen
    data class Explorer(val path: String? = null) : Screen
    data object Cast : Screen
    data object Share : Screen
    data object Settings : Screen
}

/** Dialogs any screen can open. */
sealed interface AppDialog {
    data class AddToList(val kind: MediaKind, val id: Long, val title: String) : AppDialog
    data class CastTo(val mediaFileId: Long, val title: String) : AppDialog
    data class FixMatch(val kind: MediaKind, val id: Long, val title: String, val year: Int?) : AppDialog
    data class Confirm(val title: String, val text: String, val confirm: String, val action: () -> Unit) : AppDialog
}

/** Navigation stack, global dialogs and short notices: the UI state that is not a screen's own. */
class AppController(val services: AppServices) {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()

    var dialog by mutableStateOf<AppDialog?>(null)

    /** Fullscreen, mini player, controls visibility: survives from one video to the next. */
    val player = com.lecteur.desktop.ui.player.PlayerWindowState()
    var notice by mutableStateOf<String?>(null)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun go(screen: Screen) {
        if (stack.last() != screen) stack.add(screen)
    }

    /** Top-level destination from the side bar: the stack restarts there. */
    fun root(screen: Screen) {
        stack.clear()
        stack.add(screen)
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    val canGoBack: Boolean get() = stack.size > 1

    fun open(item: LibraryItem) = open(item.kind, item.id)

    fun open(kind: MediaKind, id: Long) = go(if (kind == MediaKind.MOVIE) Screen.MovieDetail(id) else Screen.SeriesDetail(id))

    fun say(text: String) {
        notice = text
    }

    // region item actions (context menus, detail pages)

    private val snapshot: LibrarySnapshot get() = services.store.snapshot.value

    /** "Lire": the film's best version from its resume point, or the series' next episode. */
    fun play(kind: MediaKind, id: Long, fromStart: Boolean? = null) {
        val s = snapshot
        when (kind) {
            MediaKind.MOVIE -> {
                val entry = s.movies[id] ?: return
                val file = entry.files.firstOrNull { it.file.isAvailable && it.inProgress } ?: entry.files.firstOrNull { it.file.isAvailable }
                if (file == null) return say("Aucun fichier disponible : le disque est-il branché ?")
                services.playback.play(file.file.id, fromStart = fromStart)
            }
            MediaKind.SERIES -> {
                val entry = s.series[id] ?: return
                val target = seriesPlayTarget(entry) ?: return say("Aucun épisode disponible.")
                playEpisode(id, target.episode.episode.id, fromStart)
            }
        }
    }

    /** Plays one episode, the rest of the series following in the queue. */
    fun playEpisode(seriesId: Long, episodeId: Long, fromStart: Boolean? = null) {
        val entry = snapshot.series[seriesId] ?: return
        val playable = entry.owned.filter { it.available }
        val ep = playable.firstOrNull { it.episode.id == episodeId } ?: return say("Épisode indisponible.")
        val fileId = ep.bestFile?.file?.id ?: return
        val queue = playable.filter { it.episode.seasonNumber != 0 || ep.episode.seasonNumber == 0 }.mapNotNull { it.bestFile?.file?.id }
        services.playback.play(fileId, if (fileId in queue) queue else listOf(fileId), fromStart, label = entry.series.title)
    }

    fun playFile(mediaFileId: Long, fromStart: Boolean? = null) = services.playback.play(mediaFileId, fromStart = fromStart)

    fun toggleFavorite(kind: MediaKind, id: Long) {
        val s = snapshot
        val fav = s.favoritesId ?: return
        val now = s.isFavorite(kind, id)
        background {
            services.store.setMember(fav, id.takeIf { kind == MediaKind.MOVIE }, id.takeIf { kind == MediaKind.SERIES }, !now)
        }
        say(if (now) "Retiré des favoris" else "Ajouté aux favoris")
    }

    fun setWatched(kind: MediaKind, id: Long, watched: Boolean) {
        val s = snapshot
        val files = when (kind) {
            MediaKind.MOVIE -> s.movies[id]?.files.orEmpty()
            MediaKind.SERIES -> s.series[id]?.files.orEmpty()
        }.map { it.file.id }
        background { services.store.setWatched(files, watched); services.store.refreshNow() }
        say(if (watched) "Marqué comme vu" else "Marqué comme non vu")
    }

    fun setEpisodesWatched(fileIds: List<Long>, watched: Boolean) {
        background { services.store.setWatched(fileIds, watched); services.store.refreshNow() }
    }

    fun castItem(kind: MediaKind, id: Long) {
        val s = snapshot
        val (fileId, title) = when (kind) {
            MediaKind.MOVIE -> s.movies[id]?.let { e -> (e.files.firstOrNull { it.file.isAvailable }?.file?.id) to e.movie.title } ?: (null to "")
            MediaKind.SERIES -> s.series[id]?.let { e -> seriesPlayTarget(e)?.episode?.bestFile?.file?.id to e.series.title } ?: (null to "")
        }
        if (fileId == null) return say("Aucun fichier disponible.")
        dialog = AppDialog.CastTo(fileId, title)
    }

    fun background(block: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) {
            runCatching { block() }.onFailure { e -> launch(Dispatchers.Main) { say("Erreur : ${e.message}") } }
        }
    }

    // endregion
}

val LocalApp = staticCompositionLocalOf<AppController> { error("no app") }

@Composable
fun app(): AppController = LocalApp.current
