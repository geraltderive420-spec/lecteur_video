package com.lecteur.core.common.cast

import com.google.common.truth.Truth.assertThat
import com.lecteur.core.common.cast.compat.CastBlocker
import com.lecteur.core.common.cast.compat.CastCandidate
import com.lecteur.core.common.cast.compat.CastCompatibility
import com.lecteur.core.common.cast.compat.CastDeviceProfile
import com.lecteur.core.common.cast.compat.CastVerdict
import com.lecteur.core.model.AudioCodec
import com.lecteur.core.model.HdrType
import com.lecteur.core.model.VideoCodec
import org.junit.Test

class CastCompatibilityTest {
    private val ok = CastCandidate("MP4", VideoCodec.H264, AudioCodec.AAC, 1080)

    private fun blockers(c: CastCandidate, d: CastDeviceProfile = CastDeviceProfile.CONSERVATIVE) =
        (CastCompatibility.check(c, d) as? CastVerdict.Incompatible)?.issues?.map { it.blocker }

    @Test fun `h264 aac mp4 1080p plays`() {
        assertThat(CastCompatibility.check(ok)).isEqualTo(CastVerdict.Compatible())
    }

    @Test fun `mkv is refused even with friendly codecs`() {
        assertThat(blockers(ok.copy(container = "MKV"))).containsExactly(CastBlocker.CONTAINER)
    }

    @Test fun `avi, ts and unknown containers are refused`() {
        for (c in listOf("AVI", "TS", "M2TS", "WMV", null)) {
            assertThat(blockers(ok.copy(container = c))).containsExactly(CastBlocker.CONTAINER)
        }
    }

    @Test fun `container check ignores case and accepts webm, mov, m4v`() {
        for (c in listOf("mp4", "WEBM", "Mov", "m4v")) assertThat(blockers(ok.copy(container = c))).isNull()
    }

    @Test fun `dts and truehd are refused, ac3 and eac3 are not`() {
        for (a in listOf(AudioCodec.DTS, AudioCodec.DTS_HD_MA, AudioCodec.DTS_X, AudioCodec.TRUEHD, AudioCodec.E_AC3_JOC, AudioCodec.PCM)) {
            assertThat(blockers(ok.copy(audioCodec = a))).containsExactly(CastBlocker.AUDIO_CODEC)
        }
        for (a in listOf(AudioCodec.AC3, AudioCodec.E_AC3, AudioCodec.FLAC, AudioCodec.OPUS, AudioCodec.MP3, AudioCodec.VORBIS)) {
            assertThat(blockers(ok.copy(audioCodec = a))).isNull()
        }
    }

    @Test fun `file without audio info is not refused for audio`() {
        assertThat(blockers(ok.copy(audioCodec = null))).isNull()
    }

    @Test fun `hevc needs a device that decodes it`() {
        val hevc = ok.copy(videoCodec = VideoCodec.HEVC)
        assertThat(blockers(hevc)).containsExactly(CastBlocker.VIDEO_CODEC)
        assertThat(blockers(hevc, CastDeviceProfile.ULTRA)).isNull()
    }

    @Test fun `av1, mpeg2 and unknown video are refused everywhere`() {
        for (v in listOf(VideoCodec.AV1, VideoCodec.MPEG2, VideoCodec.MPEG4, VideoCodec.VC1, VideoCodec.UNKNOWN)) {
            assertThat(blockers(ok.copy(videoCodec = v), CastDeviceProfile.ULTRA)).containsExactly(CastBlocker.VIDEO_CODEC)
        }
    }

    @Test fun `4k needs a device with 4k output`() {
        val uhd = ok.copy(height = 2160)
        assertThat(blockers(uhd)).containsExactly(CastBlocker.RESOLUTION)
        assertThat(blockers(uhd, CastDeviceProfile.ULTRA)).isNull()
    }

    @Test fun `every reason is reported at once`() {
        val bad = CastCandidate("MKV", VideoCodec.HEVC, AudioCodec.DTS, 2160)
        assertThat(blockers(bad)).containsExactly(
            CastBlocker.CONTAINER, CastBlocker.VIDEO_CODEC, CastBlocker.AUDIO_CODEC, CastBlocker.RESOLUTION
        )
    }

    @Test fun `hdr on an sdr chromecast is a note, not a refusal`() {
        val verdict = CastCompatibility.check(ok.copy(hdrType = HdrType.HDR10)) as CastVerdict.Compatible
        assertThat(verdict.notes.single()).contains("HDR10")
        assertThat((CastCompatibility.check(ok.copy(hdrType = HdrType.HDR10), CastDeviceProfile.ULTRA) as CastVerdict.Compatible).notes).isEmpty()
    }

    @Test fun `refusal message names the reasons and points to companion mode`() {
        val verdict = CastCompatibility.check(CastCandidate("MKV", VideoCodec.H264, AudioCodec.DTS, 1080)) as CastVerdict.Incompatible
        assertThat(verdict.message).contains("conteneur MKV")
        assertThat(verdict.message).contains("DTS")
        assertThat(verdict.message).contains("mode compagnon")
    }
}
