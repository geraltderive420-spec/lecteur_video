package com.lecteur.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.rememberTrayState
import com.lecteur.desktop.background.Autostart

/**
 * The icon of the notification area, Jellyfin tray style: always there while the app runs, window or not, it shows
 * whether the library is served and gives the server's controls without opening the library.
 */
@Composable
fun ApplicationScope.AppTray(app: AppController, icon: Painter?) {
    val services = app.services
    val settings by services.settings.state.collectAsState()
    val share by services.share.ui.collectAsState()
    val receiver by services.receiver.ui.collectAsState()
    val tray = rememberTrayState()

    // The first time the window goes away, say where the app went
    LaunchedEffect(app.mainWindowOpen) {
        if (!app.mainWindowOpen && !settings.trayHintShown) {
            tray.sendNotification(Notification("Lecteur Média reste actif", "Le partage et la réception continuent en arrière-plan. Clic droit sur l'icône pour quitter."))
            services.settings.update { it.copy(trayHintShown = true) }
        }
    }

    val status = when {
        share.running -> "Bibliothèque partagée · ${settings.sharedDevices.size} appareil(s)"
        settings.shareEnabled -> share.error ?: "Partage en cours de démarrage"
        else -> "Partage désactivé"
    }

    Tray(
        icon = icon ?: ColorPainter(Color(0xFF7C5CFF)),
        state = tray,
        tooltip = "Lecteur Média — $status",
        onAction = app::showMainWindow,
        menu = {
            Item("Ouvrir Lecteur Média", onClick = app::showMainWindow)
            Separator()
            Item(status, enabled = false, onClick = {})
            if (receiver.controllerName != null) Item("Piloté par ${receiver.controllerName}", enabled = false, onClick = {})
            Item(if (settings.shareEnabled) "Arrêter le partage" else "Démarrer le partage", onClick = {
                val on = !settings.shareEnabled
                services.settings.update { it.copy(shareEnabled = on) }
                if (on) services.share.start() else services.share.stop()
            })
            Item("Associer un appareil…", enabled = share.running, onClick = {
                app.showMainWindow()
                app.root(Screen.Share)
                services.share.openPairing()
            })
            Separator()
            CheckboxItem("Démarrer avec Windows", checked = app.startWithWindows, enabled = Autostart.available, onCheckedChange = app::changeStartWithWindows)
            Separator()
            Item("Quitter", onClick = ::exitApplication)
        }
    )
}
