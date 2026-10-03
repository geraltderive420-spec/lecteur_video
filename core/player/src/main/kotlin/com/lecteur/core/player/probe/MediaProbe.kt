package com.lecteur.core.player.probe

import android.content.Context
import android.net.Uri
import com.lecteur.core.player.chapters.MediaChapter
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfoBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class ProbeAudioStream(
    val index: Int,
    val codecName: String?,
    val language: String?,
    val title: String?,
    val channels: Int,
    val isDefault: Boolean,
    val isForced: Boolean
)

data class ProbeSubtitleStream(
    val index: Int,
    val codecName: String?,
    val language: String?,
    val title: String?,
    val isDefault: Boolean,
    val isForced: Boolean
)

/** Container-level facts about a file that the playback engine does not expose (chapters, stream list). */
data class ProbeResult(
    val chapters: List<MediaChapter>,
    val width: Int?,
    val height: Int?,
    val videoCodecName: String?,
    val audioStreamCount: Int,
    val subtitleStreamCount: Int,
    /** FFmpeg demuxer name, e.g. "matroska,webm". */
    val format: String? = null,
    /** Duration exactly as the library reports it, unit undocumented: see [ChapterUnits.guessDurationMs]. */
    val rawDuration: Long = 0,
    val frameRate: Double? = null,
    val audioStreams: List<ProbeAudioStream> = emptyList(),
    val subtitleStreams: List<ProbeSubtitleStream> = emptyList()
)

/**
 * Reads container metadata through FFmpeg (nextlib-mediainfo). It is also the entry point the library scanner
 * will use for technical information. Never throws: an unreadable file simply yields null.
 */
@Singleton
class MediaProbe @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        // FFmpeg AV_DISPOSITION_* bits
        const val DISPOSITION_DEFAULT = 0x1
        const val DISPOSITION_FORCED = 0x40
    }

    suspend fun probe(uri: Uri): ProbeResult? = withContext(Dispatchers.IO) {
        runCatching {
            val info = MediaInfoBuilder().from(context, uri).build() ?: return@runCatching null
            try {
                ProbeResult(
                    chapters = info.chapters.map { MediaChapter(it.index, it.start, it.end, it.title?.takeIf(String::isNotBlank)) },
                    width = info.videoStream?.frameWidth?.takeIf { it > 0 },
                    height = info.videoStream?.frameHeight?.takeIf { it > 0 },
                    videoCodecName = info.videoStream?.codecName,
                    audioStreamCount = info.audioStreams.size,
                    subtitleStreamCount = info.subtitleStreams.size,
                    format = info.format?.takeIf(String::isNotBlank),
                    rawDuration = info.duration,
                    frameRate = info.videoStream?.frameRate?.takeIf { it > 0.0 },
                    audioStreams = info.audioStreams.map {
                        ProbeAudioStream(
                            index = it.index,
                            codecName = it.codecName,
                            language = it.language?.takeIf(String::isNotBlank),
                            title = it.title?.takeIf(String::isNotBlank),
                            channels = it.channels,
                            isDefault = it.disposition and DISPOSITION_DEFAULT != 0,
                            isForced = it.disposition and DISPOSITION_FORCED != 0
                        )
                    },
                    subtitleStreams = info.subtitleStreams.map {
                        ProbeSubtitleStream(
                            index = it.index,
                            codecName = it.codecName,
                            language = it.language?.takeIf(String::isNotBlank),
                            title = it.title?.takeIf(String::isNotBlank),
                            isDefault = it.disposition and DISPOSITION_DEFAULT != 0,
                            isForced = it.disposition and DISPOSITION_FORCED != 0
                        )
                    }
                )
            } finally {
                info.release()
            }
        }.getOrNull()
    }
}

/**
 * The probe library does not document its time unit. Chapter times are rescaled against the real duration known
 * by the player: values wildly larger than the media are microseconds.
 */
object ChapterUnits {

    /**
     * Last-resort duration when the platform extractor cannot open the file. Milliseconds below 2e8 (55 hours),
     * microseconds above; a clip shorter than 200 s reported in microseconds is misread, which is why the
     * extractor's duration is always preferred.
     */
    fun guessDurationMs(raw: Long): Long? = when {
        raw <= 0 -> null
        raw > 200_000_000L -> raw / 1000
        else -> raw
    }

    fun normalize(chapters: List<MediaChapter>, mediaDurationMs: Long): List<MediaChapter> {
        if (chapters.isEmpty() || mediaDurationMs <= 0L) return chapters
        val last = chapters.maxOf { maxOf(it.startMs, it.endMs) }
        val divisor = if (last > mediaDurationMs * 50) 1000L else 1L
        return chapters
            .map { it.copy(startMs = it.startMs / divisor, endMs = it.endMs / divisor) }
            .filter { it.startMs in 0..mediaDurationMs }
    }
}
