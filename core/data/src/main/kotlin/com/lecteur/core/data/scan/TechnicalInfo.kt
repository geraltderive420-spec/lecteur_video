package com.lecteur.core.data.scan

import com.lecteur.core.model.AudioCodec
import com.lecteur.core.model.HdrType
import com.lecteur.core.model.VideoCodec

data class AudioTrackFacts(
    val index: Int,
    val language: String?,
    val codec: AudioCodec,
    val channels: Int,
    val title: String?,
    val isDefault: Boolean,
    val isForced: Boolean
)

data class SubtitleTrackFacts(
    val index: Int,
    val language: String?,
    val codec: String?,
    val title: String?,
    val isDefault: Boolean,
    val isForced: Boolean
)

/** What the library knows about a file's content, read from the file itself (not guessed from its name). */
data class TechnicalInfo(
    val durationMs: Long?,
    val container: String?,
    val videoCodec: VideoCodec,
    val width: Int?,
    val height: Int?,
    val hdrType: HdrType,
    val audioTracks: List<AudioTrackFacts>,
    val subtitleTracks: List<SubtitleTrackFacts>
)

/** FFmpeg codec names to the app's enums and labels. */
object CodecMapper {

    fun video(codecName: String?): VideoCodec = when (codecName?.lowercase()) {
        "h264", "avc", "avc1" -> VideoCodec.H264
        "hevc", "h265" -> VideoCodec.HEVC
        "vp9" -> VideoCodec.VP9
        "av1" -> VideoCodec.AV1
        "mpeg4", "msmpeg4v3", "msmpeg4v2", "msmpeg4v1" -> VideoCodec.MPEG4 // Xvid/DivX are MPEG-4 part 2
        "mpeg2video", "mpeg1video" -> VideoCodec.MPEG2
        "vc1", "wmv3", "wmv2", "wmv1" -> VideoCodec.VC1
        else -> VideoCodec.UNKNOWN
    }

    /**
     * FFmpeg reports DTS-HD MA, DTS:X and E-AC-3 JOC under their core's name (`dts`, `eac3`) and the probe library does
     * not expose the profile, so the track title (where release groups write "Atmos", "DTS-HD MA"...) is the only hint.
     */
    fun audio(codecName: String?, title: String?): AudioCodec {
        val hint = title?.lowercase().orEmpty()
        return when (val name = codecName?.lowercase()) {
            "aac", "aac_latm" -> AudioCodec.AAC
            "mp3", "mp2" -> AudioCodec.MP3
            "ac3" -> AudioCodec.AC3
            "eac3" -> if ("atmos" in hint) AudioCodec.E_AC3_JOC else AudioCodec.E_AC3
            "truehd", "mlp" -> AudioCodec.TRUEHD
            "dts" -> when {
                "dts:x" in hint || "dts-x" in hint || "dts x" in hint -> AudioCodec.DTS_X
                "dts-hd" in hint || "dts hd" in hint || "master audio" in hint -> AudioCodec.DTS_HD_MA
                else -> AudioCodec.DTS
            }
            "flac" -> AudioCodec.FLAC
            "opus" -> AudioCodec.OPUS
            "vorbis" -> AudioCodec.VORBIS
            else -> if (name != null && name.startsWith("pcm")) AudioCodec.PCM else AudioCodec.UNKNOWN
        }
    }

    /** Subtitle format label as shown in track lists; image formats keep their well-known names. */
    fun subtitle(codecName: String?): String? = when (val name = codecName?.lowercase()) {
        null, "" -> null
        "subrip", "srt" -> "SRT"
        "ass", "ssa" -> "ASS"
        "hdmv_pgs_subtitle", "pgssub" -> "PGS"
        "dvd_subtitle", "dvdsub" -> "VOBSUB"
        "dvb_subtitle" -> "DVB"
        "webvtt" -> "VTT"
        "mov_text" -> "TX3G"
        else -> name.uppercase()
    }
}

object ContainerNames {

    /** @param format the FFmpeg demuxer name, which is shared by several containers ("matroska,webm"). */
    fun of(format: String?, extension: String?): String? {
        val ext = extension?.lowercase()?.takeIf { it.isNotEmpty() }
        val name = format?.lowercase().orEmpty()
        return when {
            "matroska" in name || "webm" in name -> if (ext == "webm") "WEBM" else "MKV"
            "mov" in name || "mp4" in name -> when (ext) {
                "mov" -> "MOV"
                "m4v" -> "M4V"
                else -> "MP4"
            }
            name == "avi" -> "AVI"
            "mpegts" in name -> if (ext == "m2ts") "M2TS" else "TS"
            name == "mpeg" || "mpegps" in name -> "MPEG"
            "asf" in name -> "WMV"
            "flv" in name -> "FLV"
            else -> ext?.uppercase()
        }
    }
}

object HdrClassifier {

    // android.media.MediaFormat.COLOR_TRANSFER_* values, kept here so this stays testable without Android
    const val TRANSFER_ST2084 = 6
    const val TRANSFER_HLG = 7
    const val DOLBY_VISION_MIME = "video/dolby-vision"

    /** HDR type from what the platform extractor sees; null when it could not look at the file. */
    fun fromExtractor(extractorRan: Boolean, mime: String?, colorTransfer: Int?): HdrType? = when {
        !extractorRan -> null
        mime == DOLBY_VISION_MIME -> HdrType.DOLBY_VISION
        colorTransfer == TRANSFER_ST2084 -> HdrType.HDR10
        colorTransfer == TRANSFER_HLG -> HdrType.HLG
        else -> HdrType.NONE
    }

    /**
     * Combines the file's own signal with the file name. The extractor cannot see Dolby Vision RPU side data or HDR10+
     * dynamic metadata (both ride on an HDR10 base layer), so a name tag may upgrade HDR10 but never contradict SDR.
     */
    fun merge(detected: HdrType?, fromName: HdrType): HdrType = when (detected) {
        null -> fromName
        HdrType.HDR10 -> if (fromName == HdrType.DOLBY_VISION || fromName == HdrType.HDR10_PLUS) fromName else HdrType.HDR10
        else -> detected
    }
}

/** What the platform MediaExtractor reports about the first video track; null fields mean "not reported". */
data class ExtractorFacts(
    val ran: Boolean,
    val durationMs: Long? = null,
    val videoMime: String? = null,
    val colorTransfer: Int? = null,
    val width: Int? = null,
    val height: Int? = null
)

object TechnicalInfoBuilder {

    fun build(
        probe: com.lecteur.core.player.probe.ProbeResult?,
        extractor: ExtractorFacts,
        nameHdr: HdrType,
        extension: String?
    ): TechnicalInfo {
        val duration = extractor.durationMs?.takeIf { it > 0 }
            ?: probe?.let { com.lecteur.core.player.probe.ChapterUnits.guessDurationMs(it.rawDuration) }

        return TechnicalInfo(
            durationMs = duration,
            container = ContainerNames.of(probe?.format, extension),
            videoCodec = CodecMapper.video(probe?.videoCodecName),
            width = probe?.width ?: extractor.width,
            height = probe?.height ?: extractor.height,
            hdrType = HdrClassifier.merge(
                HdrClassifier.fromExtractor(extractor.ran && extractor.videoMime != null, extractor.videoMime, extractor.colorTransfer),
                nameHdr
            ),
            audioTracks = probe?.audioStreams.orEmpty().map {
                AudioTrackFacts(it.index, it.language, CodecMapper.audio(it.codecName, it.title), it.channels, it.title, it.isDefault, it.isForced)
            },
            subtitleTracks = probe?.subtitleStreams.orEmpty().map {
                SubtitleTrackFacts(it.index, it.language, CodecMapper.subtitle(it.codecName), it.title, it.isDefault, it.isForced)
            }
        )
    }
}
