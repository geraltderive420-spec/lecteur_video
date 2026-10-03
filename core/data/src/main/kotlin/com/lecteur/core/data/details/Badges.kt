package com.lecteur.core.data.details

import com.lecteur.core.database.entity.AudioTrackInfoEntity
import com.lecteur.core.database.entity.MediaFileEntity
import com.lecteur.core.database.entity.SubtitleTrackInfoEntity
import com.lecteur.core.model.AudioCodec
import com.lecteur.core.model.MediaBadges
import com.lecteur.core.model.ResolutionTier
import com.lecteur.core.model.channelLabel
import com.lecteur.core.model.label
import com.lecteur.core.player.tracks.LanguageCodes

/** The short technical badges of the detail page, built from what the scan really read in the file. */
object Badges {

    /** Higher is better: object audio and lossless first, then lossy surround, then plain stereo codecs. */
    private val audioRank = mapOf(
        AudioCodec.DTS_X to 10, AudioCodec.E_AC3_JOC to 9, AudioCodec.TRUEHD to 8, AudioCodec.DTS_HD_MA to 8,
        AudioCodec.DTS to 6, AudioCodec.E_AC3 to 5, AudioCodec.AC3 to 4, AudioCodec.FLAC to 4, AudioCodec.PCM to 3,
        AudioCodec.OPUS to 2, AudioCodec.AAC to 2, AudioCodec.VORBIS to 2, AudioCodec.MP3 to 1, AudioCodec.UNKNOWN to 0
    )

    /** "TrueHD 7.1": the best track by codec, then by channel count. Null when the file has no audio information. */
    fun audioLabel(tracks: List<AudioTrackInfoEntity>): String? {
        val best = tracks.maxWithOrNull(compareBy<AudioTrackInfoEntity>({ audioRank[it.codec] ?: 0 }, { it.channels })) ?: return null
        val codec = best.codec.label() ?: return channelLabel(best.channels)
        return listOfNotNull(codec, channelLabel(best.channels)).joinToString(" ")
    }

    /** Display names of the languages present, in file order, without duplicates. */
    fun languages(codes: List<String?>): List<String> =
        codes.mapNotNull { LanguageCodes.displayName(it) }.distinct()

    fun forFile(file: MediaFileEntity, audio: List<AudioTrackInfoEntity>, subtitles: List<SubtitleTrackInfoEntity>) = MediaBadges(
        resolution = ResolutionTier.badge(file.width, file.height),
        hdr = file.hdrType,
        videoCodec = file.videoCodec,
        audio = audioLabel(audio),
        audioLanguages = languages(audio.map { it.language }),
        subtitleLanguages = languages(subtitles.map { it.language })
    )

    /** What the episode list can say with the columns it already has: size and HDR, no audio. */
    fun fromColumns(width: Int?, height: Int?, hdr: com.lecteur.core.model.HdrType) = MediaBadges(
        resolution = ResolutionTier.badge(width, height),
        hdr = hdr,
        videoCodec = com.lecteur.core.model.VideoCodec.UNKNOWN,
        audio = null,
        audioLanguages = emptyList(),
        subtitleLanguages = emptyList()
    )
}
