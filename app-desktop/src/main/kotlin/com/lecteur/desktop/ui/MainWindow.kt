package com.lecteur.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import com.lecteur.core.common.scan.VideoFiles
import com.lecteur.core.common.text.NaturalOrder
import com.lecteur.core.model.LibrarySection
import com.lecteur.desktop.ui.player.PlayerWindow
import kotlinx.coroutines.delay
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.awt.event.MouseEvent
import java.io.File

@Composable
fun ApplicationScope.LecteurApp(app: AppController, icon: Painter?) {
    val settings by app.services.settings.state.collectAsState()
    val session by app.services.playback.ui.collectAsState()
    val windowState = rememberWindowState(size = DpSize(1440.dp, 900.dp), placement = WindowPlacement.Maximized)

    AppTray(app, icon)
    if (session.active) PlayerWindow(app, icon)
    // Out of the composition while closed: a server in the notification area does not keep the library's UI in memory
    if (!app.mainWindowOpen) return

    Window(
        onCloseRequest = { if (settings.closeToTray) app.mainWindowOpen = false else exitApplication() },
        state = windowState,
        title = "Lecteur Média",
        icon = icon,
        onPreviewKeyEvent = { e ->
            if (e.type != KeyEventType.KeyDown) return@Window false
            when {
                e.isCtrlPressed && e.key == Key.F -> { app.root(Screen.Search); true }
                e.isCtrlPressed && e.key == Key.O -> { pickVideoFile()?.let { f -> app.services.playback.playPath(f, siblingsOf(f)) }; true }
                e.isAltPressed && e.key == Key.DirectionLeft -> { app.back(); true }
                e.key == Key.Escape && app.canGoBack && app.dialog == null -> { app.back(); true }
                else -> false
            }
        }
    ) {
        // Opened again from the tray or a second launch while already open: to the front
        LaunchedEffect(app.showRequests) {
            if (app.showRequests == 0) return@LaunchedEffect
            if (windowState.isMinimized) windowState.isMinimized = false
            window.toFront()
            window.requestFocus()
        }

        // Mouse "back" button and video files dropped on the window
        DisposableEffect(window) {
            val toolkit = java.awt.Toolkit.getDefaultToolkit()
            val listener = java.awt.event.AWTEventListener { e ->
                if (e is MouseEvent && e.id == MouseEvent.MOUSE_RELEASED && e.button == 4 && javax.swing.SwingUtilities.getWindowAncestor(e.component) === window) app.back()
            }
            toolkit.addAWTEventListener(listener, java.awt.AWTEvent.MOUSE_EVENT_MASK)
            window.dropTarget = DropTarget(window, object : DropTargetAdapter() {
                override fun drop(event: DropTargetDropEvent) {
                    event.acceptDrop(DnDConstants.ACTION_COPY)
                    @Suppress("UNCHECKED_CAST")
                    val files = runCatching { event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File> }.getOrNull().orEmpty()
                    val video = files.firstOrNull { it.isFile && VideoFiles.isVideo(it.name) }
                    val folder = files.firstOrNull { it.isDirectory }
                    when {
                        video != null -> app.services.playback.playPath(video, if (files.size > 1) files.filter { VideoFiles.isVideo(it.name) } else siblingsOf(video))
                        folder != null -> app.go(Screen.Explorer(folder.absolutePath))
                    }
                    event.dropComplete(true)
                }
            })
            onDispose { toolkit.removeAWTEventListener(listener) }
        }

        CompositionLocalProvider(LocalApp provides app) {
            LecteurTheme(settings.themeMode) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxSize()) {
                        SideBar()
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            AnimatedContent(app.current, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { screen ->
                                Box(Modifier.fillMaxSize()) { ScreenContent(screen) }
                            }
                            Notice(Modifier.align(Alignment.BottomCenter))
                        }
                    }
                    AppDialogs()
                }
            }
        }
    }
}

@Composable
private fun ScreenContent(screen: Screen) {
    when (screen) {
        Screen.Home -> HomeScreen()
        is Screen.Library -> LibraryScreen(screen)
        Screen.Search -> SearchScreen()
        Screen.Lists -> ListsScreen()
        is Screen.MovieDetail -> MovieDetailScreen(screen.id)
        is Screen.SeriesDetail -> SeriesDetailScreen(screen.id)
        is Screen.Person -> PersonScreen(screen.id)
        Screen.Folders -> FoldersScreen()
        Screen.Review -> ReviewScreen()
        is Screen.Explorer -> ExplorerScreen(screen.path)
        Screen.Cast -> CastScreen()
        Screen.Share -> ShareScreen()
        Screen.Settings -> SettingsScreen()
    }
}

@Composable
private fun SideBar() {
    val a = app()
    val receiver by a.services.receiver.ui.collectAsState()
    val remote by a.services.remote.ui.collectAsState()
    val scan by a.services.scanner.status.collectAsState()
    val root = a.stack.first()
    val castActive = receiver.controllerName != null || remote.connection is com.lecteur.desktop.cast.ConnectionState.Connected

    NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(92.dp)) {
        Spacer(Modifier.height(12.dp))
        Text("LM", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(18.dp))
        @Composable
        fun item(screen: Screen, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean = root == screen) {
            NavigationRailItem(selected = selected, onClick = { a.root(screen) }, icon = { Icon(icon, label) }, label = { Text(label) })
        }
        item(Screen.Home, "Accueil", Icons.Filled.Home)
        item(Screen.Library(LibrarySection.MOVIES), "Films", Icons.Filled.Movie, (root as? Screen.Library)?.section == LibrarySection.MOVIES)
        item(Screen.Library(LibrarySection.SERIES), "Séries", Icons.Filled.Tv, (root as? Screen.Library)?.section == LibrarySection.SERIES)
        item(Screen.Search, "Recherche", Icons.Filled.Search)
        item(Screen.Lists, "Listes", Icons.AutoMirrored.Outlined.List)
        item(Screen.Explorer(), "Fichiers", Icons.Filled.TravelExplore, root is Screen.Explorer)
        item(Screen.Cast, "Diffusion", if (castActive) Icons.Filled.CastConnected else Icons.Filled.Cast)
        item(Screen.Share, "Partage", Icons.Filled.Devices)
        Spacer(Modifier.weight(1f))
        if (scan.running) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.height(10.dp))
        }
        item(Screen.Folders, "Dossiers", Icons.Filled.Folder)
        item(Screen.Settings, "Réglages", Icons.Filled.Settings)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun Notice(modifier: Modifier) {
    val a = app()
    val text = a.notice ?: return
    LaunchedEffect(text) {
        delay(3_000)
        if (a.notice == text) a.notice = null
    }
    Snackbar(modifier.padding(24.dp), shape = RoundedCornerShape(10.dp)) { Text(text) }
}

fun siblingsOf(file: File): List<File> =
    file.parentFile?.listFiles()?.filter { it.isFile && VideoFiles.isVideo(it.name) }?.sortedWith(compareBy(NaturalOrder) { it.name }) ?: listOf(file)

