package com.lecteur.audio.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lecteur.audio.BrowseViewModel
import com.lecteur.audio.Startup
import com.lecteur.core.data.shared.SERVER_ARG
import com.lecteur.core.model.LibrarySection
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.PlayPlan
import com.lecteur.feature.cast.ui.CastSheet
import com.lecteur.feature.cast.ui.MiniRemote
import com.lecteur.feature.cast.ui.RemoteScreen
import com.lecteur.feature.cast.ui.RemoteViewModel
import com.lecteur.feature.details.MovieDetailScreen
import com.lecteur.feature.details.SeriesDetailScreen
import com.lecteur.feature.home.HomeScreen
import com.lecteur.feature.home.WelcomeScreen
import com.lecteur.feature.library.FolderExplorerScreen
import com.lecteur.feature.library.LibraryHost
import com.lecteur.feature.library.ListsScreen
import com.lecteur.feature.library.SearchScreen
import com.lecteur.feature.scanner.ui.CorrectionScreen
import com.lecteur.feature.scanner.ui.FoldersScreen
import com.lecteur.feature.scanner.ui.ReviewScreen
import com.lecteur.feature.scanner.ui.ScanStatusBanner
import com.lecteur.feature.settings.SettingsScreen
import com.lecteur.feature.shared.ui.LibrarySourceTitle
import com.lecteur.feature.shared.ui.LibrarySourceViewModel
import com.lecteur.feature.shared.ui.SharedServersScreen

private class Tab(val route: String, val target: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Screen.Home.route, Screen.Home.route, "Accueil", Icons.Rounded.Home),
    Tab(Screen.Library.route, Screen.Library.create(LibrarySection.MOVIES), "Bibliothèque", Icons.Rounded.VideoLibrary),
    Tab(Screen.Explorer.route, Screen.Explorer.route, "Dossiers", Icons.Rounded.Folder),
    Tab(Screen.Settings.route, Screen.Settings.route, "Réglages", Icons.Rounded.Settings)
)

/** Below this width (dp) the tabs sit at the bottom of the screen; from here up, on the side (tablets, unfolded foldables, landscape). */
private const val RAIL_MIN_WIDTH_DP = 600

/**
 * The app's navigation: four tabs, and everything else (details, search, browse, folders, correction) opened above them.
 * Every playback goes through [onPlay], which starts the player: no screen here knows the player.
 *
 * The home, library and search show the library picked from the home's title: this phone's (the default) or a paired
 * PC's. Both use the same screens; the pages of a PC's titles carry its id, so a page left open keeps its library.
 */
@Composable
fun LecteurRoot(startup: Startup, onPlay: (PlayPlan) -> Unit, onWelcomeDone: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val showTabs = entry?.destination?.route in Screen.topLevel
    val wide = LocalConfiguration.current.screenWidthDp >= RAIL_MIN_WIDTH_DP

    // The guided first launch opens above the home screen, so leaving it lands on a screen that explains itself
    LaunchedEffect(startup) {
        if (startup == Startup.WELCOME) nav.navigate(Screen.Welcome.route) { launchSingleTop = true }
    }

    // The player's cast button asks for the remote from another activity
    val remoteSession = hiltViewModel<RemoteViewModel>().session
    LaunchedEffect(Unit) {
        remoteSession.openRemoteRequests.collect { nav.navigate(Screen.Remote.route) { launchSingleTop = true } }
    }
    val onRemote = entry?.destination?.route == Screen.Remote.route

    Row(Modifier.fillMaxSize()) {
        if (showTabs && wide) SideTabs(nav, entry)
        Scaffold(
            modifier = Modifier.weight(1f),
            bottomBar = {
                Column {
                    if (!onRemote) MiniRemote(onOpen = { nav.navigate(Screen.Remote.route) { launchSingleTop = true } })
                    if (showTabs && !wide) BottomTabs(nav, entry)
                }
            }
        ) { padding ->
            Destinations(nav, onPlay, onWelcomeDone, Modifier.padding(padding))
        }
    }
}

@Composable
private fun BottomTabs(nav: NavHostController, entry: NavBackStackEntry?) {
    NavigationBar {
        TABS.forEach { tab ->
            NavigationBarItem(
                selected = entry.isIn(tab),
                onClick = { nav.navigateTab(tab.target) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label) }
            )
        }
    }
}

@Composable
private fun SideTabs(nav: NavHostController, entry: NavBackStackEntry?) {
    NavigationRail {
        TABS.forEach { tab ->
            NavigationRailItem(
                selected = entry.isIn(tab),
                onClick = { nav.navigateTab(tab.target) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label) }
            )
        }
    }
}

private fun NavBackStackEntry?.isIn(tab: Tab) = this?.destination?.hierarchy?.any { it.route == tab.route } == true

/** Switching tabs keeps each tab's own history and scroll position instead of piling screens up. */
private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun Destinations(nav: NavHostController, onPlay: (PlayPlan) -> Unit, onWelcomeDone: () -> Unit, modifier: Modifier) {
    // The library on display: null for this phone's, else the PC's id
    val sources: LibrarySourceViewModel = hiltViewModel()
    val source by sources.active.collectAsStateWithLifecycle()

    // Pages of a title open in the library the title comes from
    val openMovieOf: (String?) -> (Long) -> Unit = { server -> { nav.navigate(Screen.Movie.create(it, server)) } }
    val openSeriesOf: (String?) -> (Long) -> Unit = { server -> { nav.navigate(Screen.Series.create(it, server)) } }
    val browseOf: (String?) -> (LibrarySection, BrowseKind, Long) -> Unit = { server ->
        { section, kind, id -> nav.navigate(Screen.Browse.create(section, kind, id, server)) }
    }
    val openMovie = openMovieOf(source)
    val openSeries = openSeriesOf(source)
    val openSearch: () -> Unit = { nav.navigate(Screen.Search.route) { launchSingleTop = true } }
    val openServers: () -> Unit = { nav.navigate(Screen.Shared.route) { launchSingleTop = true } }
    val title: @Composable () -> Unit = { LibrarySourceTitle(onManage = openServers) }
    val back: () -> Unit = { nav.popBackStack() }

    // The cast picker belongs to no single screen: any detail page opens it for a file, the remote takes over once a TV plays.
    var castFileId by remember { mutableStateOf<Long?>(null) }
    castFileId?.let { fileId ->
        CastSheet(
            mediaFileId = fileId,
            onDismiss = { castFileId = null },
            onOpenRemote = {
                castFileId = null
                nav.navigate(Screen.Remote.route) { launchSingleTop = true }
            }
        )
    }

    NavHost(navController = nav, startDestination = Screen.Home.route, modifier = modifier) {

        composable(Screen.Home.route) {
            HomeScreen(
                onOpenMovie = openMovie,
                onOpenSeries = openSeries,
                onSeeAll = { section -> nav.navigateTab(Screen.Library.create(section)) },
                onPlay = onPlay,
                onOpenSearch = openSearch,
                onAddFolder = { nav.navigate(Screen.Folders.route) },
                serverId = source,
                title = title,
                banner = { ScanStatusBanner(onClick = { nav.navigate(Screen.Folders.route) }) }
            )
        }

        composable(Screen.Library.route, arguments = listOf(navArgument("section") { type = NavType.StringType })) { entry ->
            val section = runCatching { LibrarySection.valueOf(entry.arguments?.getString("section").orEmpty()) }.getOrDefault(LibrarySection.MOVIES)
            LibraryHost(
                initialSection = section, onOpenMovie = openMovie, onOpenSeries = openSeries, onOpenSearch = openSearch, onPlay = onPlay,
                onOpenLists = { nav.navigate(Screen.Lists.route) { launchSingleTop = true } },
                serverId = source,
                title = title
            )
        }

        composable(Screen.Explorer.route) {
            FolderExplorerScreen(onPlay = onPlay, onManageFolders = { nav.navigate(Screen.Folders.route) })
        }

        composable(Screen.Settings.route) {
            SettingsScreen(onOpenFolders = { nav.navigate(Screen.Folders.route) }, onOpenReview = { nav.navigate(Screen.Review.route) })
        }

        composable(Screen.Search.route) {
            SearchScreen(
                onBack = back,
                onOpenMovie = openMovie,
                onOpenSeries = openSeries,
                onOpenPerson = { id, _ -> browseOf(source)(LibrarySection.MOVIES, BrowseKind.PERSON, id) },
                onPlay = onPlay,
                serverId = source
            )
        }

        val serverArg = navArgument(SERVER_ARG) { type = NavType.StringType; nullable = true; defaultValue = null }

        composable(Screen.Movie.route, arguments = listOf(navArgument("movieId") { type = NavType.LongType }, serverArg)) { entry ->
            val server = entry.arguments?.getString(SERVER_ARG)
            val browse = browseOf(server)
            MovieDetailScreen(
                onBack = back,
                onPlay = onPlay,
                onOpenPerson = { id, _ -> browse(LibrarySection.MOVIES, BrowseKind.PERSON, id) },
                onOpenGenre = { id, _ -> browse(LibrarySection.MOVIES, BrowseKind.GENRE, id) },
                onOpenCollection = { id, _ -> browse(LibrarySection.MOVIES, BrowseKind.COLLECTION, id) },
                onOpenMovie = openMovieOf(server),
                onOpenYear = { decade -> browse(LibrarySection.MOVIES, BrowseKind.DECADE, decade.toLong()) },
                onCorrect = { kind, id -> nav.navigate(Screen.Correct.create(kind, id)) },
                // A PC's film plays where the remote is linked to by itself (see MainActivity.play); the picker lists this phone's files
                onCast = if (server == null) ({ castFileId = it }) else null
            )
        }

        composable(Screen.Series.route, arguments = listOf(navArgument("seriesId") { type = NavType.LongType }, serverArg)) { entry ->
            val server = entry.arguments?.getString(SERVER_ARG)
            val browse = browseOf(server)
            SeriesDetailScreen(
                onBack = back,
                onPlay = onPlay,
                onOpenPerson = { id, _ -> browse(LibrarySection.SERIES, BrowseKind.PERSON, id) },
                onOpenGenre = { id, _ -> browse(LibrarySection.SERIES, BrowseKind.GENRE, id) },
                onOpenYear = { decade -> browse(LibrarySection.SERIES, BrowseKind.DECADE, decade.toLong()) },
                onCorrect = { kind, id -> nav.navigate(Screen.Correct.create(kind, id)) },
                onCast = if (server == null) ({ castFileId = it }) else null
            )
        }

        composable(
            Screen.Browse.route,
            arguments = listOf(
                navArgument("section") { type = NavType.StringType },
                navArgument("kind") { type = NavType.StringType },
                navArgument("id") { type = NavType.LongType },
                serverArg
            )
        ) {
            val viewModel: BrowseViewModel = hiltViewModel()
            val label by viewModel.label.collectAsStateWithLifecycle(initialValue = null)
            LibraryHost(
                initialSection = viewModel.section,
                scope = viewModel.scope,
                scopeLabel = label ?: "Bibliothèque",
                onBack = back,
                onOpenMovie = openMovieOf(viewModel.serverId),
                onOpenSeries = openSeriesOf(viewModel.serverId),
                onOpenSearch = openSearch,
                onPlay = onPlay,
                serverId = viewModel.serverId
            )
        }

        composable(Screen.Folders.route) {
            FoldersScreen(onBack = back, onOpenReview = { nav.navigate(Screen.Review.route) })
        }

        composable(Screen.Review.route) { ReviewScreen(onBack = back) }

        composable(
            Screen.Correct.route,
            arguments = listOf(navArgument("kind") { type = NavType.StringType }, navArgument("id") { type = NavType.LongType })
        ) { entry ->
            val kind = runCatching { MediaKind.valueOf(entry.arguments?.getString("kind").orEmpty()) }.getOrDefault(MediaKind.MOVIE)
            CorrectionScreen(kind = kind, id = entry.arguments?.getLong("id") ?: 0L, onDone = back)
        }

        composable(Screen.Remote.route) { RemoteScreen(onBack = back) }

        composable(Screen.Shared.route) {
            // Opening a PC (or pairing a new one) shows its library on the home screen
            SharedServersScreen(
                onOpenServer = {
                    sources.select(it)
                    nav.navigateTab(Screen.Home.route)
                },
                onBack = back
            )
        }

        composable(Screen.Lists.route) {
            ListsScreen(onBack = back, onOpenList = { id, _ -> browseOf(null)(LibrarySection.MOVIES, BrowseKind.LIST, id) })
        }

        composable(Screen.Welcome.route) {
            WelcomeScreen(
                onAddFolder = {
                    onWelcomeDone()
                    nav.navigate(Screen.Folders.route) { popUpTo(Screen.Welcome.route) { inclusive = true } }
                },
                onSkip = {
                    onWelcomeDone()
                    nav.popBackStack()
                }
            )
        }
    }
}
