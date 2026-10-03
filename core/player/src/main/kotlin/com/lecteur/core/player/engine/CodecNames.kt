package com.lecteur.core.player.engine

/** Human-readable codec labels from MIME types / RFC 6381 codec strings. */
object CodecNames {

    fun audio(mimeType: String?, codecs: String? = null): String? = when (mimeType) {
        null -> null
        "audio/ac3" -> "AC3"
        "audio/eac3" -> "E-AC3"
        "audio/eac3-joc" -> "E-AC3 JOC (Atmos)"
        "audio/true-hd" -> "TrueHD"
        "audio/vnd.dts" -> "DTS"
        "audio/vnd.dts.hd" -> if (codecs?.contains("dtsx", ignoreCase = true) == true) "DTS:X" else "DTS-HD MA"
        "audio/vnd.dts.hd;profile=lbr" -> "DTS Express"
        "audio/vnd.dts.uhd;profile=p2" -> "DTS:X"
        "audio/mp4a-latm" -> "AAC"
        "audio/mpeg", "audio/mpeg-L1", "audio/mpeg-L2" -> "MP3"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/flac" -> "FLAC"
        "audio/raw" -> "PCM"
        "audio/alac" -> "ALAC"
        else -> mimeType.substringAfter('/').uppercase()
    }

    fun video(mimeType: String?): String? = when (mimeType) {
        null -> null
        "video/avc" -> "H.264"
        "video/hevc" -> "HEVC"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/av01" -> "AV1"
        "video/mp4v-es" -> "MPEG-4"
        "video/mpeg2" -> "MPEG-2"
        "video/wvc1", "video/x-ms-wmv", "video/vc1" -> "VC-1"
        "video/dolby-vision" -> "Dolby Vision"
        else -> mimeType.substringAfter('/').uppercase()
    }

    fun subtitle(mimeType: String?): String? = when (mimeType) {
        null -> null
        "application/x-subrip" -> "SRT"
        "text/x-ssa" -> "ASS/SSA"
        "text/vtt" -> "WebVTT"
        "application/pgs" -> "PGS"
        "application/vobsub" -> "VOBSUB"
        "application/dvbsubs" -> "DVB"
        "application/ttml+xml" -> "TTML"
        "application/x-quicktime-tx3g" -> "TX3G"
        "application/cea-608", "application/cea-708" -> "CEA"
        else -> mimeType.substringAfter('/').uppercase()
    }

    /** Subtitle formats made of pictures, which cannot be restyled. */
    fun isImageSubtitle(mimeType: String?): Boolean =
        mimeType == "application/pgs" || mimeType == "application/vobsub" || mimeType == "application/dvbsubs"
}
