package com.lecteur.core.common.cast.compat

import com.lecteur.core.model.AudioCodec
import com.lecteur.core.model.HdrType
import com.lecteur.core.model.VideoCodec

/** What a Chromecast model can decode. The Cast SDK does not tell us the model, so [CONSERVATIVE] is the default. */
data class CastDeviceProfile(
    val supportsHevc: Boolean,
    val supportsVp9: Boolean,
    val maxHeight: Int,
    val supportsHdr: Boolean
) {
    companion object {
        /** First generations and the Chromecast with HD output: H.264 up to 1080p, nothing else assumed. */
        val CONSERVATIVE = CastDeviceProfile(supportsHevc = false, supportsVp9 = false, maxHeight = 1080, supportsHdr = false)

        /** Chromecast Ultra and Google TV models. */
        val ULTRA = CastDeviceProfile(supportsHevc = true, supportsVp9 = true, maxHeight = 2160, supportsHdr = true)
    }
}

/** The facts about a file the check needs: all of them are already stored by the scanner. */
data class CastCandidate(
    /** Normalised container name as stored by the scanner: "MKV", "MP4", "AVI"... */
    val container: String?,
    val videoCodec: VideoCodec,
    /** The audio track that would play (the others do not matter: Cast cannot switch tracks of a progressive file). */
    val audioCodec: AudioCodec?,
    val height: Int?,
    val hdrType: HdrType = HdrType.NONE
)

/** Why a file cannot go to a Chromecast, in terms the user can act on. */
enum class CastBlocker { CONTAINER, VIDEO_CODEC, AUDIO_CODEC, RESOLUTION }

data class CastIssue(val blocker: CastBlocker, val message: String)

sealed interface CastVerdict {
    /** Playable. [notes] are degradations (HDR shown as SDR...), not reasons to refuse. */
    data class Compatible(val notes: List<String> = emptyList()) : CastVerdict

    data class Incompatible(val issues: List<CastIssue>) : CastVerdict {
        /** One sentence for a dialog or snackbar, listing every reason. */
        val message: String
            get() = "Ce fichier ne peut pas être diffusé sur un Chromecast : " +
                issues.joinToString(" ; ") { it.message } +
                ". Utilisez le mode compagnon avec l'application Lecteur Média installée sur votre TV Android."
    }
}

/**
 * Whether the Chromecast Default Media Receiver can play a file as is. Nothing is transcoded, so the answer is a
 * strict yes/no on container, codecs and resolution; the unknown is treated as "no" for the container and video
 * codec (a wrong yes ends in a black screen on the TV) and as "yes" for the audio of a file with no audio info.
 */
object CastCompatibility {

    private val SUPPORTED_CONTAINERS = setOf("MP4", "M4V", "MOV", "WEBM")

    fun check(file: CastCandidate, device: CastDeviceProfile = CastDeviceProfile.CONSERVATIVE): CastVerdict {
        val issues = ArrayList<CastIssue>()

        val container = file.container?.uppercase()
        if (container == null || container !in SUPPORTED_CONTAINERS) {
            issues += CastIssue(
                CastBlocker.CONTAINER,
                "le conteneur ${container ?: "inconnu"} n'est pas lu par Chromecast (seuls MP4 et WebM le sont)"
            )
        }

        videoIssue(file.videoCodec, device)?.let(issues::add)
        file.audioCodec?.let { audioIssue(it)?.let(issues::add) }

        val height = file.height
        if (height != null && height > device.maxHeight) {
            issues += CastIssue(
                CastBlocker.RESOLUTION,
                "la résolution (${height}p) dépasse ce que votre Chromecast affiche (${device.maxHeight}p)"
            )
        }

        if (issues.isNotEmpty()) return CastVerdict.Incompatible(issues)

        val notes = ArrayList<String>()
        if (file.hdrType != HdrType.NONE && !device.supportsHdr) {
            notes += "Le HDR (${file.hdrType.label()}) ne sera pas restitué sur ce Chromecast."
        }
        return CastVerdict.Compatible(notes)
    }

    private fun videoIssue(codec: VideoCodec, device: CastDeviceProfile): CastIssue? {
        val ok = when (codec) {
            VideoCodec.H264 -> true
            VideoCodec.HEVC -> device.supportsHevc
            VideoCodec.VP9 -> device.supportsVp9
            else -> false
        }
        if (ok) return null
        val name = if (codec == VideoCodec.UNKNOWN) "inconnu" else codec.label()
        return CastIssue(CastBlocker.VIDEO_CODEC, "le codec vidéo $name n'est pas lu par ce Chromecast")
    }

    private fun audioIssue(codec: AudioCodec): CastIssue? {
        val ok = when (codec) {
            AudioCodec.AAC, AudioCodec.MP3, AudioCodec.AC3, AudioCodec.E_AC3, AudioCodec.FLAC,
            AudioCodec.OPUS, AudioCodec.VORBIS -> true

            else -> false
        }
        if (ok) return null
        val name = if (codec == AudioCodec.UNKNOWN) "inconnu" else codec.label()
        return CastIssue(CastBlocker.AUDIO_CODEC, "le codec audio $name n'est pas lu par Chromecast")
    }

    private fun VideoCodec.label() = when (this) {
        VideoCodec.H264 -> "H.264"
        VideoCodec.HEVC -> "H.265 (HEVC)"
        VideoCodec.VP9 -> "VP9"
        VideoCodec.AV1 -> "AV1"
        VideoCodec.MPEG4 -> "MPEG-4"
        VideoCodec.MPEG2 -> "MPEG-2"
        VideoCodec.VC1 -> "VC-1"
        VideoCodec.UNKNOWN -> "inconnu"
    }

    private fun AudioCodec.label() = when (this) {
        AudioCodec.AAC -> "AAC"
        AudioCodec.MP3 -> "MP3"
        AudioCodec.AC3 -> "Dolby Digital"
        AudioCodec.E_AC3 -> "Dolby Digital Plus"
        AudioCodec.E_AC3_JOC -> "Dolby Atmos"
        AudioCodec.TRUEHD -> "Dolby TrueHD"
        AudioCodec.DTS -> "DTS"
        AudioCodec.DTS_HD_MA -> "DTS-HD MA"
        AudioCodec.DTS_X -> "DTS:X"
        AudioCodec.FLAC -> "FLAC"
        AudioCodec.OPUS -> "Opus"
        AudioCodec.VORBIS -> "Vorbis"
        AudioCodec.PCM -> "PCM"
        AudioCodec.UNKNOWN -> "inconnu"
    }

    private fun HdrType.label() = when (this) {
        HdrType.NONE -> "SDR"
        HdrType.HDR10 -> "HDR10"
        HdrType.HDR10_PLUS -> "HDR10+"
        HdrType.HLG -> "HLG"
        HdrType.DOLBY_VISION -> "Dolby Vision"
    }
}
