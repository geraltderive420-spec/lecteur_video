package com.lecteur.tv

import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.media3.common.util.UnstableApi
import com.lecteur.core.designsystem.theme.LecteurTheme
import com.lecteur.feature.cast.receiver.ReceiverHost
import com.lecteur.tv.ui.TvRoot
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class TvMainActivity : ComponentActivity() {

    @Inject lateinit var receiver: ReceiverHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LecteurTheme(darkTheme = true, dynamicColor = false) {
                TvRoot()
            }
        }
    }

    /** The TV only listens for a phone while the app is on screen: a TV that vanished from the list is better than one that answers into the void. */
    override fun onStart() {
        super.onStart()
        receiver.start(deviceName())
    }

    override fun onStop() {
        if (!receiver.ui.value.playbackActive) receiver.stop()
        super.onStop()
    }

    private fun deviceName(): String =
        Settings.Global.getString(contentResolver, "device_name")?.takeIf { it.isNotBlank() }
            ?: Build.MODEL?.takeIf { it.isNotBlank() }
            ?: "Android TV"
}
