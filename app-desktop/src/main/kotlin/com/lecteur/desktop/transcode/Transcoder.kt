package com.lecteur.desktop.transcode

import com.lecteur.core.common.library.ServedFile
import com.lecteur.core.common.library.Transcoded
import com.lecteur.desktop.data.AppDirs
import com.lecteur.desktop.data.LibrarySnapshot
import com.lecteur.desktop.data.LibraryStore
import com.lecteur.desktop.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** What the settings show: looking for an encoder, the one found, or why there is none. */
sealed interface TranscoderState {
    data object Detecting : TranscoderState
    data class Ready(val encoder: VideoEncoder) : TranscoderState
    data object NoFfmpeg : TranscoderState
    data object NoEncoder : TranscoderState
}

/**
 * Smaller versions of the library's files for phones and TVs: [HlsStreams] while they watch, [TranscodedDownloads] for the
 * ones they keep. The encoder is chosen once at start: the graphics chip's when ffmpeg can open it, else the processor.
 */
class Transcoder(private val store: LibraryStore, private val settings: SettingsStore) {
    private val _state = MutableStateFlow<TranscoderState>(TranscoderState.Detecting)
    val state: StateFlow<TranscoderState> = _state.asStateFlow()

    private val encoder: VideoEncoder? get() = (_state.value as? TranscoderState.Ready)?.encoder?.takeIf { settings.value.transcodingEnabled }

    private val root = File(AppDirs.root, "transcode")
    private val streams by lazy { HlsStreams(File(root, "hls")) { encoder } }
    private val downloads by lazy { TranscodedDownloads(File(root, "downloads")) { encoder } }

    init {
        Thread({
            _state.value = when {
                Ffmpeg.executable == null -> TranscoderState.NoFfmpeg
                else -> Ffmpeg.detectEncoder()?.let { TranscoderState.Ready(it) } ?: TranscoderState.NoEncoder
            }
        }, "transcoder-detect").apply { isDaemon = true }.start()
    }

    /** Heights devices may ask for; empty while detecting, without ffmpeg, or when the user turned transcoding off. */
    val heights: List<Int> get() = if (encoder != null) TranscodeArgs.HEIGHTS else emptyList()

    val label: String? get() = encoder?.label

    fun hls(device: String, fileId: Long, height: Int, name: String): ServedFile? {
        val source = source(fileId, height) ?: return null
        if (name == com.lecteur.core.common.library.LibraryUrls.HLS_PLAYLIST) return streams.playlist(source)
        val index = name.removePrefix("seg").removeSuffix(".ts").toIntOrNull() ?: return null
        return streams.segment(device, fileId, height, source, index)
    }

    fun download(fileId: Long, height: Int): Transcoded? {
        val source = source(fileId, height) ?: return null
        return downloads.request(fileId, height, source)
    }

    fun shutdown() {
        runCatching { streams.stopAll() }
        runCatching { downloads.stopAll() }
    }

    private fun source(fileId: Long, height: Int): TranscodeSource? {
        if (encoder == null || height !in TranscodeArgs.HEIGHTS) return null
        val file = store.mediaFile(fileId)?.takeIf { it.isAvailable } ?: return null
        val disk = File(file.uri).takeIf { it.isFile } ?: return null
        val duration = file.durationMs?.takeIf { it > 0 } ?: Ffmpeg.durationMs(disk) ?: return null
        return TranscodeSource(disk, duration, file.hdrType, audioTrack(fileId))
    }

    /** The audio track in the first of the PC's preferred languages, else the file's default one. */
    private fun audioTrack(fileId: Long): Int? {
        val tracks = store.audioTracks(fileId)
        if (tracks.isEmpty()) return null
        settings.value.audioLanguages.forEach { wanted ->
            tracks.firstOrNull { t -> t.language?.let(LibrarySnapshot::normaliseLanguage) == wanted }?.let { return it.index }
        }
        return (tracks.firstOrNull { it.isDefault } ?: tracks.first()).index
    }
}
