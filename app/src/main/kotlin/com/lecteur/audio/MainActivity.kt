package com.lecteur.audio

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.lecteur.audio.navigation.LecteurRoot
import com.lecteur.core.common.library.LibraryUrls
import com.lecteur.core.data.playback.PlaybackQueueStore
import com.lecteur.core.designsystem.theme.LecteurTheme
import com.lecteur.core.model.PlayPlan
import com.lecteur.feature.player.PlayerActivity
import com.lecteur.feature.cast.controller.RemoteSession
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var queueStore: PlaybackQueueStore
    @Inject lateinit var remoteSession: RemoteSession

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val appearance by viewModel.appearance.collectAsStateWithLifecycle()
            val startup by viewModel.startup.collectAsStateWithLifecycle()
            LecteurTheme(appearance) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    // Blank for the few milliseconds the first launch takes to be recognised, rather than flashing the wrong screen
                    startup?.let { LecteurRoot(startup = it, onPlay = ::play, onWelcomeDone = viewModel::markWelcomeDone) }
                }
            }
        }
    }

    /** The player takes the plan from the store (a long folder would overflow an Intent) and starts with its first file. */
    private fun play(plan: PlayPlan) {
        // Linked to a TV: everything plays there, never twice
        val entry = plan.entries[plan.startIndex]
        val fileId = entry.mediaFileId
        val fromPc = LibraryUrls.parseStream(entry.uri) != null
        if ((fileId != null || fromPc) && remoteSession.isConnected) {
            lifecycleScope.launch {
                if (fileId != null) remoteSession.cast(fileId) else remoteSession.castUri(entry.uri)
                remoteSession.ui.value.notice?.let {
                    Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show()
                    remoteSession.consumeNotice()
                }
            }
            return
        }
        queueStore.publish(plan)
        startActivity(PlayerActivity.createQueueIntent(this, Uri.parse(plan.entries[plan.startIndex].uri)))
    }
}
