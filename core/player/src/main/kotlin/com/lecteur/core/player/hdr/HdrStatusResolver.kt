package com.lecteur.core.player.hdr

import com.lecteur.core.model.HdrType

/** What the user actually sees, which can differ from what the file contains. */
enum class HdrOutput(val label: String, val isDegraded: Boolean) {
    SDR("SDR", false),
    HDR10("HDR10", false),
    HDR10_PLUS("HDR10+", false),
    HLG("HLG", false),
    DOLBY_VISION("Dolby Vision", false),
    DOLBY_VISION_AS_HDR10("HDR10 (repli Dolby Vision)", true),
    HDR_TONE_MAPPED_TO_SDR("SDR (tone-mapping)", true);

    /** The "Dolby Vision" badge may only be shown when the real Dolby Vision path is active. */
    val showsDolbyVisionBadge: Boolean get() = this == DOLBY_VISION
}

/**
 * Decides the effective HDR output from the source, the video decoder in use and the display capabilities.
 *
 * @param decoderIsDolbyVision the selected decoder is a Dolby Vision decoder (not the base-layer HEVC fallback)
 * @param displayHdrTypes HDR types the screen reports; empty means an SDR screen
 */
object HdrStatusResolver {

    fun resolve(source: HdrType, decoderIsDolbyVision: Boolean, displayHdrTypes: Set<HdrType>): HdrOutput {
        val displayHdr10 = HdrType.HDR10 in displayHdrTypes
        return when (source) {
            HdrType.NONE -> HdrOutput.SDR
            HdrType.DOLBY_VISION -> when {
                decoderIsDolbyVision && HdrType.DOLBY_VISION in displayHdrTypes -> HdrOutput.DOLBY_VISION
                displayHdr10 -> HdrOutput.DOLBY_VISION_AS_HDR10
                else -> HdrOutput.HDR_TONE_MAPPED_TO_SDR
            }
            HdrType.HDR10_PLUS -> when {
                HdrType.HDR10_PLUS in displayHdrTypes -> HdrOutput.HDR10_PLUS
                displayHdr10 -> HdrOutput.HDR10
                else -> HdrOutput.HDR_TONE_MAPPED_TO_SDR
            }
            HdrType.HDR10 -> if (displayHdr10) HdrOutput.HDR10 else HdrOutput.HDR_TONE_MAPPED_TO_SDR
            HdrType.HLG -> if (HdrType.HLG in displayHdrTypes) HdrOutput.HLG else HdrOutput.HDR_TONE_MAPPED_TO_SDR
        }
    }
}
