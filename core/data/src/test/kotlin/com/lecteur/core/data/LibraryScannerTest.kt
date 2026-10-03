package com.lecteur.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lecteur.core.data.scan.LibraryLinker
import com.lecteur.core.data.scan.LibraryScanner
import com.lecteur.core.data.scan.ScanPhase
import com.lecteur.core.data.scan.ScanProgress
import com.lecteur.core.database.AppDatabase
import com.lecteur.core.database.entity.LibraryFolderEntity
import com.lecteur.core.database.entity.WatchStateEntity
import com.lecteur.core.model.AudioCodec
import com.lecteur.core.model.HdrType
import com.lecteur.core.model.MatchState
import com.lecteur.core.model.MediaCategory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class LibraryScannerTest {

    private lateinit var db: AppDatabase
    private val walker = FakeWalker()
    private val inspector = FakeInspector()
    private val sidecars = FakeSidecars()

    private lateinit var scanner: LibraryScanner

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        scanner = LibraryScanner(
            db.libraryFolderDao(), db.mediaFileDao(), walker, inspector, sidecars,
            LibraryLinker(db.movieDao(), db.seriesDao(), db.seasonDao(), db.episodeDao(), db.seriesAliasDao(), db.mediaFileDao()),
            com.lecteur.core.data.library.LibraryWriteLock()
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun folder(category: MediaCategory, name: String = "Library") =
        db.libraryFolderDao().insertFolder(LibraryFolderEntity(uri = "content://tree/$name", displayPath = "/storage/emulated/0/$name", category = category))

    // region movies

    @Test
    fun aMovieFolderIsScannedFiledAndInspected() = runTest {
        val id = folder(MediaCategory.MOVIES, "Movies")
        walker.put("", "Dune.2021.2160p.UHD.BluRay.x265.HDR.mkv")
        walker.put("Blade Runner 2049 (2017)", "Blade.Runner.2049.2017.1080p.mkv")

        val summary = scanner.scan(id)

        assertThat(summary.added).isEqualTo(2)
        assertThat(summary.failed).isEqualTo(0)
        val movies = db.movieDao().getAllMovies().sortedBy { it.title }
        assertThat(movies.map { it.title }).containsExactly("Blade Runner 2049", "Dune").inOrder()
        assertThat(movies.map { it.year }).containsExactly(2017, 2021).inOrder()
        assertThat(movies.all { it.matchState == MatchState.UNIDENTIFIED && !it.matchLocked }).isTrue()

        val file = db.mediaFileDao().getMediaFilesByFolder(id).first { it.fileName.startsWith("Dune") }
        assertThat(file.movieId).isEqualTo(movies.first { it.title == "Dune" }.id)
        assertThat(file.width).isEqualTo(3840)
        assertThat(file.container).isEqualTo("MKV")
        assertThat(file.durationMs).isEqualTo(7_200_000)
        assertThat(file.hdrType).isEqualTo(HdrType.HDR10) // from the name here: the fake inspector forwards it
        assertThat(file.isAvailable).isTrue()
        assertThat(file.displayPath).isEqualTo("/storage/emulated/0/Movies/Dune.2021.2160p.UHD.BluRay.x265.HDR.mkv")

        val tracks = db.mediaFileDao().getMediaFileWithTracks(file.id)!!
        assertThat(tracks.audioTracks.map { it.codec }).containsExactly(AudioCodec.AC3, AudioCodec.TRUEHD)
        assertThat(tracks.audioTracks.first().language).isEqualTo("fre")
        assertThat(tracks.subtitleTracks.single().isForced).isTrue()
        assertThat(db.libraryFolderDao().getFolderById(id)?.lastScannedAt).isNotNull()
    }

    @Test
    fun anUnchangedLibraryIsNotInspectedAgain() = runTest {
        val id = folder(MediaCategory.MOVIES)
        walker.put("", "Dune.2021.mkv")
        walker.put("", "Alien.1979.mkv")
        scanner.scan(id)
        val inspected = inspector.inspections
        val fingerprinted = inspector.fingerprints

        val second = scanner.scan(id)

        assertThat(second.unchanged).isEqualTo(2)
        assertThat(second.added).isEqualTo(0)
        assertThat(inspector.inspections).isEqualTo(inspected)
        assertThat(inspector.fingerprints).isEqualTo(fingerprinted)
        assertThat(db.movieDao().getAllMovies()).hasSize(2)
    }

    @Test
    fun onlyNewAndModifiedFilesAreProcessedOnTheNextScan() = runTest {
        val id = folder(MediaCategory.MOVIES)
        walker.put("", "Dune.2021.mkv", size = 100, modified = 1)
        walker.put("", "Alien.1979.mkv", size = 100, modified = 1)
        scanner.scan(id)
        val before = inspector.inspections

        walker.put("", "Dune.2021.mkv", size = 250, modified = 2) // re-encoded
        walker.put("", "Heat.1995.mkv")                           // new
        val summary = scanner.scan(id)

        assertThat(summary.updated).isEqualTo(1)
        assertThat(summary.added).isEqualTo(1)
        assertThat(summary.unchanged).isEqualTo(1)
        assertThat(inspector.inspections - before).isEqualTo(2)
        assertThat(db.movieDao().getAllMovies()).hasSize(3) // the re-encoded file kept its movie
    }

    @Test
    fun aForcedScanReprocessesEverything() = runTest {
        val id = folder(MediaCategory.MOVIES)
        walker.put("", "Dune.2021.mkv")
        scanner.scan(id)
        val before = inspector.inspections

        val summary = scanner.scan(id, force = true)

        assertThat(summary.updated).isEqualTo(1)
        assertThat(inspector.inspections - before).isEqualTo(1)
        assertThat(db.movieDao().getAllMovies()).hasSize(1)
    }

    @Test
    fun anUnreadableFileIsStillListed() = runTest {
        val id = folder(MediaCategory.MOVIES)
        walker.put("", "Broken.2020.mkv", uri = "content://disk/broken")
        walker.put("", "Fine.2020.mkv")
        inspector.unreadable += "content://disk/broken"

        val summary = scanner.scan(id)

        assertThat(summary.added).isEqualTo(2)
        assertThat(summary.unreadable).isEqualTo(1)
        val broken = db.mediaFileDao().getMediaFilesByFolder(id).first { it.fileName == "Broken.2020.mkv" }
        assertThat(broken.durationMs).isNull()
        assertThat(broken.movieId).isNotNull()
    }

    @Test
    fun personalVideosAreLockedAgainstOnlineLookup() = runTest {
        val id = folder(MediaCategory.PERSONAL)
        walker.put("", "Vacances.Famille.2019.mkv")

        scanner.scan(id)

        val movie = db.movieDao().getAllMovies().single()
        assertThat(movie.matchLocked).isTrue()
        assertThat(db.movieDao().getMoviesToIdentify(Long.MAX_VALUE, 10)).isEmpty()
    }

    @Test
    fun nfoAndLocalArtworkBeatTheFileName() = runTest {
        val id = folder(MediaCategory.MOVIES)
        walker.put("Film", "Some.Release.1080p.mkv")
        walker.put("Film", "movie.nfo", uri = "content://disk/film.nfo")
        walker.put("Film", "poster.jpg", uri = "content://disk/film-poster")
        walker.put("Film", "fanart.jpg", uri = "content://disk/film-fanart")
        sidecars.files["content://disk/film.nfo"] =
            "<movie><title>Le Vrai Titre</title><year>1999</year><plot>Synopsis local</plot><uniqueid type=\"tmdb\">603</uniqueid></movie>"

        scanner.scan(id)

        val movie = db.movieDao().getAllMovies().single()
        assertThat(movie.title).isEqualTo("Le Vrai Titre")
        assertThat(movie.year).isEqualTo(1999)
        assertThat(movie.overview).isEqualTo("Synopsis local")
        assertThat(movie.tmdbId).isEqualTo(603)
        assertThat(movie.posterPath).isEqualTo("content://disk/film-poster")
        assertThat(movie.backdropPath).isEqualTo("content://disk/film-fanart")
    }

    @Test
    fun twoFilesNamingTheSameTmdbIdShareOneMovie() = runTest {
        val id = folder(MediaCategory.MOVIES)
        walker.put("A", "Matrix.1080p.mkv")
        walker.put("A", "movie.nfo", uri = "content://disk/a.nfo")
        walker.put("B", "Matrix.2160p.mkv")
        walker.put("B", "movie.nfo", uri = "content://disk/b.nfo")
        sidecars.files["content://disk/a.nfo"] = "https://www.themoviedb.org/movie/603"
        sidecars.files["content://disk/b.nfo"] = "<movie><title>The Matrix</title><tmdbid>603</tmdbid></movie>"

        scanner.scan(id)

        assertThat(db.movieDao().getAllMovies()).hasSize(1)
        val movie = db.movieDao().getAllMovies().single()
        assertThat(db.mediaFileDao().getMediaFilesForMovie(movie.id)).hasSize(2)
    }

    // endregion

    // region series

    @Test
    fun episodesOfOneShowAreGroupedWhateverTheirFolders() = runTest {
        val id = folder(MediaCategory.SERIES, "TV")
        walker.put("Breaking Bad/Saison 1", "Breaking.Bad.S01E01.1080p.mkv")
        walker.put("Breaking Bad/Saison 1", "Breaking.Bad.S01E02.1080p.mkv")
        walker.put("Breaking Bad/Saison 2", "Breaking Bad - 2x01.mkv")
        walker.put("Elsewhere", "breaking_bad_s02e02.mkv")

        scanner.scan(id)

        val series = db.seriesDao().getAllSeries()
        assertThat(series).hasSize(1)
        assertThat(series.single().title).isEqualTo("Breaking Bad")
        val episodes = db.episodeDao().getEpisodesForSeries(series.single().id)
        assertThat(episodes.map { it.seasonNumber to it.episodeNumber }).containsExactly(1 to 1, 1 to 2, 2 to 1, 2 to 2).inOrder()
        assertThat(db.seasonDao().getSeasonsForSeries(series.single().id)).hasSize(2)
        assertThat(db.mediaFileDao().getFilesForSeries(series.single().id)).hasSize(4)
    }

    @Test
    fun yearsKeepHomonymousSeriesApartAndABareTitleFindsTheOwner() = runTest {
        val id = folder(MediaCategory.SERIES, "TV")
        walker.put("a", "Doctor Who (2005) S01E01.mkv")
        walker.put("b", "Doctor Who (1963) S01E01.mkv")
        walker.put("c", "Doctor.Who.2005.S01E02.mkv")

        scanner.scan(id)

        val series = db.seriesDao().getAllSeries()
        assertThat(series).hasSize(2)
        val modern = series.first { it.firstAirDate == "2005" }
        val classic = series.first { it.firstAirDate == "1963" }
        assertThat(db.episodeDao().getEpisodesForSeries(modern.id).map { it.episodeNumber }).containsExactly(1, 2).inOrder()
        assertThat(db.episodeDao().getEpisodesForSeries(classic.id).map { it.episodeNumber }).containsExactly(1)

        // A file naming no year joins one of the two rather than creating a third
        walker.put("d", "Doctor.Who.S01E03.mkv")
        scanner.scan(id)
        assertThat(db.seriesDao().getAllSeries()).hasSize(2)
    }

    @Test
    fun aNewEpisodeOfAnIdentifiedSeriesJoinsItWithoutANewSeries() = runTest {
        val id = folder(MediaCategory.SERIES, "TV")
        walker.put("Show", "Show.S01E01.mkv")
        scanner.scan(id)
        val seriesId = db.seriesDao().getAllSeries().single().id
        db.seriesDao().updateSeries(db.seriesDao().getSeriesById(seriesId)!!.copy(title = "Le Titre Officiel", matchState = MatchState.IDENTIFIED))

        walker.put("Show", "Show.S01E02.mkv")
        scanner.scan(id)

        assertThat(db.seriesDao().getAllSeries()).hasSize(1)
        assertThat(db.episodeDao().getEpisodesForSeries(seriesId)).hasSize(2)
    }

    @Test
    fun animeAbsoluteNumbersAndSpecialsAreKeptProvisionally() = runTest {
        val id = folder(MediaCategory.ANIME, "Anime")
        walker.put("", "[Group] Naruto - 112 [1080p].mkv")
        walker.put("", "Naruto.S00E01.mkv")

        scanner.scan(id)

        val series = db.seriesDao().getAllSeries().single()
        val episodes = db.episodeDao().getEpisodesForSeries(series.id)
        val absolute = episodes.first { it.episodeNumber == 112 }
        assertThat(absolute.absoluteNumber).isEqualTo(112)
        assertThat(episodes.any { it.seasonNumber == 0 && it.episodeNumber == 1 }).isTrue()
    }

    @Test
    fun dateEpisodesRememberTheirAirDate() = runTest {
        val id = folder(MediaCategory.SERIES, "TV")
        walker.put("", "Daily.Show.2024.03.15.mkv")

        scanner.scan(id)

        val episode = db.episodeDao().getEpisodesForSeries(db.seriesDao().getAllSeries().single().id).single()
        assertThat(episode.airDate).isEqualTo("2024-03-15")
    }

    @Test
    fun aSeriesNfoAndPosterInTheShowFolderAreUsed() = runTest {
        val id = folder(MediaCategory.SERIES, "TV")
        walker.put("Show", "tvshow.nfo", uri = "content://disk/tvshow.nfo")
        walker.put("Show", "poster.jpg", uri = "content://disk/show-poster")
        walker.put("Show/Season 1", "Show.S01E01.mkv")
        sidecars.files["content://disk/tvshow.nfo"] = "<tvshow><title>Show Officiel</title><year>2015</year></tvshow>"

        scanner.scan(id)

        val series = db.seriesDao().getAllSeries().single()
        assertThat(series.title).isEqualTo("Show Officiel")
        assertThat(series.firstAirDate).isEqualTo("2015")
        assertThat(series.posterPath).isEqualTo("content://disk/show-poster")
    }

    // endregion

    // region moves, absence, availability

    @Test
    fun aRenamedOrMovedFileKeepsItsRowAndItsProgress() = runTest {
        val id = folder(MediaCategory.MOVIES)
        walker.put("old", "Dune.2021.mkv", uri = "content://disk/old/dune")
        inspector.contentIds["content://disk/old/dune"] = "dune-bytes"
        scanner.scan(id)
        val original = db.mediaFileDao().getMediaFilesByFolder(id).single()
        db.watchStateDao().upsertWatchState(WatchStateEntity(mediaFileId = original.id, positionMs = 3_000_000, durationMs = 7_200_000))
        val inspected = inspector.inspections

        walker.remove("content://disk/old/dune")
        walker.put("new", "Dune (2021) - Renamed.mkv", uri = "content://disk/new/dune")
        inspector.contentIds["content://disk/new/dune"] = "dune-bytes"
        val summary = scanner.scan(id)

        assertThat(summary.moved).isEqualTo(1)
        assertThat(summary.added).isEqualTo(0)
        assertThat(summary.markedUnavailable).isEqualTo(0)
        assertThat(inspector.inspections).isEqualTo(inspected) // not probed again
        val moved = db.mediaFileDao().getMediaFilesByFolder(id).single()
        assertThat(moved.id).isEqualTo(original.id)
        assertThat(moved.uri).isEqualTo("content://disk/new/dune")
        assertThat(moved.fileName).isEqualTo("Dune (2021) - Renamed.mkv")
        assertThat(moved.movieId).isEqualTo(original.movieId)
        assertThat(db.watchStateDao().getWatchState(original.id)?.positionMs).isEqualTo(3_000_000)
        assertThat(db.movieDao().getAllMovies()).hasSize(1)
    }

    @Test
    fun aVanishedFileIsMarkedUnavailableNeverDeleted() = runTest {
        val id = folder(MediaCategory.MOVIES)
        walker.put("", "Dune.2021.mkv", uri = "content://disk/dune")
        walker.put("", "Alien.1979.mkv", uri = "content://disk/alien")
        scanner.scan(id)

        walker.remove("content://disk/alien")
        val summary = scanner.scan(id)

        assertThat(summary.markedUnavailable).isEqualTo(1)
        val files = db.mediaFileDao().getMediaFilesByFolder(id)
        assertThat(files).hasSize(2)
        assertThat(files.first { it.fileName.startsWith("Alien") }.isAvailable).isFalse()
        assertThat(files.first { it.fileName.startsWith("Dune") }.isAvailable).isTrue()
        assertThat(db.movieDao().getAllMovies()).hasSize(2)
    }

    @Test
    fun aFileThatComesBackIsAvailableAgainWithItsHistory() = runTest {
        val id = folder(MediaCategory.MOVIES)
        walker.put("", "Dune.2021.mkv", uri = "content://disk/dune")
        scanner.scan(id)
        val fileId = db.mediaFileDao().getMediaFilesByFolder(id).single().id
        walker.remove("content://disk/dune")
        scanner.scan(id)
        assertThat(db.mediaFileDao().getMediaFileById(fileId)?.isAvailable).isFalse()

        walker.put("", "Dune.2021.mkv", uri = "content://disk/dune")
        val summary = scanner.scan(id)

        assertThat(summary.unchanged).isEqualTo(1)
        assertThat(db.mediaFileDao().getMediaFileById(fileId)?.isAvailable).isTrue()
    }

    @Test
    fun anUnpluggedDriveMarksEverythingUnavailableAndForgetsNothing() = runTest {
        val id = folder(MediaCategory.MOVIES)
        walker.put("", "Dune.2021.mkv")
        walker.put("", "Alien.1979.mkv")
        scanner.scan(id)

        walker.reachable = false
        val summary = scanner.scan(id)

        assertThat(summary.folderUnreachable).isTrue()
        assertThat(db.mediaFileDao().getMediaFilesByFolder(id).map { it.isAvailable }).containsExactly(false, false)
        assertThat(db.movieDao().getAllMovies()).hasSize(2)

        walker.reachable = true
        scanner.scan(id)
        assertThat(db.mediaFileDao().getMediaFilesByFolder(id).map { it.isAvailable }).containsExactly(true, true)
    }

    @Test
    fun aPartiallyUnreadableTreeDoesNotDeclareFilesGone() = runTest {
        val id = folder(MediaCategory.MOVIES)
        walker.put("a", "Dune.2021.mkv", uri = "content://disk/dune")
        walker.put("b", "Alien.1979.mkv", uri = "content://disk/alien")
        scanner.scan(id)

        walker.remove("content://disk/alien")
        walker.complete = false // folder b could not be listed this time
        val summary = scanner.scan(id)

        assertThat(summary.markedUnavailable).isEqualTo(0)
        assertThat(db.mediaFileDao().getMediaFilesByFolder(id).all { it.isAvailable }).isTrue()
    }

    // endregion

    @Test
    fun progressIsReportedThroughTheScan() = runTest {
        val id = folder(MediaCategory.MOVIES)
        repeat(5) { walker.put("", "Film.$it.2020.mkv") }
        val seen = mutableListOf<ScanProgress>()

        scanner.scan(id) { seen += it }

        assertThat(seen.first().phase).isEqualTo(ScanPhase.LISTING)
        assertThat(seen.last().phase).isEqualTo(ScanPhase.FINISHING)
        val analysing = seen.filter { it.phase == ScanPhase.ANALYZING }
        assertThat(analysing.map { it.processed }).containsExactly(1, 2, 3, 4, 5).inOrder()
        assertThat(analysing.all { it.total == 5 }).isTrue()
    }

    @Test
    fun aMissingFolderIsANoOp() = runTest {
        assertThat(scanner.scan(999).added).isEqualTo(0)
    }

    // region one folder, several categories

    private suspend fun entry(category: MediaCategory) =
        db.libraryFolderDao().insertFolder(LibraryFolderEntity(uri = "content://tree/Media", displayPath = "/storage/emulated/0/Media", category = category))

    private fun mixedDisk() {
        walker.put("", "Dune.2021.mkv", uri = "content://disk/dune")
        walker.put("Séries/Show", "Show.S01E01.mkv", uri = "content://disk/show1")
        walker.put("Séries/Show", "Show.S01E02.mkv", uri = "content://disk/show2")
        walker.put("Animes", "[Grp] Bleach - 044.mkv", uri = "content://disk/bleach")
    }

    @Test
    fun theSameFolderWatchedPerCategoryFilesEachFileOnceUnderTheRightKind() = runTest {
        val movies = entry(MediaCategory.MOVIES)
        val series = entry(MediaCategory.SERIES)
        val anime = entry(MediaCategory.ANIME)
        mixedDisk()

        val first = scanner.scan(movies)
        val second = scanner.scan(series)
        val third = scanner.scan(anime)

        assertThat(first.added).isEqualTo(1)
        assertThat(second.added).isEqualTo(2)
        assertThat(third.added).isEqualTo(1)
        assertThat(db.movieDao().getAllMovies().map { it.title }).containsExactly("Dune")
        assertThat(db.seriesDao().getAllSeries().map { it.title }).containsExactly("Show", "Bleach")
        assertThat(db.mediaFileDao().getMediaFilesByFolder(movies).map { it.fileName }).containsExactly("Dune.2021.mkv")
        assertThat(db.mediaFileDao().getMediaFilesByFolder(anime).map { it.fileName }).containsExactly("[Grp] Bleach - 044.mkv")
    }

    @Test
    fun rescanningNeverMarksASiblingsFilesAsMissing() = runTest {
        val movies = entry(MediaCategory.MOVIES)
        val series = entry(MediaCategory.SERIES)
        mixedDisk()
        scanner.scan(movies); scanner.scan(series)

        val again = scanner.scan(movies)
        scanner.scan(series)

        assertThat(again.markedUnavailable).isEqualTo(0)
        assertThat(db.mediaFileDao().getMediaFilesByFolder(movies).plus(db.mediaFileDao().getMediaFilesByFolder(series)).all { it.isAvailable }).isTrue()
    }

    @Test
    fun addingASeriesEntryLaterTakesTheEpisodesOverWithTheirProgress() = runTest {
        val movies = entry(MediaCategory.MOVIES)
        mixedDisk()
        scanner.scan(movies) // alone, it files everything as movies
        assertThat(db.movieDao().getAllMovies()).hasSize(4)
        val episodeFile = db.mediaFileDao().getMediaFilesByFolder(movies).first { it.fileName == "Show.S01E01.mkv" }
        db.watchStateDao().upsertWatchState(WatchStateEntity(mediaFileId = episodeFile.id, positionMs = 1_234, durationMs = 2_700_000))

        val series = entry(MediaCategory.SERIES)
        scanner.scan(series)
        val afterMovies = scanner.scan(movies)

        assertThat(afterMovies.markedUnavailable).isEqualTo(0)
        val moved = db.mediaFileDao().getMediaFileById(episodeFile.id)!!
        assertThat(moved.folderId).isEqualTo(series)
        assertThat(moved.episodeId).isNotNull()
        assertThat(moved.movieId).isNull()
        assertThat(moved.isAvailable).isTrue()
        assertThat(db.watchStateDao().getWatchState(episodeFile.id)?.positionMs).isEqualTo(1_234)
        // The movie rows created for the episodes are gone; the real movie stays
        assertThat(db.movieDao().getAllMovies().map { it.title }).contains("Dune")
        assertThat(db.movieDao().getAllMovies().map { it.title }).doesNotContain("Show")
        assertThat(db.seriesDao().getAllSeries().map { it.title }).containsExactly("Show", "Bleach")
    }

    // endregion
}
