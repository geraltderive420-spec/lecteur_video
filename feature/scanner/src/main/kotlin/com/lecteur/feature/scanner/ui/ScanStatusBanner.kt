package com.lecteur.feature.scanner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lecteur.feature.scanner.work.JobText
import com.lecteur.feature.scanner.work.LibraryJobState
import com.lecteur.feature.scanner.work.ScanScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ScanStatusViewModel @Inject constructor(private val scheduler: ScanScheduler) : ViewModel() {

    val job: StateFlow<LibraryJobState> = scheduler.jobState.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryJobState())

    fun cancel() = scheduler.cancelAll()
}

/**
 * A strip under the top bar of the home screen while the library is being analysed or identified: what is going on, how far,
 * a way to stop it, and a tap that opens the folders screen. Takes no room when nothing is running.
 */
@Composable
fun ScanStatusBanner(onClick: () -> Unit, modifier: Modifier = Modifier, viewModel: ScanStatusViewModel = hiltViewModel()) {
    val job by viewModel.job.collectAsStateWithLifecycle()
    if (!job.isBusy) return

    val scanning = job.scanRunning || job.scanQueued
    val title = if (scanning) JobText.scanTitle(job.scanProgress) else "Recherche des informations"
    val detail = when {
        job.scanQueued && !job.scanRunning -> "En attente…"
        job.scanRunning -> JobText.scanDetail(job.scanProgress)
        job.identifyQueued && !job.identifyRunning -> "En attente d'une connexion…"
        else -> JobText.identifyDetail(job.identifiedSoFar)
    }
    val fraction = if (job.scanRunning) JobText.fraction(job.scanProgress) else null

    Surface(modifier.fillMaxWidth().clickable(onClick = onClick), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1)
                }
                TextButton(onClick = viewModel::cancel) { Text("Annuler") }
            }
            if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(end = 12.dp, bottom = 4.dp))
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(end = 12.dp, bottom = 4.dp))
        }
    }
}
