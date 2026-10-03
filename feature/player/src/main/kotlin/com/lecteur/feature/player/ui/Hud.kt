package com.lecteur.feature.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lecteur.core.player.gesture.SeekPreview
import com.lecteur.core.player.gesture.VolumeMath
import com.lecteur.feature.player.TimeFormat
import kotlinx.coroutines.delay

/** Transient on-screen feedback for gestures. Each indicator disappears by itself shortly after its last update. */
@Stable
class HudState {
    var brightness by mutableStateOf<Float?>(null)
        private set
    var volume by mutableStateOf<Float?>(null)
        private set
    var seek by mutableStateOf<SeekPreview?>(null)
        private set
    var skipMs by mutableStateOf<Long?>(null)
        private set
    var speed by mutableStateOf<Float?>(null)
        private set
    var zoomPercent by mutableStateOf<Int?>(null)
        private set

    // Bumped on every update so the auto-hide timer restarts
    internal var stamp by mutableStateOf(0L)
        private set

    fun showBrightness(level: Float) { brightness = level; touch() }
    fun showVolume(level: Float) { volume = level; touch() }
    fun showSeek(preview: SeekPreview) { seek = preview; touch() }
    fun showSkip(totalMs: Long) { skipMs = totalMs; touch() }
    fun showZoom(scale: Float) { zoomPercent = (scale * 100).toInt(); touch() }

    /** Held while the finger stays down (long press): no auto-hide. */
    fun holdSpeed(value: Float?) { speed = value }

    fun hideSeek() { seek = null }

    internal fun clearTransient() {
        brightness = null
        volume = null
        skipMs = null
        zoomPercent = null
    }

    private fun touch() { stamp++ }
}

@Composable
fun HudOverlay(hud: HudState, volumeBoostEnabled: Boolean, modifier: Modifier = Modifier) {
    LaunchedEffect(hud.stamp) {
        if (hud.stamp == 0L) return@LaunchedEffect
        delay(900)
        hud.clearTransient()
    }

    Box(modifier.fillMaxSize()) {
        hud.brightness?.let {
            LevelBar(
                label = "${(it * 100).toInt()} %",
                fraction = it,
                icon = { Icon(Icons.Rounded.BrightnessMedium, contentDescription = "Luminosité", tint = Color.White) },
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 40.dp)
            )
        }
        hud.volume?.let {
            val max = VolumeMath.maxLevel(volumeBoostEnabled)
            LevelBar(
                label = "${VolumeMath.percent(it)} %",
                fraction = it / max,
                icon = { Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = "Volume", tint = Color.White) },
                highlight = it > 1f,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 40.dp)
            )
        }
        hud.seek?.let {
            Pill(modifier = Modifier.align(Alignment.Center)) {
                Text(TimeFormat.clock(it.targetMs), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("(${TimeFormat.signed(it.deltaMs)})", color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp)
            }
        }
        hud.skipMs?.let {
            Pill(
                modifier = Modifier
                    .align(if (it < 0) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 72.dp)
            ) {
                Text(TimeFormat.skip(it), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
        hud.speed?.let {
            Pill(modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp)) {
                Text("${TimeFormat.speed(it)}  ▶▶", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        hud.zoomPercent?.let {
            Pill(modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp)) {
                Text("Zoom $it %", color = Color.White, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun Pill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) { content() }
}

@Composable
private fun LevelBar(
    label: String,
    fraction: Float,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    highlight: Boolean = false
) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        icon()
        Box(
            Modifier
                .width(6.dp)
                .height(120.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.25f)),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                Modifier
                    .fillMaxHeight(fraction.coerceIn(0f, 1f))
                    .width(6.dp)
                    .background(if (highlight) MaterialTheme.colorScheme.tertiary else Color.White)
            )
        }
        Text(label, color = Color.White, fontSize = 14.sp)
    }
}
