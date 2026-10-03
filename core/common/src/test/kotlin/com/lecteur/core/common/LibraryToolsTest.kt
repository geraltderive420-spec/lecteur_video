package com.lecteur.core.common

import com.google.common.truth.Truth.assertThat
import com.lecteur.core.common.match.CategoryFit
import com.lecteur.core.common.match.MatchCandidate
import com.lecteur.core.common.match.MatchQuery
import com.lecteur.core.common.match.MatchScorer
import com.lecteur.core.common.match.TitleSimilarity
import com.lecteur.core.common.nfo.NfoKind
import com.lecteur.core.common.nfo.NfoParser
import com.lecteur.core.common.scan.EpisodeNumbering
import com.lecteur.core.common.scan.FoundFile
import com.lecteur.core.common.scan.KnownFile
import com.lecteur.core.common.scan.MoveResolver
import com.lecteur.core.common.scan.ScanPlanner
import com.lecteur.core.common.scan.SeriesKey
import com.lecteur.core.common.scan.VideoFiles
import com.lecteur.core.model.MatchState
import com.lecteur.core.model.MediaCategory
import com.lecteur.core.common.parser.FilenameParser
import org.junit.Test

class TitleSimilarityTest {

    @Test
    fun accentsCaseAndPunctuationAreIgnored() {
        assertThat(TitleSimilarity.similarity("Amélie", "amelie!")).isEqualTo(1.0)
        assertThat(TitleSimilarity.similarity("Spider-Man: No Way Home", "Spider Man No Way Home")).isEqualTo(1.0)
        assertThat(TitleSimilarity.similarity("Tom & Jerry", "Tom and Jerry")).isEqualTo(1.0)
    }

    @Test
    fun leadingArticleIsIgnored() {
        assertThat(TitleSimilarity.similarity("The Matrix", "Matrix")).isEqualTo(1.0)
        assertThat(TitleSimilarity.similarity("Les Misérables", "Miserables")).isEqualTo(1.0)
    }

    @Test
    fun aSingleWordTitleIsNotStrippedToNothing() {
        assertThat(TitleSimilarity.comparisonKey("The")).isEqualTo("the")
        assertThat(TitleSimilarity.similarity("", "Matrix")).isEqualTo(0.0)
    }

    @Test
    fun typosScoreHighAndUnrelatedTitlesLow() {
        assertThat(TitleSimilarity.similarity("Inglorious Basterds", "Inglourious Basterds")).isGreaterThan(0.9)
        assertThat(TitleSimilarity.similarity("Alien", "Aliens")).isGreaterThan(0.7)
        assertThat(TitleSimilarity.similarity("Titanic", "Gladiator")).isLessThan(0.3)
    }

    @Test
    fun numbersInTitlesMatter() {
        assertThat(TitleSimilarity.similarity("Blade Runner 2049", "Blade Runner")).isLessThan(0.9)
        assertThat(TitleSimilarity.similarity("1917", "2001")).isLessThan(0.5)
    }
}

class MatchScorerTest {

    private val dune = MatchCandidate(1, "Dune", year = 2021, runtimeMinutes = 155, popularity = 100.0)
    private val dune1984 = MatchCandidate(2, "Dune", year = 1984, runtimeMinutes = 137, popularity = 20.0)
    private val duneTwo = MatchCandidate(3, "Dune : Deuxième partie", originalTitle = "Dune: Part Two", year = 2024, runtimeMinutes = 166)

    @Test
    fun exactTitleYearAndRuntimeIsIdentified() {
        val decision = MatchScorer.decide(MatchQuery("Dune", 2021, 154), listOf(dune, dune1984, duneTwo))
        assertThat(decision.state).isEqualTo(MatchState.IDENTIFIED)
        assertThat(decision.best?.candidate?.id).isEqualTo(1)
        assertThat(decision.best?.confidence).isGreaterThan(0.95)
    }

    @Test
    fun theYearPicksBetweenRemakes() {
        val decision = MatchScorer.decide(MatchQuery("Dune", 1984), listOf(dune, dune1984))
        assertThat(decision.best?.candidate?.id).isEqualTo(2)
        assertThat(decision.state).isEqualTo(MatchState.IDENTIFIED)
    }

    @Test
    fun runtimeSeparatesTwoEntriesWhenTheYearIsMissing() {
        val longCut = MatchScorer.score(MatchQuery("Dune", null, 137), dune).confidence
        val rightCut = MatchScorer.score(MatchQuery("Dune", null, 137), dune1984).confidence
        assertThat(rightCut).isGreaterThan(longCut)
    }

    @Test
    fun remakesWithoutAnyYearAreAmbiguousAndGoToReview() {
        val twin = dune.copy(id = 9, year = 2022, popularity = 99.0)
        val decision = MatchScorer.decide(MatchQuery("Dune"), listOf(dune, twin))
        assertThat(decision.state).isEqualTo(MatchState.TO_VERIFY)
    }

    @Test
    fun aSingleCandidateWithoutYearIsStillIdentified() {
        assertThat(MatchScorer.decide(MatchQuery("Dune"), listOf(dune)).state).isEqualTo(MatchState.IDENTIFIED)
    }

    @Test
    fun yearOffByOneIsToleratedButNotTwoPlus() {
        assertThat(MatchScorer.score(MatchQuery("Dune", 2020), dune).yearScore).isEqualTo(0.7)
        assertThat(MatchScorer.score(MatchQuery("Dune", 2018), dune).yearScore).isEqualTo(0.0)
    }

    @Test
    fun originalTitleCanMatch() {
        val decision = MatchScorer.decide(MatchQuery("Dune Part Two", 2024), listOf(duneTwo))
        assertThat(decision.state).isEqualTo(MatchState.IDENTIFIED)
    }

    @Test
    fun weakTitleIsToVerifyAndUnrelatedIsUnidentified() {
        val weak = MatchScorer.decide(MatchQuery("Blade Runner 2049", 2017), listOf(MatchCandidate(5, "Blade Runner", year = 1982)))
        assertThat(weak.state).isEqualTo(MatchState.TO_VERIFY)

        val none = MatchScorer.decide(MatchQuery("Vacances Famille 2019", 2019), listOf(MatchCandidate(6, "Gladiator", year = 2000)))
        assertThat(none.state).isEqualTo(MatchState.UNIDENTIFIED)
        assertThat(none.best).isNull()
    }

    @Test
    fun noCandidateMeansUnidentified() {
        assertThat(MatchScorer.decide(MatchQuery("x"), emptyList()).state).isEqualTo(MatchState.UNIDENTIFIED)
    }

    @Test
    fun popularityBreaksExactTies() {
        val ranked = MatchScorer.rank(MatchQuery("Dune", 2021), listOf(dune.copy(id = 10, popularity = 1.0), dune.copy(id = 11, popularity = 50.0)))
        assertThat(ranked.first().candidate.id).isEqualTo(11)
    }
}

class NfoParserTest {

    @Test
    fun kodiMovieNfoIsRead() {
        val nfo = NfoParser.parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <movie>
              <title>Le Fabuleux Destin d'Am&eacute;lie Poulain</title>
              <originaltitle>Le Fabuleux Destin d'Amélie Poulain</originaltitle>
              <year>2001</year>
              <plot><![CDATA[Une serveuse <b>rêveuse</b> & discrète.]]></plot>
              <runtime>122 min</runtime>
              <ratings><rating name="imdb" default="true"><value>8,3</value></rating></ratings>
              <mpaa>Rated PG-13</mpaa>
              <uniqueid type="imdb" default="true">tt0211915</uniqueid>
              <uniqueid type="tmdb">194</uniqueid>
              <genre>Comédie</genre><genre>Romance</genre>
            </movie>
            """.trimIndent()
        )!!

        assertThat(nfo.kind).isEqualTo(NfoKind.MOVIE)
        assertThat(nfo.originalTitle).isEqualTo("Le Fabuleux Destin d'Amélie Poulain")
        assertThat(nfo.year).isEqualTo(2001)
        assertThat(nfo.overview).isEqualTo("Une serveuse <b>rêveuse</b> & discrète.")
        assertThat(nfo.runtimeMinutes).isEqualTo(122)
        assertThat(nfo.rating).isEqualTo(8.3f)
        assertThat(nfo.certification).isEqualTo("PG-13")
        assertThat(nfo.tmdbId).isEqualTo(194)
        assertThat(nfo.imdbId).isEqualTo("tt0211915")
        assertThat(nfo.genres).containsExactly("Comédie", "Romance").inOrder()
    }

    @Test
    fun legacyIdTagsAndPremieredYearAreRead() {
        val nfo = NfoParser.parse("<tvshow><title>Show</title><premiered>2015-04-12</premiered><id>tt1234567</id><tmdbid>42</tmdbid></tvshow>")!!
        assertThat(nfo.kind).isEqualTo(NfoKind.SERIES)
        assertThat(nfo.year).isEqualTo(2015)
        assertThat(nfo.imdbId).isEqualTo("tt1234567")
        assertThat(nfo.tmdbId).isEqualTo(42)
    }

    @Test
    fun aBareUrlNfoGivesTheTmdbId() {
        val movie = NfoParser.parse("https://www.themoviedb.org/movie/603-the-matrix")!!
        assertThat(movie.kind).isEqualTo(NfoKind.MOVIE)
        assertThat(movie.tmdbId).isEqualTo(603)
        assertThat(movie.title).isNull()

        val tv = NfoParser.parse("https://www.themoviedb.org/tv/1396")!!
        assertThat(tv.kind).isEqualTo(NfoKind.SERIES)
        assertThat(tv.tmdbId).isEqualTo(1396)
    }

    @Test
    fun anImdbUrlGivesTheImdbId() {
        assertThat(NfoParser.parse("https://www.imdb.com/title/tt0133093/")?.imdbId).isEqualTo("tt0133093")
    }

    @Test
    fun garbageAndEmptyFilesYieldNothing() {
        assertThat(NfoParser.parse("")).isNull()
        assertThat(NfoParser.parse("hello world")).isNull()
        assertThat(NfoParser.parse("<movie><year>2001</year></movie>")).isNull() // nothing identifies the film
    }

    @Test
    fun byteOrderMarkAndMissingClosingRootAreTolerated() {
        val nfo = NfoParser.parse("﻿<movie><title>Alien</title><year>1979</year>")!!
        assertThat(nfo.title).isEqualTo("Alien")
        assertThat(nfo.year).isEqualTo(1979)
    }
}

class ScanPlannerTest {

    private fun known(id: Long, uri: String, size: Long = 100, modified: Long = 10, fp: String = "fp$id", available: Boolean = true) =
        KnownFile(id, uri, fp, size, modified, available)

    private fun found(uri: String, size: Long = 100, modified: Long = 10, path: String = "a/b/$uri.mkv") =
        FoundFile(uri, "$uri.mkv", size, modified, path)

    @Test
    fun unchangedNewAndMissingAreSeparated() {
        val plan = ScanPlanner.plan(
            known = listOf(known(1, "keep"), known(2, "gone")),
            found = listOf(found("keep"), found("fresh"))
        )
        assertThat(plan.unchanged.map { it.found.uri }).containsExactly("keep")
        assertThat(plan.toProcess.map { it.found.uri }).containsExactly("fresh")
        assertThat(plan.toProcess.single().known).isNull()
        assertThat(plan.missing.map { it.uri }).containsExactly("gone")
    }

    @Test
    fun aChangedSizeOrDateMeansReprocess() {
        val plan = ScanPlanner.plan(
            known = listOf(known(1, "bigger"), known(2, "touched")),
            found = listOf(found("bigger", size = 999), found("touched", modified = 77))
        )
        assertThat(plan.toProcess.map { it.found.uri }).containsExactly("bigger", "touched")
        assertThat(plan.toProcess.all { it.known != null }).isTrue()
        assertThat(plan.unchanged).isEmpty()
    }

    @Test
    fun aProviderWithoutDatesIsJudgedBySizeOnly() {
        val plan = ScanPlanner.plan(listOf(known(1, "x", modified = 55)), listOf(found("x", modified = 0)))
        assertThat(plan.unchanged).hasSize(1)
    }

    @Test
    fun forceReprocessesEverything() {
        val plan = ScanPlanner.plan(listOf(known(1, "keep")), listOf(found("keep")), force = true)
        assertThat(plan.toProcess).hasSize(1)
        assertThat(plan.unchanged).isEmpty()
    }

    @Test
    fun emptyWalkMarksEverythingMissing() {
        val plan = ScanPlanner.plan(listOf(known(1, "a"), known(2, "b")), emptyList())
        assertThat(plan.missing).hasSize(2)
    }

    @Test
    fun relativePathGivesParentFolders() {
        val file = found("ep", path = "Show/Saison 2/05.mkv")
        assertThat(file.parentName).isEqualTo("Saison 2")
        assertThat(file.grandparentName).isEqualTo("Show")
        assertThat(found("x", path = "x.mkv").parentName).isNull()
    }

    @Test
    fun movedFileIsClaimedByItsFingerprintOnce() {
        val resolver = MoveResolver(listOf(known(1, "old", fp = "same"), known(2, "old2", fp = "same"), known(3, "other", fp = "zzz")))

        assertThat(resolver.claim("same")?.id).isEqualTo(1)
        assertThat(resolver.claim("same")?.id).isEqualTo(2)
        assertThat(resolver.claim("same")).isNull()
        assertThat(resolver.claim("unknown")).isNull()
        assertThat(resolver.unclaimed().map { it.id }).containsExactly(3L)
    }
}

class EpisodeNumberingTest {

    @Test
    fun absoluteNumberWalksThroughSeasons() {
        val counts = mapOf(0 to 3, 1 to 12, 2 to 13, 3 to 25)
        assertThat(EpisodeNumbering.fromAbsolute(1, counts)).isEqualTo(1 to 1)
        assertThat(EpisodeNumbering.fromAbsolute(12, counts)).isEqualTo(1 to 12)
        assertThat(EpisodeNumbering.fromAbsolute(13, counts)).isEqualTo(2 to 1)
        assertThat(EpisodeNumbering.fromAbsolute(37, counts)).isEqualTo(3 to 12)
        assertThat(EpisodeNumbering.fromAbsolute(50, counts)).isEqualTo(3 to 25)
        assertThat(EpisodeNumbering.fromAbsolute(51, counts)).isNull()
        assertThat(EpisodeNumbering.fromAbsolute(0, counts)).isNull()
    }

    @Test
    fun seasonsAreWalkedInOrderWhateverTheMapOrder() {
        assertThat(EpisodeNumbering.fromAbsolute(11, linkedMapOf(2 to 10, 1 to 10))).isEqualTo(2 to 1)
    }

    @Test
    fun dateEncodedEpisodesAreRecognisedAndDecoded() {
        assertThat(EpisodeNumbering.isDateBased(2024, 315)).isTrue()
        assertThat(EpisodeNumbering.dateOf(2024, 315)).isEqualTo("2024-03-15")
        assertThat(EpisodeNumbering.dateOf(2023, 1231)).isEqualTo("2023-12-31")
        assertThat(EpisodeNumbering.isDateBased(2, 315)).isFalse()
        assertThat(EpisodeNumbering.isDateBased(2024, 1399)).isFalse()
        assertThat(EpisodeNumbering.isDateBased(null, 315)).isFalse()
    }
}

class SeriesKeyAndFilesTest {

    @Test
    fun lookupKeysGoFromSpecificToGeneral() {
        assertThat(SeriesKey.lookupKeys("Doctor Who", 2005)).containsExactly("doctor who 2005", "doctor who").inOrder()
        assertThat(SeriesKey.lookupKeys("Amélie!", null)).containsExactly("amelie")
        assertThat(SeriesKey.lookupKeys("  ", 2000)).isEmpty()
        assertThat(SeriesKey.registrationKey("Doctor Who", 2005)).isEqualTo("doctor who 2005")
    }

    @Test
    fun videoExtensionsAreRecognised() {
        assertThat(VideoFiles.isVideo("Film.MKV")).isTrue()
        assertThat(VideoFiles.isVideo("Film.m2ts")).isTrue()
        assertThat(VideoFiles.isVideo("Film.srt")).isFalse()
        assertThat(VideoFiles.isVideo("noextension")).isFalse()
        assertThat(VideoFiles.isNfo("movie.NFO")).isTrue()
    }

    @Test
    fun artworkNamesFollowKodiConventions() {
        assertThat(VideoFiles.isPosterFor("poster.jpg", null)).isTrue()
        assertThat(VideoFiles.isPosterFor("Folder.PNG", null)).isTrue()
        assertThat(VideoFiles.isPosterFor("Film-poster.jpg", "Film")).isTrue()
        assertThat(VideoFiles.isPosterFor("Film.jpg", "Film")).isTrue()
        assertThat(VideoFiles.isPosterFor("Autre-poster.jpg", "Film")).isFalse()
        assertThat(VideoFiles.isPosterFor("poster.txt", null)).isFalse()
        assertThat(VideoFiles.isBackdropFor("fanart.jpg", null)).isTrue()
        assertThat(VideoFiles.isBackdropFor("Film-fanart.jpg", "Film")).isTrue()
        assertThat(VideoFiles.isBackdropFor("Film.jpg", "Film")).isFalse()
    }
}

class CategoryFitTest {

    private val all = listOf(MediaCategory.MOVIES, MediaCategory.SERIES, MediaCategory.ANIME)

    private fun pick(siblings: List<MediaCategory>, name: String, path: String = name) =
        CategoryFit.choose(siblings, FilenameParser.parse(name), path)

    @Test
    fun aSingleEntryTakesEverything() {
        assertThat(pick(listOf(MediaCategory.MOVIES), "Show.S01E01.mkv")).isEqualTo(MediaCategory.MOVIES)
        assertThat(pick(listOf(MediaCategory.SERIES), "Dune.2021.mkv")).isEqualTo(MediaCategory.SERIES)
    }

    @Test
    fun filmsEpisodesAndAnimeGoToTheirOwnEntry() {
        assertThat(pick(all, "Dune.2021.2160p.mkv")).isEqualTo(MediaCategory.MOVIES)
        assertThat(pick(all, "Breaking.Bad.S01E01.mkv")).isEqualTo(MediaCategory.SERIES)
        assertThat(pick(all, "[Group] Naruto - 112 [1080p].mkv")).isEqualTo(MediaCategory.ANIME)
        assertThat(pick(all, "Show.S01E01.mkv", "Media/Animes/Show/Show.S01E01.mkv")).isEqualTo(MediaCategory.ANIME)
    }

    @Test
    fun missingEntriesFallBackToTheClosestOne() {
        val seriesAndAnime = listOf(MediaCategory.SERIES, MediaCategory.ANIME)
        assertThat(pick(seriesAndAnime, "Show.S01E01.mkv")).isEqualTo(MediaCategory.SERIES)
        assertThat(pick(listOf(MediaCategory.MOVIES, MediaCategory.ANIME), "Show.S01E01.mkv")).isEqualTo(MediaCategory.ANIME)
        // No movie entry: a film still has an owner, and nothing is dropped
        assertThat(pick(seriesAndAnime, "Dune.2021.mkv")).isEqualTo(MediaCategory.SERIES)
    }

    @Test
    fun documentariesAreRecognisedByTheirFolderWhenThereIsAnEntryForThem() {
        val both = listOf(MediaCategory.MOVIES, MediaCategory.DOCUMENTARIES)
        assertThat(pick(both, "Planet.Earth.2006.mkv", "Media/Documentaires/Planet.Earth.2006.mkv")).isEqualTo(MediaCategory.DOCUMENTARIES)
        assertThat(pick(both, "Planet.Earth.2006.mkv")).isEqualTo(MediaCategory.MOVIES)
    }

    @Test
    fun exactlyOneSiblingTakesEachFile() {
        val names = listOf("Dune.2021.mkv", "Show.S02E03.mkv", "[Grp] Bleach - 044.mkv", "Alien.1979.mkv", "Daily.2024.03.15.mkv")
        names.forEach { name ->
            val owners = all.filter { pick(all, name) == it }
            assertThat(owners).hasSize(1)
        }
    }
}
