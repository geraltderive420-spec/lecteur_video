package com.lecteur.feature.scanner.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lecteur.core.model.MediaKind
import kotlinx.coroutines.delay

/**
 * The correction dialog opened from a detail page ("Corriger l'association"): the same search, TMDB id entry and lock as the
 * "to verify" list, on one title. Leaves by itself once the dialog is dismissed or the choice applied.
 */
@Composable
fun CorrectionScreen(kind: MediaKind, id: Long, onDone: () -> Unit, modifier: Modifier = Modifier, viewModel: ReviewViewModel = hiltViewModel()) {
    val correction by viewModel.correction.collectAsStateWithLifecycle()
    var wasOpen by remember { mutableStateOf(false) }

    LaunchedEffect(kind, id) { viewModel.openById(kind, id) }
    LaunchedEffect(correction) {
        if (correction != null) wasOpen = true else if (wasOpen) onDone()
    }
    // A title that no longer exists never opens the dialog: do not leave the user on a blank screen
    LaunchedEffect(Unit) {
        delay(MISSING_TITLE_GRACE_MS)
        if (!wasOpen) onDone()
    }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
    correction?.let { CorrectionDialog(it, viewModel) }
}

private const val MISSING_TITLE_GRACE_MS = 3_000L
