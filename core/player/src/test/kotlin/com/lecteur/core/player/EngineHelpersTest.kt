package com.lecteur.core.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.google.common.truth.Truth.assertThat
import com.lecteur.core.model.HdrType
import com.lecteur.core.player.engine.AudioDelayProcessor
import com.lecteur.core.player.engine.CodecNames
import com.lecteur.core.player.engine.EqualizerPresets
import com.lecteur.core.player.engine.Media3PlayerEngine
import com.lecteur.core.player.engine.PlaybackErrorKind
import com.lecteur.core.player.engine.PlaybackErrorMapper
import com.lecteur.core.player.settings.EqualizerPreset
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
class EngineHelpersTest {

    // region AudioDelayProcessor (16-bit stereo, 1000 Hz so that 1 ms = 1 frame = 4 bytes)

    private val format = AudioProcessor.AudioFormat(1000, 2, C.ENCODING_PCM_16BIT)

    private fun processor(delayMs: Long): AudioDelayProcessor = AudioDelayProcessor().apply {
        setDelayMs(delayMs)
        configure(format)
        flush()
    }

    private fun frames(startValue: Int, count: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until count) {
            buffer.putShort((startValue + i).toShort())
            buffer.putShort((startValue + i).toShort())
        }
        buffer.flip()
        return buffer
    }

    private fun AudioDelayProcessor.run(input: ByteBuffer): List<Int> {
        queueInput(input)
        val out = output
        val values = mutableListOf<Int>()
        while (out.remaining() >= 4) {
            values += out.getShort().toInt()
            out.getShort()
        }
        return values
    }

    @Test
    fun zeroDelayIsTransparent() {
        val p = processor(0)
        assertThat(p.run(frames(1, 5))).containsExactly(1, 2, 3, 4, 5).inOrder()
    }

    @Test
    fun positiveDelayPrependsSilenceOnce() {
        val p = processor(3)
        assertThat(p.run(frames(1, 4))).containsExactly(0, 0, 0, 1, 2, 3, 4).inOrder()
        assertThat(p.run(frames(5, 2))).containsExactly(5, 6).inOrder()
    }

    @Test
    fun negativeDelayDropsLeadingFramesAcrossBuffers() {
        val p = processor(-3)
        assertThat(p.run(frames(1, 2))).isEmpty()
        assertThat(p.run(frames(3, 4))).containsExactly(4, 5, 6).inOrder()
    }

    @Test
    fun delayChangeMidStreamAppliesOnlyTheDifference() {
        val p = processor(2)
        p.run(frames(1, 3)) // 2 frames of silence + 3
        p.setDelayMs(5)
        assertThat(p.run(frames(4, 2))).containsExactly(0, 0, 0, 4, 5).inOrder()
        p.setDelayMs(4)
        assertThat(p.run(frames(6, 3))).containsExactly(7, 8).inOrder()
    }

    @Test
    fun flushReappliesTheWholeDelay() {
        val p = processor(2)
        p.run(frames(1, 3))
        p.flush() // seek
        assertThat(p.run(frames(100, 2))).containsExactly(0, 0, 100, 101).inOrder()
    }

    @Test
    fun delayIsClamped() {
        val p = AudioDelayProcessor().apply {
            setDelayMs(1_000_000)
            configure(AudioProcessor.AudioFormat(1000, 1, C.ENCODING_PCM_16BIT))
            flush()
        }
        p.queueInput(ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()))
        assertThat(p.output.remaining()).isEqualTo((AudioDelayProcessor.MAX_DELAY_MS * 2).toInt())
    }

    @Test
    fun nonPcmFormatsAreRejected() {
        val result = runCatching {
            AudioDelayProcessor().configure(AudioProcessor.AudioFormat(48000, 6, C.ENCODING_AC3))
        }
        assertThat(result.exceptionOrNull()).isInstanceOf(AudioProcessor.UnhandledAudioFormatException::class.java)
    }

    // endregion

    @Test
    fun hdrIsDetectedFromTheVideoFormat() {
        fun format(mime: String, transfer: Int?): Format {
            val builder = Format.Builder().setSampleMimeType(mime)
            if (transfer != null) builder.setColorInfo(ColorInfo.Builder().setColorTransfer(transfer).build())
            return builder.build()
        }
        assertThat(Media3PlayerEngine.hdrTypeOf(format(MimeTypes.VIDEO_H265, C.COLOR_TRANSFER_ST2084))).isEqualTo(HdrType.HDR10)
        assertThat(Media3PlayerEngine.hdrTypeOf(format(MimeTypes.VIDEO_H265, C.COLOR_TRANSFER_HLG))).isEqualTo(HdrType.HLG)
        assertThat(Media3PlayerEngine.hdrTypeOf(format(MimeTypes.VIDEO_DOLBY_VISION, null))).isEqualTo(HdrType.DOLBY_VISION)
        assertThat(Media3PlayerEngine.hdrTypeOf(format(MimeTypes.VIDEO_H264, null))).isEqualTo(HdrType.NONE)
        assertThat(Media3PlayerEngine.hdrTypeOf(null)).isEqualTo(HdrType.NONE)
    }

    @Test
    fun decoderNamesClassifyHardwareSoftwareAndDolbyVision() {
        assertThat(Media3PlayerEngine.isSoftwareDecoder("OMX.google.h264.decoder")).isTrue()
        assertThat(Media3PlayerEngine.isSoftwareDecoder("c2.android.hevc.decoder")).isTrue()
        assertThat(Media3PlayerEngine.isSoftwareDecoder("ffmpeg")).isTrue()
        assertThat(Media3PlayerEngine.isSoftwareDecoder("c2.qti.hevc.decoder")).isFalse()
        assertThat(Media3PlayerEngine.isSoftwareDecoder("OMX.qcom.video.decoder.avc")).isFalse()

        assertThat(Media3PlayerEngine.isDolbyVisionDecoder("c2.dolby.decoder.hevc")).isTrue()
        assertThat(Media3PlayerEngine.isDolbyVisionDecoder("OMX.qcom.video.decoder.dolbyvision")).isTrue()
        assertThat(Media3PlayerEngine.isDolbyVisionDecoder("c2.qti.hevc.decoder")).isFalse()
    }

    @Test
    fun codecNamesAreReadable() {
        assertThat(CodecNames.audio("audio/eac3-joc")).isEqualTo("E-AC3 JOC (Atmos)")
        assertThat(CodecNames.audio("audio/true-hd")).isEqualTo("TrueHD")
        assertThat(CodecNames.audio("audio/vnd.dts.hd")).isEqualTo("DTS-HD MA")
        assertThat(CodecNames.audio("audio/vnd.dts.hd", "dtsx")).isEqualTo("DTS:X")
        assertThat(CodecNames.audio("audio/mp4a-latm")).isEqualTo("AAC")
        assertThat(CodecNames.audio(null)).isNull()
        assertThat(CodecNames.video("video/hevc")).isEqualTo("HEVC")
        assertThat(CodecNames.subtitle("application/pgs")).isEqualTo("PGS")
        assertThat(CodecNames.isImageSubtitle("application/vobsub")).isTrue()
        assertThat(CodecNames.isImageSubtitle("text/x-ssa")).isFalse()
    }

    @Test
    fun errorsAreMappedToActionableMessages() {
        val missing = PlaybackErrorMapper.map(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, "x")
        assertThat(missing.kind).isEqualTo(PlaybackErrorKind.FILE_MISSING)
        assertThat(missing.suggestion).isNotEmpty()
        assertThat(PlaybackErrorMapper.map(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, null).kind)
            .isEqualTo(PlaybackErrorKind.UNSUPPORTED_FORMAT)
        assertThat(PlaybackErrorMapper.map(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, null).kind)
            .isEqualTo(PlaybackErrorKind.CORRUPT_FILE)
        assertThat(PlaybackErrorMapper.map(PlaybackException.ERROR_CODE_IO_NO_PERMISSION, null).kind)
            .isEqualTo(PlaybackErrorKind.NO_PERMISSION)
        assertThat(PlaybackErrorMapper.map(PlaybackException.ERROR_CODE_UNSPECIFIED, "boom").technicalDetail).isEqualTo("boom")
    }

    @Test
    fun equalizerPresetsShapeTheSpectrum() {
        assertThat(EqualizerPresets.gainMillibels(EqualizerPreset.FLAT, 1_000)).isEqualTo(0)
        assertThat(EqualizerPresets.gainMillibels(EqualizerPreset.BASS_BOOST, 60)).isGreaterThan(0)
        assertThat(EqualizerPresets.gainMillibels(EqualizerPreset.BASS_BOOST, 8_000)).isEqualTo(0)
        assertThat(EqualizerPresets.gainMillibels(EqualizerPreset.TREBLE_BOOST, 14_000)).isGreaterThan(0)
        assertThat(EqualizerPresets.gainMillibels(EqualizerPreset.VOICE, 2_000)).isGreaterThan(0)
        assertThat(EqualizerPresets.gainMillibels(EqualizerPreset.VOICE, 60)).isLessThan(0)
    }
}

class DolbyVisionProfilesTest {

    @org.junit.Test
    fun theProfileIsReadFromTheCodecString() {
        val p = com.lecteur.core.player.engine.DolbyVisionProfiles
        com.google.common.truth.Truth.assertThat(p.profileOf("dvhe.07.06")).isEqualTo(7)
        com.google.common.truth.Truth.assertThat(p.profileOf("dvh1.08.07")).isEqualTo(8)
        com.google.common.truth.Truth.assertThat(p.profileOf("dvhe.05.06")).isEqualTo(5)
        com.google.common.truth.Truth.assertThat(p.profileOf("dav1.10.09")).isEqualTo(10)
        com.google.common.truth.Truth.assertThat(p.profileOf("hvc1.2.4.L153.B0")).isNull()
        com.google.common.truth.Truth.assertThat(p.profileOf(null)).isNull()
    }

    @org.junit.Test
    fun onlyProfilesWithoutACompatibleBaseLayerNeedTheDolbyDecoder() {
        val p = com.lecteur.core.player.engine.DolbyVisionProfiles
        listOf(4, 7, 8, 9).forEach { com.google.common.truth.Truth.assertThat(p.usesBaseLayer(it)).isTrue() }
        listOf(5, 10).forEach { com.google.common.truth.Truth.assertThat(p.usesBaseLayer(it)).isFalse() }
        com.google.common.truth.Truth.assertThat(p.usesBaseLayer(null)).isFalse() // unknown profile: leave the choice to ExoPlayer
    }
}

class DolbyVisionFallbackTest {

    private val p = com.lecteur.core.player.engine.DolbyVisionProfiles

    @org.junit.Test
    fun withoutAnyDolbyDecoderEveryProfileIsPlayedAsItsBaseLayer() {
        listOf("dvhe.05.06", "dvhe.07.06", "dvh1.08.07", "dav1.10.09").forEach {
            com.google.common.truth.Truth.assertThat(p.shouldPlayAsBaseLayer(it, deviceHasDolbyDecoder = false)).isTrue()
        }
    }

    @org.junit.Test
    fun withADolbyDecoderOnlyProfilesWithAUsableBaseLayerAreRedirected() {
        com.google.common.truth.Truth.assertThat(p.shouldPlayAsBaseLayer("dvhe.07.06", true)).isTrue()
        com.google.common.truth.Truth.assertThat(p.shouldPlayAsBaseLayer("dvh1.08.07", true)).isTrue()
        com.google.common.truth.Truth.assertThat(p.shouldPlayAsBaseLayer("dvhe.05.06", true)).isFalse()
        com.google.common.truth.Truth.assertThat(p.shouldPlayAsBaseLayer("dav1.10.09", true)).isFalse()
    }

    @org.junit.Test
    fun theBaseLayerMimeFollowsTheCodecFamily() {
        com.google.common.truth.Truth.assertThat(p.baseLayerMime("dvhe.05.06")).isEqualTo("video/hevc")
        com.google.common.truth.Truth.assertThat(p.baseLayerMime("dvh1.08.07")).isEqualTo("video/hevc")
        com.google.common.truth.Truth.assertThat(p.baseLayerMime("dvav.09.03")).isEqualTo("video/avc")
        com.google.common.truth.Truth.assertThat(p.baseLayerMime("dav1.10.09")).isEqualTo("video/av01")
        com.google.common.truth.Truth.assertThat(p.baseLayerMime(null)).isEqualTo("video/hevc")
    }
}
