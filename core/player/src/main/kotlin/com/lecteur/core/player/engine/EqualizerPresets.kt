package com.lecteur.core.player.engine

import com.lecteur.core.player.settings.EqualizerPreset

/** Band gains (millibels) per preset, independent of the device's band layout. */
object EqualizerPresets {

    fun gainMillibels(preset: EqualizerPreset, centerFrequencyHz: Int): Int = when (preset) {
        EqualizerPreset.FLAT -> 0
        EqualizerPreset.VOICE -> when {
            centerFrequencyHz < 200 -> -300
            centerFrequencyHz in 1_000..4_000 -> 300
            else -> 0
        }
        EqualizerPreset.BASS_BOOST -> when {
            centerFrequencyHz < 150 -> 500
            centerFrequencyHz < 400 -> 250
            else -> 0
        }
        EqualizerPreset.TREBLE_BOOST -> when {
            centerFrequencyHz >= 8_000 -> 500
            centerFrequencyHz >= 3_000 -> 250
            else -> 0
        }
    }
}
