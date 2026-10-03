package com.lecteur.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class MediaCategory(val serializedName: String) {
    MOVIES("MOVIES"),
    SERIES("SERIES"),
    ANIME("ANIME"),
    DOCUMENTARIES("DOCUMENTARIES"),
    PERSONAL("PERSONAL"),
    GENERIC("GENERIC");

    companion object {
        fun fromString(value: String): MediaCategory =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) || it.serializedName.equals(value, ignoreCase = true) }
                ?: GENERIC
    }
}

@Serializable
enum class MatchState {
    IDENTIFIED,
    TO_VERIFY,
    UNIDENTIFIED
}

@Serializable
enum class HdrType {
    NONE,
    HDR10,
    HDR10_PLUS,
    HLG,
    DOLBY_VISION
}

@Serializable
enum class VideoCodec {
    H264,
    HEVC,
    VP9,
    AV1,
    MPEG4,
    MPEG2,
    VC1,
    UNKNOWN
}

@Serializable
enum class AudioCodec {
    AAC,
    MP3,
    AC3,
    E_AC3,
    E_AC3_JOC,
    TRUEHD,
    DTS,
    DTS_HD_MA,
    DTS_X,
    FLAC,
    OPUS,
    VORBIS,
    PCM,
    UNKNOWN
}
