package com.lecteur.core.common.parser

import com.google.common.truth.Truth.assertThat
import com.lecteur.core.model.HdrType
import org.junit.Test

class FilenameParserTest {

    // --- 1. Standard SxxExx Formats ---
    @Test
    fun testStandardSxxExx() {
        val result = FilenameParser.parse("Breaking.Bad.S01E02.1080p.mkv")
        assertThat(result.cleanTitle).isEqualTo("Breaking Bad")
        assertThat(result.seasonNumber).isEqualTo(1)
        assertThat(result.episodeNumbers).containsExactly(2)
        assertThat(result.resolution).isEqualTo("1080P")
        assertThat(result.extension).isEqualTo("mkv")
    }

    @Test
    fun testStandardWithReleaseGroupAndDV() {
        val result = FilenameParser.parse("Stranger.Things.S04E09.2160p.NF.WEB-DL.DDP5.1.Atmos.DV.HEVC-FLUX.mkv")
        assertThat(result.cleanTitle).isEqualTo("Stranger Things")
        assertThat(result.seasonNumber).isEqualTo(4)
        assertThat(result.episodeNumbers).containsExactly(9)
        assertThat(result.resolution).isEqualTo("2160P")
        assertThat(result.hdrType).isEqualTo(HdrType.DOLBY_VISION)
        assertThat(result.releaseGroup).isEqualTo("FLUX")
    }

    @Test
    fun testLowercaseSxxExx() {
        val result = FilenameParser.parse("the.bear.s02e06.720p.webrip.mkv")
        assertThat(result.cleanTitle).isEqualTo("the bear")
        assertThat(result.seasonNumber).isEqualTo(2)
        assertThat(result.episodeNumbers).containsExactly(6)
    }

    @Test
    fun testSxxExxWithHyphens() {
        val result = FilenameParser.parse("Game-of-Thrones-S08E03-1080p.mkv")
        assertThat(result.cleanTitle).isEqualTo("Game of Thrones")
        assertThat(result.seasonNumber).isEqualTo(8)
        assertThat(result.episodeNumbers).containsExactly(3)
    }

    @Test
    fun testSxxExxWithSpaces() {
        val result = FilenameParser.parse("Severance S01E01 1080p WEBRip x265.mkv")
        assertThat(result.cleanTitle).isEqualTo("Severance")
        assertThat(result.seasonNumber).isEqualTo(1)
        assertThat(result.episodeNumbers).containsExactly(1)
        assertThat(result.videoCodec).isEqualTo("X265")
    }

    // --- 2. Multi-episode formats ---
    @Test
    fun testDoubleEpisodeStandard() {
        val result = FilenameParser.parse("Friends.S01E01E02.720p.BluRay.mkv")
        assertThat(result.cleanTitle).isEqualTo("Friends")
        assertThat(result.seasonNumber).isEqualTo(1)
        assertThat(result.episodeNumbers).containsExactly(1, 2).inOrder()
    }

    @Test
    fun testMultiEpisodeRangeWithE() {
        val result = FilenameParser.parse("The.Office.US.S03E01-E02.1080p.mkv")
        assertThat(result.cleanTitle).isEqualTo("The Office US")
        assertThat(result.seasonNumber).isEqualTo(3)
        assertThat(result.episodeNumbers).containsExactly(1, 2).inOrder()
    }

    @Test
    fun testMultiEpisodeRangeShort() {
        val result = FilenameParser.parse("Lost.S01E23-24.HDTV.mkv")
        assertThat(result.cleanTitle).isEqualTo("Lost")
        assertThat(result.seasonNumber).isEqualTo(1)
        assertThat(result.episodeNumbers).containsExactly(23, 24).inOrder()
    }

    @Test
    fun testTripleEpisode() {
        val result = FilenameParser.parse("Doctor.Who.S04E11E12E13.720p.mkv")
        assertThat(result.cleanTitle).isEqualTo("Doctor Who")
        assertThat(result.seasonNumber).isEqualTo(4)
        assertThat(result.episodeNumbers).containsExactly(11, 12, 13).inOrder()
    }

    // --- 3. Alternate SxEE formats ---
    @Test
    fun testAlternate1x02() {
        val result = FilenameParser.parse("Lost.1x02.HDTV.avi")
        assertThat(result.cleanTitle).isEqualTo("Lost")
        assertThat(result.seasonNumber).isEqualTo(1)
        assertThat(result.episodeNumbers).containsExactly(2)
        assertThat(result.extension).isEqualTo("avi")
    }

    @Test
    fun testAlternate01x05() {
        val result = FilenameParser.parse("The.Wire.01x05.DVDRip.xvid.avi")
        assertThat(result.cleanTitle).isEqualTo("The Wire")
        assertThat(result.seasonNumber).isEqualTo(1)
        assertThat(result.episodeNumbers).containsExactly(5)
    }

    @Test
    fun testAlternateMultiEpisode() {
        val result = FilenameParser.parse("Battlestar.Galactica.02x01-02.mkv")
        assertThat(result.cleanTitle).isEqualTo("Battlestar Galactica")
        assertThat(result.seasonNumber).isEqualTo(2)
        assertThat(result.episodeNumbers).containsExactly(1, 2)
    }

    // --- 4. French formats ---
    @Test
    fun testFrenchSaisonEpisode() {
        val result = FilenameParser.parse("Kaamelott Saison 1 Episode 2.mp4")
        assertThat(result.cleanTitle).isEqualTo("Kaamelott")
        assertThat(result.seasonNumber).isEqualTo(1)
        assertThat(result.episodeNumbers).containsExactly(2)
    }

    @Test
    fun testFrenchWithDots() {
        val result = FilenameParser.parse("Le.Bureau.des.Legendes.Saison.03.Episode.08.1080p.mkv")
        assertThat(result.cleanTitle).isEqualTo("Le Bureau des Legendes")
        assertThat(result.seasonNumber).isEqualTo(3)
        assertThat(result.episodeNumbers).containsExactly(8)
    }

    @Test
    fun testFrenchShortS01Episode() {
        val result = FilenameParser.parse("Engrenages S05 Episode 04 720p.mkv")
        assertThat(result.cleanTitle).isEqualTo("Engrenages")
        assertThat(result.seasonNumber).isEqualTo(5)
        assertThat(result.episodeNumbers).containsExactly(4)
    }

    // --- 5. Standalone episode notations (EP02, E02, Ep.02) ---
    @Test
    fun testStandaloneEP02() {
        val result = FilenameParser.parse("Attack on Titan EP02 1080p.mkv")
        assertThat(result.cleanTitle).isEqualTo("Attack on Titan")
        assertThat(result.seasonNumber).isEqualTo(1)
        assertThat(result.episodeNumbers).containsExactly(2)
    }

    @Test
    fun testStandaloneE02() {
        val result = FilenameParser.parse("Vinland.Saga.E05.720p.mkv")
        assertThat(result.cleanTitle).isEqualTo("Vinland Saga")
        assertThat(result.seasonNumber).isEqualTo(1)
        assertThat(result.episodeNumbers).containsExactly(5)
    }

    @Test
    fun testStandaloneEpWithDot() {
        val result = FilenameParser.parse("Sousou no Frieren Ep. 12 1080p.mkv")
        assertThat(result.cleanTitle).isEqualTo("Sousou no Frieren")
        assertThat(result.seasonNumber).isEqualTo(1)
        assertThat(result.episodeNumbers).containsExactly(12)
    }

    // --- 6. Anime Absolute Numbering ---
    @Test
    fun testAnimeAbsoluteSubsPlease() {
        val result = FilenameParser.parse("[SubsPlease] Frieren - 05 [1080p].mkv")
        assertThat(result.cleanTitle).isEqualTo("Frieren")
        assertThat(result.episodeNumbers).containsExactly(5)
        assertThat(result.absoluteEpisodeNumber).isEqualTo(5)
        assertThat(result.releaseGroup).isEqualTo("SubsPlease")
    }

    @Test
    fun testAnimeAbsoluteEraiRaws() {
        val result = FilenameParser.parse("[Erai-raws] Jujutsu Kaisen - 012 [1080p][Multiple Subtitle].mkv")
        assertThat(result.cleanTitle).isEqualTo("Jujutsu Kaisen")
        assertThat(result.absoluteEpisodeNumber).isEqualTo(12)
        assertThat(result.releaseGroup).isEqualTo("Erai-raws")
    }

    @Test
    fun testAnimeAbsoluteWithoutGroup() {
        val result = FilenameParser.parse("Naruto Shippuden - 250.mp4")
        assertThat(result.cleanTitle).isEqualTo("Naruto Shippuden")
        assertThat(result.episodeNumbers).containsExactly(250)
    }

    @Test
    fun testAnimeThreeDigitEpisode() {
        val result = FilenameParser.parse("[HorribleSubs] One Piece - 892 [720p].mkv")
        assertThat(result.cleanTitle).isEqualTo("One Piece")
        assertThat(result.absoluteEpisodeNumber).isEqualTo(892)
    }

    // --- 7. Specials (S00E01, SP01, OVA) ---
    @Test
    fun testSpecialS00E01() {
        val result = FilenameParser.parse("Sherlock.S00E01.The.Abominable.Bride.1080p.mkv")
        assertThat(result.seasonNumber).isEqualTo(0)
        assertThat(result.episodeNumbers).containsExactly(1)
        assertThat(result.isSpecial).isTrue()
    }

    @Test
    fun testSpecialSP01() {
        val result = FilenameParser.parse("Demon Slayer SP01 1080p.mkv")
        assertThat(result.seasonNumber).isEqualTo(0)
        assertThat(result.episodeNumbers).containsExactly(1)
        assertThat(result.isSpecial).isTrue()
    }

    @Test
    fun testSpecialOVA() {
        val result = FilenameParser.parse("Fullmetal Alchemist OVA 02 720p.mkv")
        assertThat(result.seasonNumber).isEqualTo(0)
        assertThat(result.episodeNumbers).containsExactly(2)
        assertThat(result.isSpecial).isTrue()
    }

    // --- 8. Date-based episodes ---
    @Test
    fun testDateEpisodeDot() {
        val result = FilenameParser.parse("The.Daily.Show.2024.03.15.1080p.mkv")
        assertThat(result.cleanTitle).isEqualTo("The Daily Show")
        assertThat(result.seasonNumber).isEqualTo(2024)
        assertThat(result.episodeNumbers).containsExactly(315)
    }

    @Test
    fun testDateEpisodeDash() {
        val result = FilenameParser.parse("Last Week Tonight - 2023-11-20 - 1080p.mp4")
        assertThat(result.cleanTitle).isEqualTo("Last Week Tonight")
        assertThat(result.seasonNumber).isEqualTo(2023)
        assertThat(result.episodeNumbers).containsExactly(1120)
    }

    // --- 9. Parent Directory Hints ---
    @Test
    fun testParentDirectorySeasonHint() {
        val result = FilenameParser.parse(
            filename = "05.mkv",
            parentDirectoryName = "Saison 2",
            grandparentDirectoryName = "Breaking Bad"
        )
        assertThat(result.cleanTitle).isEqualTo("Breaking Bad")
        assertThat(result.seasonNumber).isEqualTo(2)
        assertThat(result.episodeNumbers).containsExactly(5)
    }

    @Test
    fun testParentDirectoryShortS02() {
        val result = FilenameParser.parse(
            filename = "Episode 03.mp4",
            parentDirectoryName = "S02",
            grandparentDirectoryName = "Succession"
        )
        assertThat(result.cleanTitle).isEqualTo("Succession")
        assertThat(result.seasonNumber).isEqualTo(2)
        assertThat(result.episodeNumbers).containsExactly(3)
    }

    @Test
    fun testParentDirectorySeasonFolderOnly() {
        val result = FilenameParser.parse(
            filename = "12 - The Finale.mkv",
            parentDirectoryName = "Season 4"
        )
        assertThat(result.seasonNumber).isEqualTo(4)
        assertThat(result.episodeNumbers).containsExactly(12)
    }

    // --- 10. Tricky Titles (Numbers, Years in Titles) ---
    @Test
    fun testTricky1917WithYear() {
        val result = FilenameParser.parse("1917 (2019) 1080p.mkv")
        assertThat(result.cleanTitle).isEqualTo("1917")
        assertThat(result.year).isEqualTo(2019)
        assertThat(result.resolution).isEqualTo("1080P")
    }

    @Test
    fun testTricky1917Dotted() {
        val result = FilenameParser.parse("1917.2019.1080p.BluRay.x264-SPARKS.mkv")
        assertThat(result.cleanTitle).isEqualTo("1917")
        assertThat(result.year).isEqualTo(2019)
        assertThat(result.releaseGroup).isEqualTo("SPARKS")
    }

    @Test
    fun testTricky1917Alone() {
        val result = FilenameParser.parse("1917.1080p.mkv")
        assertThat(result.cleanTitle).isEqualTo("1917")
    }

    @Test
    fun testTricky2001ASpaceOdysseyBracketed() {
        val result = FilenameParser.parse("2001 A Space Odyssey (1968).mkv")
        assertThat(result.cleanTitle).isEqualTo("2001 A Space Odyssey")
        assertThat(result.year).isEqualTo(1968)
    }

    @Test
    fun testTricky2001ASpaceOdysseyDotted() {
        val result = FilenameParser.parse("2001.A.Space.Odyssey.1968.720p.mkv")
        assertThat(result.cleanTitle).isEqualTo("2001 A Space Odyssey")
        assertThat(result.year).isEqualTo(1968)
    }

    @Test
    fun testTrickyBladeRunner2049WithYear() {
        val result = FilenameParser.parse("Blade Runner 2049 (2017).mkv")
        assertThat(result.cleanTitle).isEqualTo("Blade Runner 2049")
        assertThat(result.year).isEqualTo(2017)
    }

    @Test
    fun testTrickyBladeRunner2049Dotted() {
        val result = FilenameParser.parse("Blade.Runner.2049.2017.2160p.UHD.mkv")
        assertThat(result.cleanTitle).isEqualTo("Blade Runner 2049")
        assertThat(result.year).isEqualTo(2017)
        assertThat(result.resolution).isEqualTo("2160P")
    }

    @Test
    fun testTrickyBladeRunner2049WithoutReleaseYear() {
        val result = FilenameParser.parse("Blade.Runner.2049.1080p.mkv")
        assertThat(result.cleanTitle).isEqualTo("Blade Runner 2049")
    }

    @Test
    fun testTrickyCatch22() {
        val result = FilenameParser.parse("Catch-22 S01E01 1080p.mkv")
        assertThat(result.cleanTitle).isEqualTo("Catch-22")
        assertThat(result.seasonNumber).isEqualTo(1)
        assertThat(result.episodeNumbers).containsExactly(1)
    }

    @Test
    fun testTricky300() {
        val result = FilenameParser.parse("300 (2006) 1080p BluRay.mkv")
        assertThat(result.cleanTitle).isEqualTo("300")
        assertThat(result.year).isEqualTo(2006)
    }

    @Test
    fun testTrickyDistrict9() {
        val result = FilenameParser.parse("District 9 (2009) 720p.mkv")
        assertThat(result.cleanTitle).isEqualTo("District 9")
        assertThat(result.year).isEqualTo(2009)
    }

    @Test
    fun testTrickyApollo13() {
        val result = FilenameParser.parse("Apollo 13 (1995) 1080p.mkv")
        assertThat(result.cleanTitle).isEqualTo("Apollo 13")
        assertThat(result.year).isEqualTo(1995)
    }

    @Test
    fun testTrickySe7en() {
        val result = FilenameParser.parse("Se7en.1995.1080p.mkv")
        assertThat(result.cleanTitle).isEqualTo("Se7en")
        assertThat(result.year).isEqualTo(1995)
    }

    @Test
    fun testTrickyOceans11() {
        val result = FilenameParser.parse("Ocean's.11.2001.1080p.mkv")
        assertThat(result.cleanTitle).isEqualTo("Ocean's 11")
        assertThat(result.year).isEqualTo(2001)
    }

    @Test
    fun testTricky12Monkeys() {
        val result = FilenameParser.parse("12.Monkeys.1995.1080p.mkv")
        assertThat(result.cleanTitle).isEqualTo("12 Monkeys")
        assertThat(result.year).isEqualTo(1995)
    }

    @Test
    fun testTricky1984Double() {
        val result = FilenameParser.parse("1984.1984.1080p.mkv")
        assertThat(result.cleanTitle).isEqualTo("1984")
        assertThat(result.year).isEqualTo(1984)
    }

    @Test
    fun testTricky2012Movie() {
        val result = FilenameParser.parse("2012.2009.1080p.mkv")
        assertThat(result.cleanTitle).isEqualTo("2012")
        assertThat(result.year).isEqualTo(2009)
    }

    @Test
    fun testTricky9Movie() {
        val result = FilenameParser.parse("9 (2009) 1080p.mkv")
        assertThat(result.cleanTitle).isEqualTo("9")
        assertThat(result.year).isEqualTo(2009)
    }

    // --- 11. Movie Titles & Tech Specs ---
    @Test
    fun testInceptionStandard() {
        val result = FilenameParser.parse("Inception.2010.1080p.BluRay.x264.DTS-WiKi.mkv")
        assertThat(result.cleanTitle).isEqualTo("Inception")
        assertThat(result.year).isEqualTo(2010)
        assertThat(result.resolution).isEqualTo("1080P")
        assertThat(result.videoCodec).isEqualTo("X264")
        assertThat(result.audioCodec).isEqualTo("DTS")
        assertThat(result.releaseGroup).isEqualTo("WiKi")
    }

    @Test
    fun testInterstellarIMAX() {
        val result = FilenameParser.parse("Interstellar.2014.IMAX.2160p.UHD.HDR.x265.TrueHD.7.1.Atmos.mkv")
        assertThat(result.cleanTitle).isEqualTo("Interstellar")
        assertThat(result.year).isEqualTo(2014)
        assertThat(result.resolution).isEqualTo("2160P")
        assertThat(result.hdrType).isEqualTo(HdrType.HDR10)
        assertThat(result.videoCodec).isEqualTo("X265")
    }

    @Test
    fun testTheMatrix4KDV() {
        val result = FilenameParser.parse("The.Matrix.1999.2160p.UHD.BluRay.x265.DV.HDR10.TrueHD.Atmos-TERMiNAL.mkv")
        assertThat(result.cleanTitle).isEqualTo("The Matrix")
        assertThat(result.year).isEqualTo(1999)
        assertThat(result.hdrType).isEqualTo(HdrType.DOLBY_VISION)
        assertThat(result.releaseGroup).isEqualTo("TERMiNAL")
    }

    @Test
    fun testHDR10PlusTag() {
        val result = FilenameParser.parse("Dune.Part.Two.2024.2160p.HDR10Plus.HEVC.mkv")
        assertThat(result.cleanTitle).isEqualTo("Dune Part Two")
        assertThat(result.year).isEqualTo(2024)
        assertThat(result.hdrType).isEqualTo(HdrType.HDR10_PLUS)
    }

    @Test
    fun testHLGTag() {
        val result = FilenameParser.parse("Planet.Earth.III.S01E01.2160p.HLG.mkv")
        assertThat(result.cleanTitle).isEqualTo("Planet Earth III")
        assertThat(result.hdrType).isEqualTo(HdrType.HLG)
    }

    @Test
    fun testAV1Codec() {
        val result = FilenameParser.parse("Oppenheimer.2023.1080p.AV1.Opus.mkv")
        assertThat(result.cleanTitle).isEqualTo("Oppenheimer")
        assertThat(result.year).isEqualTo(2023)
        assertThat(result.videoCodec).isEqualTo("AV1")
    }

    @Test
    fun testVP9Codec() {
        val result = FilenameParser.parse("Big.Buck.Bunny.1080p.VP9.Opus.webm")
        assertThat(result.cleanTitle).isEqualTo("Big Buck Bunny")
        assertThat(result.videoCodec).isEqualTo("VP9")
        assertThat(result.extension).isEqualTo("webm")
    }

    @Test
    fun testDTSHDMAAudio() {
        val result = FilenameParser.parse("Gladiator.2000.1080p.DTS-HD.MA.x264.mkv")
        assertThat(result.cleanTitle).isEqualTo("Gladiator")
        assertThat(result.year).isEqualTo(2000)
        assertThat(result.audioCodec).isIn(listOf("DTS-HD.MA", "DTS-HD MA"))
    }

    @Test
    fun testFLACAudio() {
        val result = FilenameParser.parse("Spirited.Away.2001.1080p.FLAC.x265.mkv")
        assertThat(result.cleanTitle).isEqualTo("Spirited Away")
        assertThat(result.year).isEqualTo(2001)
        assertThat(result.audioCodec).isEqualTo("FLAC")
    }

    // --- 12. Miscellaneous Edge Cases ---
    @Test
    fun testFileNameWithUnderscores() {
        val result = FilenameParser.parse("The_Lord_of_the_Rings_The_Fellowship_of_the_Ring_2001_1080p.mp4")
        assertThat(result.cleanTitle).isEqualTo("The Lord of the Rings The Fellowship of the Ring")
        assertThat(result.year).isEqualTo(2001)
    }

    @Test
    fun testFileNameWithParenthesesTitle() {
        val result = FilenameParser.parse("WALL-E (2008) [720p].mkv")
        assertThat(result.cleanTitle).isEqualTo("WALL-E")
        assertThat(result.year).isEqualTo(2008)
        assertThat(result.resolution).isEqualTo("720P")
    }

    @Test
    fun testFileRemuxTag() {
        val result = FilenameParser.parse("Pulp.Fiction.1994.REMUX.1080p.AVC.DTS-HD.MA.5.1-EPSiLON.mkv")
        assertThat(result.cleanTitle).isEqualTo("Pulp Fiction")
        assertThat(result.year).isEqualTo(1994)
        assertThat(result.releaseGroup).isEqualTo("EPSiLON")
    }

    @Test
    fun testOldAviFile() {
        val result = FilenameParser.parse("Fight.Club.1999.DVDRip.XviD-AC3.avi")
        assertThat(result.cleanTitle).isEqualTo("Fight Club")
        assertThat(result.year).isEqualTo(1999)
        assertThat(result.extension).isEqualTo("avi")
    }

    @Test
    fun testTsContainer() {
        val result = FilenameParser.parse("Recording_20240101_1080i.ts")
        assertThat(result.extension).isEqualTo("ts")
        assertThat(result.resolution).isEqualTo("1080I")
    }

    @Test
    fun testM2tsContainer() {
        val result = FilenameParser.parse("00001.m2ts")
        assertThat(result.extension).isEqualTo("m2ts")
    }

    @Test
    fun testMovieWithAccents() {
        val result = FilenameParser.parse("Amélie.Poulain.2001.1080p.mkv")
        assertThat(result.cleanTitle).isEqualTo("Amélie Poulain")
        assertThat(result.year).isEqualTo(2001)
    }

    @Test
    fun testMovieWithExclamationMark() {
        val result = FilenameParser.parse("Mother! (2017) 1080p.mkv")
        assertThat(result.cleanTitle).isEqualTo("Mother!")
        assertThat(result.year).isEqualTo(2017)
    }

    @Test
    fun testMovieWithQuestionMarkCleaned() {
        val result = FilenameParser.parse("Who.Am.I.2014.1080p.mkv")
        assertThat(result.cleanTitle).isEqualTo("Who Am I")
        assertThat(result.year).isEqualTo(2014)
    }

    @Test
    fun testFourDigitTitleYearMismatch() {
        val result = FilenameParser.parse("Cyberpunk.2077.No.Coincidence.2023.mkv")
        assertThat(result.cleanTitle).isEqualTo("Cyberpunk 2077 No Coincidence")
        assertThat(result.year).isEqualTo(2023)
    }

    @Test
    fun testDoubleEpisodeWithSpaces() {
        val result = FilenameParser.parse("Chernobyl - S01E01-E02 - 1080p.mkv")
        assertThat(result.cleanTitle).isEqualTo("Chernobyl")
        assertThat(result.seasonNumber).isEqualTo(1)
        assertThat(result.episodeNumbers).containsExactly(1, 2)
    }

    @Test
    fun testSingleDigitSeasonAndEpisode() {
        val result = FilenameParser.parse("The.Crown.S1E1.mkv")
        assertThat(result.cleanTitle).isEqualTo("The Crown")
        assertThat(result.seasonNumber).isEqualTo(1)
        assertThat(result.episodeNumbers).containsExactly(1)
    }

    @Test
    fun testThreeDigitEpisodeStandard() {
        val result = FilenameParser.parse("LongRunningSeries.S01E105.mkv")
        assertThat(result.cleanTitle).isEqualTo("LongRunningSeries")
        assertThat(result.seasonNumber).isEqualTo(1)
        assertThat(result.episodeNumbers).containsExactly(105)
    }

    @Test
    fun testCleanTitleWithHyphenInsideWord() {
        val result = FilenameParser.parse("Spider-Man.No.Way.Home.2021.2160p.mkv")
        assertThat(result.cleanTitle).isEqualTo("Spider-Man No Way Home")
        assertThat(result.year).isEqualTo(2021)
    }
}
