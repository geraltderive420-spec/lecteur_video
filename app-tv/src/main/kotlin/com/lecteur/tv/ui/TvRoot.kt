package com.lecteur.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import com.lecteur.core.model.MediaKind
import com.lecteur.feature.scanner.ui.FoldersScreen
import com.lecteur.feature.scanner.ui.ReviewScreen
import com.lecteur.feature.settings.SettingsScreen
import com.lecteur.tv.TvLibraryViewModel
import com.lecteur.tv.TvPage
import kotlinx.coroutines.delay

private val TABS = listOf("Accueil", "Films", "Séries", "Recherche", "Serveurs", "Dossiers", "Réglages")
private const val TAB_SERVERS = 4
private const val TAB_FOLDERS = 5

/**
 * The TV app: a tab row (focus moves selection), the merged library under it (this TV's and the paired PCs'), pages
 * opened above it (a title, a person), and the player taking over the whole screen while something plays.
 */
@UnstableApi
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun TvRoot(viewModel: TvLibraryViewModel = hiltViewModel(), receiverViewModel: TvReceiverViewModel = hiltViewModel()) {
    val receiver by receiverViewModel.ui.collectAsStateWithLifecycle()
    val pages by viewModel.pages.collectAsStateWithLifecycle()
    val tab by viewModel.tab.collectAsStateWithLifecycle()

    // Back on the TV after a while (or after watching on the phone): the PCs' progress may have moved
    LifecycleResumeEffect(Unit) {
        viewModel.refreshServers()
        onPauseOrDispose { }
    }

    if (receiver.playbackActive) {
        TvPlayerScreen(receiverViewModel)
        return
    }

    val memory = remember { TvFocusMemory() }
    // The card each open page was opened from, to give it the focus back when the page closes
    val openedFrom = remember { mutableStateListOf<String?>() }
    var restore by remember { mutableIntStateOf(0) }
    var showReview by rememberSaveable { mutableStateOf(false) }

    val open: (TvPage) -> Unit = { page ->
        openedFrom.add(memory.lastKey)
        viewModel.open(page)
    }
    val back: () -> Unit = {
        viewModel.back()
        memory.lastKey = openedFrom.removeLastOrNull() ?: memory.lastKey
        restore++
    }
    LaunchedEffect(restore) {
        if (restore == 0) return@LaunchedEffect
        withFrameNanos { }
        memory.restore()
    }

    BackHandler(enabled = pages.isNotEmpty() || tab != 0 || showReview) {
        when {
            pages.isNotEmpty() -> back()
            showReview -> showReview = false
            else -> viewModel.selectTab(0)
        }
    }

    CompositionLocalProvider(LocalTvFocus provides memory) {
        Box(Modifier.fillMaxSize().background(BACKGROUND)) {
            // While a page is open, the tabs underneath cannot take the focus (their focused tab would open)
            Column(
                Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)
                    .focusProperties { enter = { if (pages.isNotEmpty()) FocusRequester.Cancel else FocusRequester.Default } }
                    .focusGroup()
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    TabRow(selectedTabIndex = tab, modifier = Modifier.weight(1f)) {
                        TABS.forEachIndexed { index, label ->
                            Tab(selected = index == tab, onFocus = { if (pages.isEmpty()) viewModel.selectTab(index) }, onClick = { viewModel.selectTab(index) }) {
                                Text(label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                            }
                        }
                    }
                    CompanionBadge(receiver)
                }

                Box(Modifier.fillMaxSize().padding(top = 16.dp)) {
                    when {
                        showReview -> ReviewScreen(onBack = { showReview = false })
                        tab == 0 -> TvHomeScreen(viewModel, onOpen = open)
                        tab == 1 -> TvGridScreen(viewModel, MediaKind.MOVIE, onOpen = open)
                        tab == 2 -> TvGridScreen(viewModel, MediaKind.SERIES, onOpen = open)
                        tab == 3 -> TvSearchScreen(viewModel, onOpen = open)
                        tab == TAB_SERVERS -> TvServersScreen(onPaired = { viewModel.refreshServers(); viewModel.selectTab(0) })
                        tab == TAB_FOLDERS -> FoldersScreen(onBack = { viewModel.selectTab(0) }, onOpenReview = { showReview = true })
                        else -> SettingsScreen(onOpenFolders = { viewModel.selectTab(TAB_FOLDERS) }, onOpenReview = { showReview = true })
                    }
                }
            }

            pages.lastOrNull()?.let { page ->
                Box(Modifier.fillMaxSize().background(BACKGROUND).padding(horizontal = 48.dp, vertical = 24.dp)) {
                    when (page) {
                        is TvPage.Detail -> TvDetailScreen(viewModel, page.ref, onOpen = open)
                        is TvPage.Person -> TvPersonScreen(viewModel, page, onOpen = open)
                    }
                }
            }

            Notice(viewModel, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Short messages (a PC that does not answer, a file on an unplugged disk), gone after a few seconds. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Notice(viewModel: TvLibraryViewModel, modifier: Modifier) {
    val text by viewModel.notice.collectAsStateWithLifecycle()
    val shown = text ?: return
    LaunchedEffect(shown) {
        delay(4_000)
        viewModel.consumeNotice()
    }
    Box(modifier.padding(bottom = 32.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2B2B31)).padding(horizontal = 24.dp, vertical = 14.dp)) {
        Text(shown, style = MaterialTheme.typography.bodyLarge)
    }
}

/** What a phone needs to connect: this TV's name and the code to type. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CompanionBadge(ui: com.lecteur.feature.cast.receiver.ReceiverUi) {
    if (!ui.running) return
    Column(horizontalAlignment = Alignment.End) {
        if (ui.controllerName != null) {
            Text("Connecté à ${ui.controllerName}", style = MaterialTheme.typography.labelLarge)
        } else {
            Text("Diffusion depuis le téléphone", style = MaterialTheme.typography.labelMedium)
            Text("${ui.deviceName} · code ${ui.pairingCode.chunked(3).joinToString(" ")}", style = MaterialTheme.typography.titleMedium)
        }
    }
}
