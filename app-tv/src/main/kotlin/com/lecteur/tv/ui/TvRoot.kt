package com.lecteur.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import com.lecteur.feature.scanner.ui.FoldersScreen
import com.lecteur.feature.scanner.ui.ReviewScreen
import com.lecteur.tv.TvViewModel

private val TABS = listOf("Accueil", "Films", "Séries", "Dossiers")

/**
 * The TV app: a tab row (focus moves selection), the library under it, and the player taking over the whole
 * screen while something plays, whether started from here or by a phone.
 */
@UnstableApi
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvRoot(viewModel: TvViewModel = hiltViewModel(), receiverViewModel: TvReceiverViewModel = hiltViewModel()) {
    val receiver by receiverViewModel.ui.collectAsStateWithLifecycle()

    if (receiver.playbackActive) {
        TvPlayerScreen(receiverViewModel)
        return
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showReview by rememberSaveable { mutableStateOf(false) }
    // Back from a sub-tab returns to the home tab before it leaves the app.
    BackHandler(enabled = tab != 0 || showReview) {
        if (showReview) showReview = false else tab = 0
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF0F0F12)).padding(horizontal = 48.dp, vertical = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TabRow(selectedTabIndex = tab, modifier = Modifier.weight(1f)) {
                TABS.forEachIndexed { index, label ->
                    Tab(selected = index == tab, onFocus = { tab = index }, onClick = { tab = index }) {
                        Text(label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    }
                }
            }
            CompanionBadge(receiver)
        }

        Box(Modifier.fillMaxSize().padding(top = 16.dp)) {
            when {
                showReview -> ReviewScreen(onBack = { showReview = false })
                tab == 0 -> TvHome(viewModel)
                tab == 1 -> TvGrid(viewModel.movies, emptyText = "Aucun film dans la bibliothèque de cette TV.", onPlay = viewModel::playItem)
                tab == 2 -> TvGrid(viewModel.series, emptyText = "Aucune série dans la bibliothèque de cette TV.", onPlay = viewModel::playItem)
                else -> FoldersScreen(onBack = { tab = 0 }, onOpenReview = { showReview = true })
            }
        }
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
