package com.lecteur.feature.cast.chromecast

import android.content.Context
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.lecteur.core.common.cast.compat.CastCandidate
import com.lecteur.core.common.cast.compat.CastCompatibility
import com.lecteur.core.common.cast.compat.CastDeviceProfile
import com.lecteur.core.common.cast.compat.CastVerdict
import com.lecteur.core.data.playback.PlaybackRequestFactory
import com.lecteur.core.database.dao.MediaFileDao
import com.lecteur.core.player.resume.WatchStateStore
import com.lecteur.feature.cast.stream.MimeTypes
import com.lecteur.feature.cast.stream.ShareFailure
import com.lecteur.feature.cast.stream.StreamHost
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** What came of an attempt to cast a file to a Chromecast. */
sealed interface ChromecastResult {
    data class Started(val notes: List<String>) : ChromecastResult

    /** The file cannot play on a Chromecast; [message] says why and what to do instead. */
    data class Refused(val message: String) : ChromecastResult

    data class Failed(val message: String) : ChromecastResult
}

/**
 * Standard Chromecast casting. The Default Media Receiver plays only MP4/WebM with H.264 (and a few other codecs),
 * so the file is checked first and a refusal explains why instead of ending in a black TV screen. The file is
 * served by the phone's own HTTP server ([StreamHost]); the Chromecast fetches it directly.
 */
@Singleton
class ChromecastController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaFiles: MediaFileDao,
    private val streamHost: StreamHost,
    private val requestFactory: PlaybackRequestFactory,
    private val watchStateStore: WatchStateStore
) {
    /** Null on devices without Google Play services, where Cast cannot work. */
    private val castContext: CastContext? by lazy { runCatching { CastContext.getSharedInstance(context) }.getOrNull() }

    val isAvailable: Boolean get() = castContext != null

    private val session: CastSession? get() = castContext?.sessionManager?.currentCastSession

    val hasSession: Boolean get() = session?.isConnected == true

    /** Whether [mediaFileId] can go to a Chromecast, with the reasons when it cannot. */
    suspend fun check(mediaFileId: Long, profile: CastDeviceProfile = CastDeviceProfile.CONSERVATIVE): CastVerdict? =
        withContext(Dispatchers.IO) {
            val file = mediaFiles.getMediaFileById(mediaFileId) ?: return@withContext null
            val tracks = mediaFiles.getAudioTracks(mediaFileId)
            val saved = watchStateStore.get(mediaFileId)?.selectedAudioTrackIndex
            // The track that would play: the saved choice, else the default one, else the first.
            val audio = tracks.firstOrNull { it.trackIndex == saved }
                ?: tracks.firstOrNull { it.isDefault }
                ?: tracks.firstOrNull()
            CastCompatibility.check(
                CastCandidate(file.container, file.videoCodec, audio?.codec, file.height, file.hdrType),
                profile
            )
        }

    suspend fun cast(mediaFileId: Long, fromStart: Boolean = false): ChromecastResult {
        val active = session?.takeIf { it.isConnected }?.remoteMediaClient
            ?: return ChromecastResult.Failed("Choisissez d'abord un Chromecast dans la liste.")

        val verdict = check(mediaFileId) ?: return ChromecastResult.Failed("Ce fichier n'est plus dans la bibliothèque.")
        if (verdict is CastVerdict.Incompatible) return ChromecastResult.Refused(verdict.message)

        val shared = try {
            streamHost.share(mediaFileId)
        } catch (e: ShareFailure) {
            return ChromecastResult.Failed(e.message ?: "Impossible de partager ce fichier.")
        }
        val file = mediaFiles.getMediaFileById(mediaFileId)
        val prepared = requestFactory.forMediaFile(mediaFileId)
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, prepared?.request?.title ?: file?.fileName.orEmpty())
        }
        val info = MediaInfo.Builder(shared.url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(MimeTypes.forContainer(file?.container, file?.fileName.orEmpty()))
            .setMetadata(metadata)
            .build()
        val start = if (fromStart) 0L else prepared?.resumePromptMs ?: 0L
        active.load(
            MediaLoadRequestData.Builder()
                .setMediaInfo(info)
                .setAutoplay(true)
                .setCurrentTime(start)
                .build()
        )
        return ChromecastResult.Started((verdict as CastVerdict.Compatible).notes)
    }

    fun pause() { session?.remoteMediaClient?.pause() }
    fun play() { session?.remoteMediaClient?.play() }
    fun stop() { session?.remoteMediaClient?.stop() }
}
