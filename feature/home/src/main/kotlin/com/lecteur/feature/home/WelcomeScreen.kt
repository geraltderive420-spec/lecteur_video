package com.lecteur.feature.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * First launch, in two steps that each explain what they are for: the notification permission (progress of the analysis
 * while the app is in the background), then the first folder. Both can be skipped; neither comes back.
 */
@Composable
fun WelcomeScreen(onAddFolder: () -> Unit, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    var notificationsGranted by remember {
        mutableStateOf(
            !needsNotificationPermission ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        )
    }
    var notificationsAsked by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsGranted = it
        notificationsAsked = true
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)
    ) {
        Icon(Icons.Rounded.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
        Text("Bienvenue", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Votre médiathèque reste sur votre appareil : aucun serveur, aucun compte. Deux réglages et c'est prêt.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Step(
            icon = if (notificationsGranted && needsNotificationPermission) Icons.Rounded.CheckCircle else Icons.Rounded.Notifications,
            title = "Suivre l'analyse",
            text = when {
                !needsNotificationPermission -> "L'analyse de vos dossiers s'affiche dans une notification pendant qu'elle travaille."
                notificationsGranted -> "Autorisé : la progression de l'analyse s'affichera dans une notification."
                notificationsAsked -> "Refusé : l'analyse se fera quand même, sans notification. Vous pouvez l'autoriser plus tard dans les réglages Android."
                else -> "Une notification montre la progression pendant que l'analyse travaille en arrière-plan."
            }
        ) {
            if (needsNotificationPermission && !notificationsGranted && !notificationsAsked) {
                OutlinedButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Autoriser les notifications") }
            }
        }

        Step(
            icon = Icons.Rounded.CreateNewFolder,
            title = "Ajouter votre premier dossier",
            text = "Choisissez le dossier de vos films ou de vos séries, sur le stockage interne, la carte SD ou une clé USB. Les fichiers ne sont jamais modifiés ni copiés."
        ) {
            Button(onClick = onAddFolder) { Text("Ajouter un dossier") }
        }

        TextButton(onClick = onSkip) { Text("Plus tard") }
    }
}

@Composable
private fun Step(icon: ImageVector, title: String, text: String, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action()
        }
    }
}
