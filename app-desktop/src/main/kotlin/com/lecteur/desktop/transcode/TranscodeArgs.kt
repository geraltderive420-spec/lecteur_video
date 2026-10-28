package com.lecteur.desktop.transcode

import com.lecteur.core.common.library.TranscodeLevels
import com.lecteur.core.model.HdrType
import java.io.File

/** The H.264 encoders ffmpeg can use, best first. [hwaccel]: how ffmpeg decodes on the same chip (frames come back to memory). */
enum class VideoEncoder(val codec: String, val label: String, val hwaccel: String?) {
    NVENC("h264_nvenc", "NVIDIA NVENC", "cuda"),
    QSV("h264_qsv", "Intel Quick Sync", "d3d11va"),
    AMF("h264_amf", "AMD AMF", "d3d11va"),
    // The processor encodes; decoding still goes to the graphics chip when there is one (a Ryzen G's)
    X264("libx264", "Processeur (x264)", "d3d11va");

    val hardware: Boolean get() = this != X264
}

/**
 * ffmpeg command lines that bring a file down to a smaller height, always to H.264 + AAC stereo: what every phone, TV and
 * Chromecast plays. HDR is tone-mapped to SDR after the downscale, on a picture 9 times smaller than 4K.
 */
object TranscodeArgs {
    /** The heights offered to devices; a file is only offered the ones below its own. */
    val HEIGHTS = listOf(1080, 720, 480, 360)

    /** HLS segment length. Short: playback starts (and restarts after a seek) as soon as the first one is encoded. */
    const val SEGMENT_SECONDS = 4

    private fun videoKbps(height: Int) = TranscodeLevels.videoKbps(height)

    private const val AUDIO_KBPS = TranscodeLevels.AUDIO_KBPS

    fun videoFilter(height: Int, hdr: HdrType): String {
        val scale = "scale=-2:$height:flags=bicubic"
        val transfer = when (hdr) {
            HdrType.NONE -> return "$scale,format=yuv420p"
            HdrType.HLG -> "arib-std-b67"
            else -> "smpte2084"
        }
        // Files often leave the colour tags out: zscale needs them, so they are stated
        return "$scale,setparams=color_primaries=bt2020:color_trc=$transfer:colorspace=bt2020nc:range=tv," +
            "zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,tonemap=tonemap=hable:desat=0," +
            "zscale=t=bt709:m=bt709:r=tv,format=yuv420p"
    }

    /** [live]: encoding while someone watches (fast presets); else a download, where quality matters more than speed. */
    fun encoder(encoder: VideoEncoder, height: Int, live: Boolean): List<String> {
        val kbps = videoKbps(height)
        val rate = listOf("-b:v", "${kbps}k", "-maxrate", "${kbps * 3 / 2}k", "-bufsize", "${kbps * 2}k")
        return when (encoder) {
            VideoEncoder.NVENC -> listOf("-c:v", "h264_nvenc", "-preset", if (live) "p4" else "p6", "-tune", "hq", "-rc", "vbr") + rate +
                listOf("-profile:v", "high", "-forced-idr", "1")
            VideoEncoder.QSV -> listOf("-c:v", "h264_qsv", "-preset", if (live) "faster" else "slow") + rate + listOf("-profile:v", "high", "-forced_idr", "1")
            VideoEncoder.AMF -> listOf("-c:v", "h264_amf", "-quality", if (live) "speed" else "quality", "-rc", "vbr_peak") + rate +
                listOf("-profile:v", "high", "-forced_idr", "1")
            VideoEncoder.X264 -> listOf("-c:v", "libx264", "-preset", if (live) "veryfast" else "medium", "-crf", if (live) "23" else "21") +
                listOf("-maxrate", "${kbps * 3 / 2}k", "-bufsize", "${kbps * 2}k", "-profile:v", "high")
        }
    }

    private fun input(input: File, encoder: VideoEncoder, hwDecode: Boolean, before: List<String> = emptyList()): List<String> =
        listOf("-hide_banner", "-nostdin", "-loglevel", "error", "-y") +
            (if (hwDecode && encoder.hwaccel != null) listOf("-hwaccel", encoder.hwaccel) else emptyList()) +
            before + listOf("-i", input.absolutePath)

    /**
     * HLS from segment [startSegment] on: `seg<N>.ts` in [dir], ffmpeg's own playlist (the segments it finished) in
     * `out.m3u8`. Timestamps continue from the start of the film, so segments of two runs (before and after a seek) fit.
     * [audio]: the audio track (in file order), null for ffmpeg's choice.
     */
    fun hls(input: File, dir: File, startSegment: Int, height: Int, hdr: HdrType, audio: Int?, encoder: VideoEncoder, hwDecode: Boolean): List<String> {
        val start = startSegment * SEGMENT_SECONDS
        val seek = if (start > 0) listOf("-ss", start.toString()) else emptyList()
        // Ahead of the player, but not the whole film at once: 4× real time after the first minute
        val pace = listOf("-readrate", "4", "-readrate_initial_burst", "60")
        return input(input, encoder, hwDecode, seek + pace) +
            listOf("-map", "0:V:0", "-map", if (audio != null) "0:a:$audio?" else "0:a:0?", "-sn", "-dn") +
            listOf("-vf", videoFilter(height, hdr)) + encoder(encoder, height, live = true) +
            listOf("-force_key_frames", "expr:gte(t,n_forced*$SEGMENT_SECONDS)") +
            listOf("-c:a", "aac", "-ac", "2", "-b:a", "${AUDIO_KBPS}k", "-max_muxing_queue_size", "2048") +
            (if (start > 0) listOf("-output_ts_offset", start.toString()) else emptyList()) +
            listOf(
                "-f", "hls", "-hls_time", SEGMENT_SECONDS.toString(), "-hls_list_size", "0", "-hls_playlist_type", "event",
                "-start_number", startSegment.toString(), "-hls_segment_filename", File(dir, "seg%d.ts").absolutePath, File(dir, "out.m3u8").absolutePath
            )
    }

    /**
     * A smaller Matroska copy to keep: every audio track (AAC stereo), the subtitles and fonts as they are.
     * [subtitles] false drops them (a subtitle format Matroska cannot take); progress goes to stdout.
     */
    fun download(input: File, output: File, height: Int, hdr: HdrType, encoder: VideoEncoder, hwDecode: Boolean, subtitles: Boolean): List<String> {
        // MP4 text subtitles cannot be copied into Matroska: turned into SRT
        val mp4 = input.extension.lowercase() in setOf("mp4", "m4v", "mov")
        val subs = when {
            !subtitles -> listOf("-sn")
            mp4 -> listOf("-map", "0:s?", "-c:s", "srt")
            else -> listOf("-map", "0:s?", "-map", "0:t?", "-c:s", "copy", "-c:t", "copy")
        }
        return input(input, encoder, hwDecode) +
            listOf("-map", "0:V:0", "-map", "0:a?") + subs + listOf("-dn") +
            listOf("-vf", videoFilter(height, hdr)) + encoder(encoder, height, live = false) +
            listOf("-c:a", "aac", "-ac", "2", "-b:a", "${AUDIO_KBPS}k", "-max_muxing_queue_size", "4096") +
            listOf("-progress", "pipe:1", "-nostats", "-f", "matroska", output.absolutePath)
    }

    /** One black frame through [encoder]: tells whether this PC (chip and driver) can use it. */
    fun probe(encoder: VideoEncoder): List<String> =
        listOf("-hide_banner", "-nostdin", "-loglevel", "error", "-f", "lavfi", "-i", "color=black:s=640x360:d=0.2", "-frames:v", "3") +
            listOf("-vf", "format=yuv420p", "-c:v", encoder.codec, "-f", "null", "-")
}
