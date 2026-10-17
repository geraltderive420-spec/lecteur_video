package com.lecteur.desktop.player

import com.lecteur.core.model.AudioCodec
import com.lecteur.core.model.HdrType
import com.lecteur.core.model.ParsedMediaInfo
import com.lecteur.core.model.VideoCodec
import com.lecteur.desktop.data.AudioTrackRow
import com.lecteur.desktop.data.SubtitleTrackRow
import com.lecteur.desktop.data.TechInfo
import uk.co.caprica.vlcj.media.Media
import uk.co.caprica.vlcj.media.MediaEventAdapter
import uk.co.caprica.vlcj.media.MediaParsedStatus
import uk.co.caprica.vlcj.media.ParseFlag
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Reads duration, picture size and tracks of a file through libVLC's parser (no decoding, a few milliseconds per file). */
object MediaProbe {

    fun probe(file: File, parsed: ParsedMediaInfo, timeoutMs: Int = 8_000): TechInfo {
        val fromName = fromName(file, parsed)
        val factory = Vlc.factory() ?: return fromName
        val media: Media = factory.media().newMedia(file.absolutePath) ?: return fromName
        try {
            val done = CountDownLatch(1)
            media.events().addMediaEventListener(object : MediaEventAdapter() {
                override fun mediaParsedChanged(media: Media, newStatus: MediaParsedStatus) {
                    done.countDown()
                }
            })
            if (!media.parsing().parse(timeoutMs, ParseFlag.PARSE_LOCAL)) return fromName
            done.await(timeoutMs + 1_000L, TimeUnit.MILLISECONDS)
            if (media.parsing().status() != MediaParsedStatus.DONE) return fromName

            val info = media.info()
            val video = info.videoTracks().firstOrNull()
            val audio = info.audioTracks().mapIndexed { i, t ->
                AudioTrackRow(
                    index = i,
                    language = t.language()?.takeIf { it.isNotBlank() },
                    codec = audioCodec(t.codecName(), t.codecDescription(), t.profile(), parsed.audioCodec),
                    channels = t.channels(),
                    title = t.description()?.takeIf { it.isNotBlank() },
                    isDefault = i == 0
                )
            }
            val text = info.textTracks().mapIndexed { i, t ->
                SubtitleTrackRow(
                    index = i,
                    language = t.language()?.takeIf { it.isNotBlank() },
                    codec = t.codecName()?.trim(),
                    externalPath = null,
                    title = t.description()?.takeIf { it.isNotBlank() },
                    isForced = t.description()?.contains("forc", ignoreCase = true) == true
                )
            }
            val duration = info.duration().takeIf { it > 0 }
            return TechInfo(
                durationMs = duration,
                container = file.extension.uppercase(),
                videoCodec = video?.let { videoCodec(it.codecName()) }?.takeIf { it != VideoCodec.UNKNOWN } ?: fromName.videoCodec,
                width = video?.width()?.takeIf { it > 0 } ?: fromName.width,
                height = video?.height()?.takeIf { it > 0 } ?: fromName.height,
                hdr = fromName.hdr,
                audio = audio.ifEmpty { fromName.audio },
                subtitles = text
            )
        } catch (_: Throwable) {
            return fromName
        } finally {
            media.release()
        }
    }

    /** What the file name says, used when libVLC cannot read the file. */
    private fun fromName(file: File, parsed: ParsedMediaInfo): TechInfo {
        val (w, h) = when (parsed.resolution?.lowercase()) {
            "2160p", "4k", "uhd" -> 3840 to 2160
            "1080p", "1080i" -> 1920 to 1080
            "720p" -> 1280 to 720
            "576p" -> 720 to 576
            "480p" -> 720 to 480
            else -> null to null
        }
        return TechInfo(
            durationMs = null,
            container = file.extension.uppercase(),
            videoCodec = when (parsed.videoCodec?.lowercase()) {
                "x265", "h265", "hevc" -> VideoCodec.HEVC
                "x264", "h264", "avc" -> VideoCodec.H264
                "av1" -> VideoCodec.AV1
                "vp9" -> VideoCodec.VP9
                "xvid", "divx" -> VideoCodec.MPEG4
                "mpeg2" -> VideoCodec.MPEG2
                else -> VideoCodec.UNKNOWN
            },
            width = w, height = h,
            hdr = parsed.hdrType,
            audio = emptyList(),
            subtitles = emptyList()
        )
    }

    private fun videoCodec(fourcc: String?): VideoCodec = when (fourcc?.trim()?.lowercase()) {
        "h264", "avc1", "x264" -> VideoCodec.H264
        "hevc", "h265", "hvc1", "hev1" -> VideoCodec.HEVC
        "vp90", "vp9" -> VideoCodec.VP9
        "av01", "av1" -> VideoCodec.AV1
        "mp4v", "xvid", "divx", "dx50", "fmp4" -> VideoCodec.MPEG4
        "mpgv", "mp2v", "mpeg2" -> VideoCodec.MPEG2
        "wvc1", "vc-1", "vc1" -> VideoCodec.VC1
        else -> VideoCodec.UNKNOWN
    }

    private fun audioCodec(fourcc: String?, description: String?, profile: Int, nameHint: String?): AudioCodec {
        val desc = description.orEmpty().lowercase()
        val hint = nameHint.orEmpty().lowercase()
        return when (fourcc?.trim()?.lowercase()) {
            "mp4a", "aac" -> AudioCodec.AAC
            "mpga", "mp3", "mp3 " -> AudioCodec.MP3
            "a52", "ac-3", "ac3" -> AudioCodec.AC3
            "eac3" -> if ("atmos" in desc || "atmos" in hint) AudioCodec.E_AC3_JOC else AudioCodec.E_AC3
            "mlp", "trhd" -> AudioCodec.TRUEHD
            "dts" -> when {
                "dts:x" in desc || "dts-x" in hint -> AudioCodec.DTS_X
                "ma" in desc || "dts-hd" in hint || profile > 0 -> AudioCodec.DTS_HD_MA
                else -> AudioCodec.DTS
            }
            "flac" -> AudioCodec.FLAC
            "opus" -> AudioCodec.OPUS
            "vorb" -> AudioCodec.VORBIS
            "araw", "lpcm", "s16l", "s24l", "pcm", "bpcm" -> AudioCodec.PCM
            else -> AudioCodec.UNKNOWN
        }
    }
}
