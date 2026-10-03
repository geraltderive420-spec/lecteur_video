package com.lecteur.tv.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

private const val SEEK_STEP_MS = 10_000L
private const val OVERLAY_MS = 4_000L

/**
 * Full-screen playback on the TV. The remote's keys drive it directly (OK = play/pause, left/right = ±10 s, back =
 * leave); a phone connected in companion mode drives the same engine through the receiver host. A thin overlay with
 * the title and the position appears on any key and fades after a few seconds.
 */
@UnstableApi
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvPlayerScreen(viewModel: TvReceiverViewModel) {
    val engine by viewModel.engine.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val player = engine ?: return
    val state by player.state.collectAsStateWithLifecycle()
    val progress by player.progress.collectAsStateWithLifecycle()

    var overlayUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val focus = remember { FocusRequester() }

    BackHandler { viewModel.closePlayback() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(overlayUntil) {
        while (System.currentTimeMillis() < overlayUntil) {
            now = System.currentTimeMillis()
            delay(250)
        }
        now = System.currentTimeMillis()
    }
    // Paused or buffering: the overlay stays, so the screen never looks frozen without explanation.
    val showOverlay = now < overlayUntil || !state.isPlaying

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                overlayUntil = System.currentTimeMillis() + OVERLAY_MS
                when (event.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        if (state.isPlaying) player.pause() else player.play()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { player.seekBy(SEEK_STEP_MS); true }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> { player.seekBy(-SEEK_STEP_MS); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_STOP -> { viewModel.closePlayback(); true }
                    else -> false
                }
            }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                    this.player = player.player
                }
            },
            update = { view -> if (view.player !== player.player) view.player = player.player }
        )

        if (showOverlay) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .padding(horizontal = 48.dp, vertical = 24.dp)
            ) {
                Text(ui.title.orEmpty(), style = MaterialTheme.typography.titleLarge, color = Color.White)
                val fraction = if (progress.durationMs > 0) (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                Text(
                    "${clock(progress.positionMs)} / ${clock(progress.durationMs)}" + if (!state.isPlaying) "   ⏸" else "",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White
                )
                state.error?.let {
                    Text(it.message, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodyLarge)
                    it.suggestion?.let { s -> Text(s, color = Color.White, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}

private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
