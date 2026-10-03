package com.lecteur.feature.player

import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Rational
import android.view.Display
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.lecteur.core.model.HdrType
import com.lecteur.core.player.gesture.VolumeMath
import com.lecteur.core.player.session.PlaybackServiceConnection
import com.lecteur.core.player.session.PlayerHolder
import com.lecteur.core.designsystem.theme.LecteurTheme
import com.lecteur.feature.player.ui.PlayerHostActions
import com.lecteur.feature.player.ui.PlayerScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Full-screen playback screen. Opened with a video URI as intent data, either by the app (file picker, library)
 * or by another app through ACTION_VIEW.
 */
@UnstableApi
@AndroidEntryPoint
class PlayerActivity : ComponentActivity() {

    @Inject
    lateinit var holder: PlayerHolder

    private val viewModel: PlayerViewModel by viewModels()
    private val serviceConnection by lazy { PlaybackServiceConnection(this) }
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private var isInPip by mutableStateOf(false)
    private var pipReceiver: BroadcastReceiver? = null

    private val subtitlePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onSubtitlePicked(uri)
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val canPip: Boolean
        get() = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setUpWindow()
        reportDisplayCapabilities()

        val host = PlayerHostActions(
            brightness = ::currentBrightness,
            setBrightness = ::applyBrightness,
            systemVolume = ::currentVolume,
            setSystemVolume = ::applyVolume,
            enterPictureInPicture = ::enterPip,
            canEnterPictureInPicture = canPip,
            pickSubtitleFile = { subtitlePicker.launch(arrayOf("*/*")) },
            toggleRotationLock = ::toggleRotationLock,
            close = ::finish
        )
        setContent {
            LecteurTheme(darkTheme = true, dynamicColor = false) {
                PlayerScreen(viewModel, host, isInPip)
            }
        }

        lifecycleScope.launch { viewModel.messages.collect { Toast.makeText(this@PlayerActivity, it, Toast.LENGTH_SHORT).show() } }
        lifecycleScope.launch {
            // Applies the saved rotation lock, then keeps the PiP window in sync with playback
            applyRotationLock(viewModel.settings.value.lockedRotation)
            val engine = viewModel.engine.filterNotNull().first()
            holder.sessionActivity = sessionPendingIntent()
            // The MediaSession service needs the engine to exist before a controller connects
            serviceConnection.connect()
            engine.state.collect { updatePipParams() }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val uri = intent.data ?: intent.getParcelableExtraCompat(Intent.EXTRA_STREAM)
        if (uri == null) {
            Toast.makeText(this, "Aucun fichier à lire.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        viewModel.open(uri.toString(), hasQueue = intent.getBooleanExtra(EXTRA_QUEUE, false))
    }

    // region lifecycle

    override fun onStart() {
        super.onStart()
        viewModel.engine.value?.setVideoEnabled(true)
        registerPipReceiver()
    }

    override fun onStop() {
        unregisterPipReceiver()
        if (!isChangingConfigurations) {
            viewModel.saveProgress()
            if (!isFinishing) {
                val engine = viewModel.engine.value
                if (viewModel.settings.value.backgroundAudio) engine?.setVideoEnabled(false) else engine?.pause()
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        serviceConnection.disconnect()
        if (isFinishing) viewModel.closePlayback()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters PiP by itself (auto-enter); older versions need the explicit call
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && viewModel.engine.value?.state?.value?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPip = isInPictureInPictureMode
    }

    // endregion

    // region window, brightness, volume, rotation

    private fun setUpWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Window brightness only: the system setting is left untouched. */
    private fun applyBrightness(level: Float) {
        window.attributes = window.attributes.apply { screenBrightness = level.coerceIn(0.01f, 1f) }
    }

    private fun currentBrightness(): Float {
        val window = window.attributes.screenBrightness
        if (window >= 0f) return window
        return runCatching { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
    }

    private fun currentVolume(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    private fun applyVolume(fraction: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, VolumeMath.systemIndex(fraction, max), 0)
    }

    private fun toggleRotationLock() {
        val lock = !viewModel.settings.value.lockedRotation
        viewModel.updateSettings { it.copy(lockedRotation = lock) }
        applyRotationLock(lock)
        Toast.makeText(this, if (lock) "Rotation verrouillée" else "Rotation automatique", Toast.LENGTH_SHORT).show()
    }

    private fun applyRotationLock(locked: Boolean) {
        requestedOrientation = if (locked) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    }

    @Suppress("DEPRECATION")
    private fun reportDisplayCapabilities() {
        val display: Display? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay
        val raw: IntArray = when {
            display == null -> intArrayOf()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> display.mode.supportedHdrTypes
            else -> display.hdrCapabilities?.supportedHdrTypes ?: intArrayOf()
        }
        viewModel.setDisplayHdrTypes(
            raw.toList().mapNotNull {
                when (it) {
                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> HdrType.DOLBY_VISION
                    Display.HdrCapabilities.HDR_TYPE_HDR10 -> HdrType.HDR10
                    Display.HdrCapabilities.HDR_TYPE_HLG -> HdrType.HLG
                    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> HdrType.HDR10_PLUS
                    else -> null
                }
            }.toSet()
        )
    }

    // endregion

    // region picture in picture

    private fun enterPip() {
        if (!canPip) return
        runCatching { enterPictureInPictureMode(buildPipParams()) }
    }

    private fun buildPipParams(): PictureInPictureParams {
        val engine = viewModel.engine.value
        val playing = engine?.state?.value?.isPlaying == true
        val size = engine?.state?.value?.videoSize
        val builder = PictureInPictureParams.Builder().setActions(
            listOf(
                pipAction(PIP_REWIND, android.R.drawable.ic_media_rew, "Reculer", 1),
                if (playing) pipAction(PIP_PLAY_PAUSE, android.R.drawable.ic_media_pause, "Pause", 2)
                else pipAction(PIP_PLAY_PAUSE, android.R.drawable.ic_media_play, "Lecture", 2),
                pipAction(PIP_FORWARD, android.R.drawable.ic_media_ff, "Avancer", 3)
            )
        )
        if (size != null && size.isKnown) {
            // Android rejects ratios outside 1:2.39 .. 2.39:1
            val ratio = size.displayAspect.coerceIn(0.42f, 2.39f)
            builder.setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(playing).setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun updatePipParams() {
        if (canPip) runCatching { setPictureInPictureParams(buildPipParams()) }
    }

    private fun pipAction(type: String, iconRes: Int, title: String, requestCode: Int): RemoteAction {
        val intent = Intent(PIP_ACTION).setPackage(packageName).putExtra(EXTRA_PIP_TYPE, type)
        val pending = PendingIntent.getBroadcast(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return RemoteAction(Icon.createWithResource(this, iconRes), title, title, pending)
    }

    private fun registerPipReceiver() {
        if (pipReceiver != null) return
        pipReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getStringExtra(EXTRA_PIP_TYPE)) {
                    PIP_PLAY_PAUSE -> viewModel.togglePlayPause()
                    PIP_REWIND -> viewModel.seekBy(-viewModel.settings.value.seekStepSeconds * 1000L)
                    PIP_FORWARD -> viewModel.seekBy(viewModel.settings.value.seekStepSeconds * 1000L)
                }
            }
        }
        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(PIP_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun unregisterPipReceiver() {
        pipReceiver?.let { unregisterReceiver(it) }
        pipReceiver = null
    }

    // endregion

    private fun onSubtitlePicked(uri: Uri) {
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && column >= 0) cursor.getString(column) else null
        } ?: uri.lastPathSegment.orEmpty()
        viewModel.addSubtitle(uri.toString(), name)
    }

    private fun sessionPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT).setData(intent.data),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    @Suppress("DEPRECATION")
    private fun Intent.getParcelableExtraCompat(name: String): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableExtra(name, Uri::class.java) else getParcelableExtra(name)

    companion object {
        private const val PIP_ACTION = "com.lecteur.feature.player.PIP_ACTION"
        private const val EXTRA_PIP_TYPE = "type"
        private const val EXTRA_QUEUE = "com.lecteur.feature.player.QUEUE"
        private const val PIP_PLAY_PAUSE = "play_pause"
        private const val PIP_REWIND = "rewind"
        private const val PIP_FORWARD = "forward"

        /**
         * Intent that plays the plan published in PlaybackQueueStore, starting with [firstUri] (what the screen opens with
         * if the process was recreated and the plan is gone).
         */
        fun createQueueIntent(context: Context, firstUri: Uri): Intent = createIntent(context, firstUri).putExtra(EXTRA_QUEUE, true)

        /** Intent that opens [uri] in the player; the read permission travels with it. */
        fun createIntent(context: Context, uri: Uri): Intent =
            Intent(context, PlayerActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
