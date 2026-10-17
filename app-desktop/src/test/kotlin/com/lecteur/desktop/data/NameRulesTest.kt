package com.lecteur.desktop.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** File names from a real download folder that the scan filed wrongly. */
class NameRulesTest {

    @Test
    fun `dashed episode number in a season folder is an episode of the show folder`() {
        val p = NameRules.parse("Kaiji Ultimate Survivor - 01 - Departure.mp4", "saison 1", "Kaiji l'ultime survivant")
        assertThat(p.isSeries).isTrue()
        assertThat(p.seasonNumber).isEqualTo(1)
        assertThat(p.episodeNumbers).containsExactly(1)
        assertThat(p.cleanTitle.lowercase()).contains("kaiji")
        assertThat(p.cleanTitle.lowercase()).contains("survivant")
    }

    @Test
    fun `second season with another file title joins the same show`() {
        val s1 = NameRules.parse("Kaiji Ultimate Survivor - 26 - Afterglow.mp4", "saison 1", "Kaiji l'ultime survivant")
        val s2 = NameRules.parse("Kaiji Against All Rules - 09 - Applause, and then....mp4", "saison 2", "Kaiji l'ultime survivant")
        assertThat(s2.isSeries).isTrue()
        assertThat(s2.seasonNumber).isEqualTo(2)
        assertThat(s2.episodeNumbers).containsExactly(9)
        assertThat(s2.cleanTitle).isEqualTo(s1.cleanTitle)
    }

    @Test
    fun `year after a dashed title is not an episode`() {
        val p = NameRules.parse("Wall-E.2008.MULTi.1080p.BluRay.HDLight.x265-H4S5S.mkv", "Torrent download", null)
        assertThat(p.isSeries).isFalse()
        assertThat(p.year).isEqualTo(2008)
        assertThat(p.cleanTitle.lowercase()).startsWith("wall")
    }

    @Test
    fun `regular episodes and films are left alone`() {
        val ep = NameRules.parse("L.amour.est.dans.le.pre.S21E05.FRENCH.1080p.WEB.H264-BOUBA.mkv", "Torrent download", null)
        assertThat(ep.isSeries).isTrue()
        assertThat(ep.seasonNumber).isEqualTo(21)
        assertThat(ep.episodeNumbers).containsExactly(5)

        val film = NameRules.parse("Spider-Man.Brand.New.Day.2026.MULTi.TRUEFRENCH.2160p.HDR.DV.WEB-DL.H265-Slay3R.mkv", "Torrent download", null)
        assertThat(film.isSeries).isFalse()
        assertThat(film.year).isEqualTo(2026)

        val dated = NameRules.parse("L.Amour.Est.Dans.Le.Pre.S21E06.31.08.2026.VFF.1080p.WEB.AAC.2.0.H264-BOUBA.mkv", "Torrent download", null)
        assertThat(dated.seasonNumber).isEqualTo(21)
        assertThat(dated.episodeNumbers).containsExactly(6)
    }
}
