package com.lecteur.core.data

import com.google.common.truth.Truth.assertThat
import com.lecteur.core.data.library.TreeOverlap
import com.lecteur.core.data.scan.CodecMapper
import com.lecteur.core.data.scan.ContainerNames
import com.lecteur.core.data.scan.DocumentsContractWalker
import com.lecteur.core.data.scan.ExtractorFacts
import com.lecteur.core.data.scan.HdrClassifier
import com.lecteur.core.data.scan.LinkPolicy
import com.lecteur.core.data.scan.LinkTarget
import com.lecteur.core.data.scan.SidecarResolver
import com.lecteur.core.data.scan.TechnicalInfoBuilder
import com.lecteur.core.data.scan.WalkedEntry
import com.lecteur.core.data.scan.WalkResult
import com.lecteur.core.data.scan.WalkedDirectory
import com.lecteur.core.common.parser.FilenameParser
import com.lecteur.core.model.AudioCodec
import com.lecteur.core.model.HdrType
import com.lecteur.core.model.MediaCategory
import com.lecteur.core.model.VideoCodec
import com.lecteur.core.player.probe.ProbeAudioStream
import com.lecteur.core.player.probe.ProbeResult
import com.lecteur.core.player.probe.ProbeSubtitleStream
import org.junit.Test

class CodecMapperTest {

    @Test
    fun videoCodecsMapFromFfmpegNames() {
        assertThat(CodecMapper.video("h264")).isEqualTo(VideoCodec.H264)
        assertThat(CodecMapper.video("HEVC")).isEqualTo(VideoCodec.HEVC)
        assertThat(CodecMapper.video("mpeg4")).isEqualTo(VideoCodec.MPEG4)
        assertThat(CodecMapper.video("mpeg2video")).isEqualTo(VideoCodec.MPEG2)
        assertThat(CodecMapper.video("wmv3")).isEqualTo(VideoCodec.VC1)
        assertThat(CodecMapper.video("av1")).isEqualTo(VideoCodec.AV1)
        assertThat(CodecMapper.video("prores")).isEqualTo(VideoCodec.UNKNOWN)
        assertThat(CodecMapper.video(null)).isEqualTo(VideoCodec.UNKNOWN)
    }

    @Test
    fun audioCodecsMapAndTitlesRefineTheCoreCodecs() {
        assertThat(CodecMapper.audio("ac3", null)).isEqualTo(AudioCodec.AC3)
        assertThat(CodecMapper.audio("pcm_s16le", null)).isEqualTo(AudioCodec.PCM)
        assertThat(CodecMapper.audio("truehd", "TrueHD Atmos 7.1")).isEqualTo(AudioCodec.TRUEHD)
        assertThat(CodecMapper.audio("eac3", "DD+ 5.1")).isEqualTo(AudioCodec.E_AC3)
        assertThat(CodecMapper.audio("eac3", "Dolby Atmos")).isEqualTo(AudioCodec.E_AC3_JOC)
        assertThat(CodecMapper.audio("dts", null)).isEqualTo(AudioCodec.DTS)
        assertThat(CodecMapper.audio("dts", "DTS-HD MA 7.1")).isEqualTo(AudioCodec.DTS_HD_MA)
        assertThat(CodecMapper.audio("dts", "DTS:X 7.1")).isEqualTo(AudioCodec.DTS_X)
        assertThat(CodecMapper.audio("weird", null)).isEqualTo(AudioCodec.UNKNOWN)
    }

    @Test
    fun subtitleFormatsKeepTheirWellKnownNames() {
        assertThat(CodecMapper.subtitle("subrip")).isEqualTo("SRT")
        assertThat(CodecMapper.subtitle("hdmv_pgs_subtitle")).isEqualTo("PGS")
        assertThat(CodecMapper.subtitle("dvd_subtitle")).isEqualTo("VOBSUB")
        assertThat(CodecMapper.subtitle("ass")).isEqualTo("ASS")
        assertThat(CodecMapper.subtitle("xsub")).isEqualTo("XSUB")
        assertThat(CodecMapper.subtitle(null)).isNull()
    }
}

class ContainerAndHdrTest {

    @Test
    fun sharedDemuxerNamesAreDisambiguatedByExtension() {
        assertThat(ContainerNames.of("matroska,webm", "mkv")).isEqualTo("MKV")
        assertThat(ContainerNames.of("matroska,webm", "webm")).isEqualTo("WEBM")
        assertThat(ContainerNames.of("mov,mp4,m4a,3gp,3g2,mj2", "mp4")).isEqualTo("MP4")
        assertThat(ContainerNames.of("mov,mp4,m4a,3gp,3g2,mj2", "mov")).isEqualTo("MOV")
        assertThat(ContainerNames.of("mpegts", "m2ts")).isEqualTo("M2TS")
        assertThat(ContainerNames.of("mpegts", "ts")).isEqualTo("TS")
        assertThat(ContainerNames.of("avi", "avi")).isEqualTo("AVI")
        assertThat(ContainerNames.of(null, "wmv")).isEqualTo("WMV")
        assertThat(ContainerNames.of(null, null)).isNull()
    }

    @Test
    fun extractorSignalsMapToHdrTypes() {
        assertThat(HdrClassifier.fromExtractor(true, "video/dolby-vision", null)).isEqualTo(HdrType.DOLBY_VISION)
        assertThat(HdrClassifier.fromExtractor(true, "video/hevc", HdrClassifier.TRANSFER_ST2084)).isEqualTo(HdrType.HDR10)
        assertThat(HdrClassifier.fromExtractor(true, "video/hevc", HdrClassifier.TRANSFER_HLG)).isEqualTo(HdrType.HLG)
        assertThat(HdrClassifier.fromExtractor(true, "video/avc", 3)).isEqualTo(HdrType.NONE)
        assertThat(HdrClassifier.fromExtractor(false, null, null)).isNull()
    }

    @Test
    fun theNameMayUpgradeHdr10ButNeverContradictTheFile() {
        assertThat(HdrClassifier.merge(HdrType.HDR10, HdrType.DOLBY_VISION)).isEqualTo(HdrType.DOLBY_VISION)
        assertThat(HdrClassifier.merge(HdrType.HDR10, HdrType.HDR10_PLUS)).isEqualTo(HdrType.HDR10_PLUS)
        assertThat(HdrClassifier.merge(HdrType.HDR10, HdrType.NONE)).isEqualTo(HdrType.HDR10)
        assertThat(HdrClassifier.merge(HdrType.NONE, HdrType.DOLBY_VISION)).isEqualTo(HdrType.NONE) // mislabelled SDR file
        assertThat(HdrClassifier.merge(HdrType.HLG, HdrType.HDR10)).isEqualTo(HdrType.HLG)
        assertThat(HdrClassifier.merge(null, HdrType.HDR10_PLUS)).isEqualTo(HdrType.HDR10_PLUS) // extractor could not look: trust the name
    }
}

class TechnicalInfoBuilderTest {

    private val probe = ProbeResult(
        chapters = emptyList(), width = 3840, height = 2160, videoCodecName = "hevc", audioStreamCount = 2, subtitleStreamCount = 1,
        format = "matroska,webm", rawDuration = 7_200_000, frameRate = 23.976,
        audioStreams = listOf(
            ProbeAudioStream(1, "eac3", "fre", "Atmos", 8, isDefault = true, isForced = false),
            ProbeAudioStream(2, "dts", "eng", null, 6, isDefault = false, isForced = false)
        ),
        subtitleStreams = listOf(ProbeSubtitleStream(3, "hdmv_pgs_subtitle", "fre", null, isDefault = false, isForced = true))
    )

    @Test
    fun probeAndExtractorAreCombined() {
        val info = TechnicalInfoBuilder.build(probe, ExtractorFacts(true, 7_200_500, "video/hevc", HdrClassifier.TRANSFER_ST2084), HdrType.DOLBY_VISION, "mkv")

        assertThat(info.durationMs).isEqualTo(7_200_500) // the extractor's, whose unit is known
        assertThat(info.container).isEqualTo("MKV")
        assertThat(info.videoCodec).isEqualTo(VideoCodec.HEVC)
        assertThat(info.width).isEqualTo(3840)
        assertThat(info.hdrType).isEqualTo(HdrType.DOLBY_VISION)
        assertThat(info.audioTracks.map { it.codec }).containsExactly(AudioCodec.E_AC3_JOC, AudioCodec.DTS).inOrder()
        assertThat(info.audioTracks[0].isDefault).isTrue()
        assertThat(info.subtitleTracks.single().codec).isEqualTo("PGS")
        assertThat(info.subtitleTracks.single().isForced).isTrue()
    }

    @Test
    fun durationFallsBackToTheProbeWhenTheExtractorFailed() {
        val info = TechnicalInfoBuilder.build(probe, ExtractorFacts(ran = false), HdrType.NONE, "mkv")
        assertThat(info.durationMs).isEqualTo(7_200_000)
        assertThat(info.hdrType).isEqualTo(HdrType.NONE)
    }

    @Test
    fun anExtractorOnlyResultStillGivesWhatItKnows() {
        val info = TechnicalInfoBuilder.build(null, ExtractorFacts(true, 90_000, "video/avc", null, 1280, 720), HdrType.NONE, "avi")
        assertThat(info.durationMs).isEqualTo(90_000)
        assertThat(info.width).isEqualTo(1280)
        assertThat(info.audioTracks).isEmpty()
        assertThat(info.container).isEqualTo("AVI")
    }
}

class SidecarAndPolicyTest {

    private fun entry(name: String) = WalkedEntry("content://x/$name", name, 1, 1)

    @Test
    fun movieSidecarsFollowTheVideoName() {
        val dir = listOf(entry("Film (2020).mkv"), entry("Film (2020).nfo"), entry("Film (2020)-poster.jpg"), entry("Film (2020)-fanart.jpg"))
        val refs = SidecarResolver.forMovie(dir, "Film (2020).mkv")
        assertThat(refs.nfo?.name).isEqualTo("Film (2020).nfo")
        assertThat(refs.poster?.name).isEqualTo("Film (2020)-poster.jpg")
        assertThat(refs.backdrop?.name).isEqualTo("Film (2020)-fanart.jpg")
    }

    @Test
    fun genericNamesApplyOnlyToAFolderWithASingleVideo() {
        val single = listOf(entry("Film.mkv"), entry("movie.nfo"), entry("poster.jpg"), entry("fanart.jpg"))
        val refs = SidecarResolver.forMovie(single, "Film.mkv")
        assertThat(refs.nfo?.name).isEqualTo("movie.nfo")
        assertThat(refs.poster?.name).isEqualTo("poster.jpg")
        assertThat(refs.backdrop?.name).isEqualTo("fanart.jpg")

        val shared = listOf(entry("A.mkv"), entry("B.mkv"), entry("movie.nfo"), entry("poster.jpg"))
        assertThat(SidecarResolver.forMovie(shared, "A.mkv")).isEqualTo(com.lecteur.core.data.scan.SidecarRefs.NONE)
    }

    @Test
    fun seriesSidecarsAreFoundInTheShowFolderAboveTheSeasonFolder() {
        val dirs = mapOf(
            "Show" to listOf(entry("tvshow.nfo"), entry("poster.jpg"), entry("fanart.jpg")),
            "Show/Saison 2" to listOf(entry("S02E01.mkv"))
        )
        val refs = SidecarResolver.forSeries(dirs, "Show/Saison 2")
        assertThat(refs.nfo?.name).isEqualTo("tvshow.nfo")
        assertThat(refs.poster?.name).isEqualTo("poster.jpg")
        assertThat(SidecarResolver.forSeries(dirs, "Show").backdrop?.name).isEqualTo("fanart.jpg")
    }

    @Test
    fun seasonFolderNames() {
        listOf("Season 1", "Saison 02", "S3", "Specials", "season_4").forEach { assertThat(SidecarResolver.isSeasonFolder(it)).isTrue() }
        listOf("Breaking Bad", "Seasonal Affective", "Movies").forEach { assertThat(SidecarResolver.isSeasonFolder(it)).isFalse() }
    }

    @Test
    fun categoryAndNameDecideWhatAFileBecomes() {
        val episode = FilenameParser.parse("Show.S01E02.mkv")
        val film = FilenameParser.parse("Blade.Runner.2049.2017.mkv")

        assertThat(LinkPolicy.decide(MediaCategory.SERIES, episode)).isEqualTo(LinkTarget.SERIES)
        assertThat(LinkPolicy.decide(MediaCategory.ANIME, episode)).isEqualTo(LinkTarget.SERIES)
        assertThat(LinkPolicy.decide(MediaCategory.GENERIC, episode)).isEqualTo(LinkTarget.SERIES)
        assertThat(LinkPolicy.decide(MediaCategory.GENERIC, film)).isEqualTo(LinkTarget.MOVIE)
        assertThat(LinkPolicy.decide(MediaCategory.MOVIES, episode)).isEqualTo(LinkTarget.MOVIE)
        assertThat(LinkPolicy.decide(MediaCategory.SERIES, film)).isEqualTo(LinkTarget.MOVIE)
        assertThat(LinkPolicy.decide(MediaCategory.PERSONAL, episode)).isEqualTo(LinkTarget.PERSONAL)
    }
}

class WalkAndTreeTest {

    @Test
    fun walkResultBuildsRelativePathsFromTheRootName() {
        val walk = WalkResult(
            true, true,
            listOf(
                WalkedDirectory("", listOf(WalkedEntry("u1", "a.mkv", 1, 1), WalkedEntry("u2", "a.srt", 1, 1))),
                WalkedDirectory("Show/Saison 1", listOf(WalkedEntry("u3", "e1.mp4", 2, 2)))
            ),
            "Series"
        )
        assertThat(walk.videoFiles.map { it.relativePath }).containsExactly("Series/a.mkv", "Series/Show/Saison 1/e1.mp4").inOrder()
        assertThat(walk.videoFiles[1].parentName).isEqualTo("Saison 1")
        assertThat(walk.videoFiles[1].grandparentName).isEqualTo("Show")
    }

    @Test
    fun hiddenAndSystemDirectoriesAreIgnored() {
        listOf(".thumbnails", "@eaDir", "\$RECYCLE.BIN", "lost+found").forEach { assertThat(DocumentsContractWalker.isIgnoredDirectory(it)).isTrue() }
        listOf("Movies", "Season 1").forEach { assertThat(DocumentsContractWalker.isIgnoredDirectory(it)).isFalse() }
    }

    private val authority = "content://com.android.externalstorage.documents/tree/"

    @Test
    fun nestedOrIdenticalTreesOverlap() {
        val movies = authority + "primary%3AMovies"
        assertThat(TreeOverlap.overlaps(movies, movies)).isTrue()
        assertThat(TreeOverlap.overlaps(movies, authority + "primary%3AMovies%2FAction")).isTrue()
        assertThat(TreeOverlap.overlaps(authority + "primary%3AMovies%2FAction", movies)).isTrue()
    }

    @Test
    fun siblingsAndOtherVolumesDoNotOverlap() {
        val movies = authority + "primary%3AMovies"
        assertThat(TreeOverlap.overlaps(movies, authority + "primary%3AMovies2")).isFalse()
        assertThat(TreeOverlap.overlaps(movies, authority + "1A2B-3C4D%3AMovies")).isFalse()
        assertThat(TreeOverlap.overlaps(movies, "content://other.provider/tree/primary%3AMovies")).isFalse()
    }
}

class DeepWalkTest {

    private fun dir(id: String, name: String) = com.lecteur.core.data.scan.TreeChild(id, name, true, 0, 0)
    private fun file(id: String, name: String) = com.lecteur.core.data.scan.TreeChild(id, name, false, 10, 1)

    @Test
    fun everyLevelDownToTheLeavesIsVisited() = kotlinx.coroutines.test.runTest {
        val tree = mapOf(
            "root" to listOf(file("f0", "a.mkv"), dir("d1", "Séries")),
            "d1" to listOf(dir("d2", "Show"), file("f1", "b.mkv")),
            "d2" to listOf(dir("d3", "Saison 1"), dir("hidden", ".thumbs")),
            "d3" to listOf(dir("d4", "Extras"), file("f2", "S01E01.mkv")),
            "d4" to listOf(file("f3", "deep.mp4"), file("f4", "notes.txt")),
            "hidden" to listOf(file("f5", "skipped.mkv"))
        )

        val walk = com.lecteur.core.data.scan.TreeTraversal.walk("root", "Media", { tree[it] }, { "uri:$it" })

        assertThat(walk.complete).isTrue()
        assertThat(walk.videoFiles.map { it.relativePath }).containsExactly(
            "Media/a.mkv", "Media/Séries/b.mkv", "Media/Séries/Show/Saison 1/S01E01.mkv", "Media/Séries/Show/Saison 1/Extras/deep.mp4"
        )
    }

    @Test
    fun anUnreadableSubfolderMakesTheWalkIncompleteButKeepsTheRest() = kotlinx.coroutines.test.runTest {
        val tree = mapOf("root" to listOf(dir("bad", "Broken"), file("f0", "a.mkv")))

        val walk = com.lecteur.core.data.scan.TreeTraversal.walk("root", "Media", { tree[it] }, { it })

        assertThat(walk.reachable).isTrue()
        assertThat(walk.complete).isFalse()
        assertThat(walk.videoFiles).hasSize(1)
    }

    @Test
    fun anUnreadableRootIsUnreachable() = kotlinx.coroutines.test.runTest {
        assertThat(com.lecteur.core.data.scan.TreeTraversal.walk("root", "Media", { null }, { it }).reachable).isFalse()
    }
}
