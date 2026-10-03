package com.lecteur.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lecteur.core.data.identify.MetadataRepository
import com.lecteur.core.database.AppDatabase
import com.lecteur.core.database.entity.EpisodeEntity
import com.lecteur.core.database.entity.LibraryFolderEntity
import com.lecteur.core.database.entity.MediaFileEntity
import com.lecteur.core.database.entity.MovieEntity
import com.lecteur.core.database.entity.SeasonEntity
import com.lecteur.core.database.entity.SeriesAliasEntity
import com.lecteur.core.database.entity.SeriesEntity
import com.lecteur.core.database.entity.WatchStateEntity
import com.lecteur.core.model.CastRef
import com.lecteur.core.model.CollectionRef
import com.lecteur.core.model.EpisodeMetadata
import com.lecteur.core.model.GenreRef
import com.lecteur.core.model.MatchState
import com.lecteur.core.model.MediaCategory
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.MetadataException
import com.lecteur.core.model.MetadataSearchHit
import com.lecteur.core.model.MovieMetadata
import com.lecteur.core.model.SeasonMetadata
import com.lecteur.core.model.SeriesMetadata
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MetadataRepositoryTest {

    private lateinit var db: AppDatabase
    private val provider = FakeProvider()
    private val prefetcher = RecordingPrefetcher()
    private lateinit var repo: MetadataRepository
    private var folderId = 0L
    private var clock = 1_000_000L
    private val now = { clock }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = MetadataRepository(
            provider, db.movieDao(), db.seriesDao(), db.seasonDao(), db.episodeDao(), db.metadataDao(),
            db.mediaFileDao(), db.seriesAliasDao(), prefetcher, com.lecteur.core.data.library.LibraryWriteLock()
        )
        kotlinx.coroutines.runBlocking {
            folderId = db.libraryFolderDao().insertFolder(LibraryFolderEntity(uri = "u", displayPath = "/m", category = MediaCategory.MOVIES))
        }
    }

    @After
    fun tearDown() = db.close()

    // region builders

    private suspend fun addMovie(title: String, year: Int? = null, file: String = title, durationMs: Long? = null, locked: Boolean = false, overview: String? = null, poster: String? = null): Long {
        val id = db.movieDao().insertMovie(MovieEntity(title = title, year = year, matchLocked = locked, overview = overview, posterPath = poster))
        db.mediaFileDao().insertMediaFile(
            MediaFileEntity(
                folderId = folderId, uri = "content://f/$file", displayPath = "/m/$file", fileName = "$file.mkv", size = 1,
                fingerprint = "fp-$file", lastModified = 1, movieId = id, durationMs = durationMs
            )
        )
        return id
    }

    private fun movieMeta(
        id: Long, title: String, year: Int = 2021, runtime: Int? = 155, overview: String? = "Synopsis TMDB",
        genres: List<GenreRef> = listOf(GenreRef(878, "Science-Fiction")),
        cast: List<CastRef> = emptyList(), collection: CollectionRef? = null, poster: String? = "/poster$id.jpg"
    ) = MovieMetadata(
        tmdbId = id, imdbId = "tt$id", title = title, originalTitle = title, releaseDate = "$year-10-22", year = year, overview = overview,
        runtimeMinutes = runtime, rating = 7.8f, certification = "12", posterPath = poster, backdropPath = "/back$id.jpg",
        logoPath = "/logo$id.png", trailerKey = "yt$id", genres = genres, cast = cast, collection = collection
    )

    private fun movieHit(id: Long, title: String, year: Int?, popularity: Double = 10.0) =
        MetadataSearchHit(id, MediaKind.MOVIE, title, title, year, null, null, popularity)

    private fun seriesHit(id: Long, title: String, year: Int?) = MetadataSearchHit(id, MediaKind.SERIES, title, title, year, null, null, 10.0)

    private fun seriesMeta(id: Long, title: String, seasons: Map<Int, Int>, year: Int = 2008, airDates: (Int, Int) -> String? = { _, _ -> null }) = SeriesMetadata(
        tmdbId = id, imdbId = "tt$id", title = title, originalTitle = title, firstAirDate = "$year-01-20", year = year, status = "Ended",
        overview = "Synopsis série", rating = 8.9f, certification = "16", posterPath = "/sp$id.jpg", backdropPath = "/sb$id.jpg", logoPath = null,
        trailerKey = null, typicalRuntimeMinutes = 45, genres = listOf(GenreRef(18, "Drame")), cast = emptyList(),
        seasons = seasons.map { (number, count) ->
            SeasonMetadata(
                seasonNumber = number, name = "Saison $number", overview = null, posterPath = "/season$id-$number.jpg", airDate = null, episodeCount = count,
                episodes = (1..count).map { EpisodeMetadata(it, "Épisode $it", "Résumé $it", "/still$id-$number-$it.jpg", airDates(number, it), 45) }
            )
        }
    )

    private suspend fun addEpisodeWithFile(seriesId: Long, season: Int, number: Int, absolute: Int? = null, airDate: String? = null, file: String = "s${seriesId}e$season.$number"): Pair<Long, Long> {
        val seasonId = db.seasonDao().getSeasonByNumber(seriesId, season)?.id
            ?: db.seasonDao().insertSeason(SeasonEntity(seriesId = seriesId, seasonNumber = season))
        val episodeId = db.episodeDao().insertEpisode(
            EpisodeEntity(seriesId = seriesId, seasonId = seasonId, seasonNumber = season, episodeNumber = number, absoluteNumber = absolute, airDate = airDate)
        )
        val fileId = db.mediaFileDao().insertMediaFile(
            MediaFileEntity(
                folderId = folderId, uri = "content://f/$file", displayPath = "/m/$file", fileName = "$file.mkv", size = 1,
                fingerprint = "fp-$file", lastModified = 1, episodeId = episodeId
            )
        )
        return episodeId to fileId
    }

    // endregion

    // region movies

    @Test
    fun aMovieIsIdentifiedWithItsMetadataGenresCastCollectionAndArtwork() = runTest {
        val id = addMovie("Dune", 2021, durationMs = 155 * 60_000L)
        provider.searchHandler = { _, _, _ -> listOf(movieHit(438631, "Dune", 2021)) }
        provider.movies[438631] = movieMeta(
            438631, "Dune",
            genres = listOf(GenreRef(878, "Science-Fiction"), GenreRef(12, "Aventure")),
            cast = listOf(
                CastRef(1, "Timothée Chalamet", "/tc.jpg", "Paul", 0),
                CastRef(2, "Denis Villeneuve", null, "Réalisateur", 1000, isDirector = true)
            ),
            collection = CollectionRef(726871, "Dune - Saga", posterPath = "/saga.jpg")
        )

        val result = repo.identifyPending(now)

        assertThat(result.identified).isEqualTo(1)
        val movie = db.movieDao().getMovieById(id)!!
        assertThat(movie.tmdbId).isEqualTo(438631)
        assertThat(movie.matchState).isEqualTo(MatchState.IDENTIFIED)
        assertThat(movie.imdbId).isEqualTo("tt438631")
        assertThat(movie.overview).isEqualTo("Synopsis TMDB")
        assertThat(movie.runtime).isEqualTo(155)
        assertThat(movie.certification).isEqualTo("12")
        assertThat(movie.posterPath).isEqualTo("/poster438631.jpg")
        assertThat(movie.logoPath).isEqualTo("/logo438631.png")
        assertThat(movie.trailerKey).isEqualTo("yt438631")
        assertThat(movie.matchAttemptedAt).isEqualTo(clock)
        assertThat(db.metadataDao().getGenresForMovie(id).map { it.name }).containsExactly("Science-Fiction", "Aventure")
        assertThat(db.metadataDao().getCastForMovie(id).map { it.personName to it.isDirector })
            .containsExactly("Timothée Chalamet" to false, "Denis Villeneuve" to true).inOrder()
        assertThat(db.metadataDao().getCollectionById(movie.collectionId!!)?.name).isEqualTo("Dune - Saga")
        assertThat(prefetcher.urls).contains("https://image.tmdb.org/t/p/w342/poster438631.jpg")
        assertThat(prefetcher.urls).contains("https://image.tmdb.org/t/p/w780/back438631.jpg")
        assertThat(prefetcher.urls).contains("https://image.tmdb.org/t/p/w185/tc.jpg")
    }

    @Test
    fun aWrongYearInTheFileNameStillFindsTheFilm() = runTest {
        val id = addMovie("Dune", 2020)
        // The year-restricted search finds nothing; the search without a year does
        provider.searchHandler = { _, _, year -> if (year != null) emptyList() else listOf(movieHit(438631, "Dune", 2021)) }
        provider.movies[438631] = movieMeta(438631, "Dune")

        val result = repo.identifyPending(now)

        assertThat(result.identified).isEqualTo(1)
        assertThat(provider.searches.map { it.third }).containsExactly(2020, null).inOrder()
        assertThat(db.movieDao().getMovieById(id)?.tmdbId).isEqualTo(438631)
    }

    @Test
    fun theFilesRealDurationSeparatesTwoFilmsOfTheSameTitleAndYearUnknown() = runTest {
        val id = addMovie("Dune", null, durationMs = 137 * 60_000L)
        provider.searchHandler = { _, _, _ -> listOf(movieHit(1, "Dune", 2021, popularity = 99.0), movieHit(2, "Dune", 1984, popularity = 5.0)) }
        provider.movies[1] = movieMeta(1, "Dune", year = 2021, runtime = 155)
        provider.movies[2] = movieMeta(2, "Dune", year = 1984, runtime = 137)

        repo.identifyPending(now)

        // Popularity would pick 2021; the 137 minutes of the file say 1984
        assertThat(db.movieDao().getMovieById(id)?.tmdbId).isEqualTo(2)
    }

    @Test
    fun twoVersionsOfTheSameFilmEndUpUnderOneMovie() = runTest {
        val hd = addMovie("Dune", 2021, file = "Dune.1080p")
        val uhd = addMovie("Dune 4K", 2021, file = "Dune.2160p")
        provider.searchHandler = { _, _, _ -> listOf(movieHit(438631, "Dune", 2021)) }
        provider.movies[438631] = movieMeta(438631, "Dune")

        val result = repo.identifyPending(now)

        assertThat(result.processed).isEqualTo(2)
        val movies = db.movieDao().getAllMovies()
        assertThat(movies).hasSize(1)
        assertThat(db.mediaFileDao().getMediaFilesForMovie(movies.single().id)).hasSize(2)
        assertThat(movies.single().id).isAnyOf(hd, uhd)
    }

    @Test
    fun aDoubtfulMatchIsProposedAsToVerify() = runTest {
        val id = addMovie("Blade Runner 2049", 2017)
        provider.searchHandler = { _, _, _ -> listOf(movieHit(78, "Blade Runner", 1982)) }
        provider.movies[78] = movieMeta(78, "Blade Runner", year = 1982, runtime = 117)

        val result = repo.identifyPending(now)

        assertThat(result.toVerify).isEqualTo(1)
        val movie = db.movieDao().getMovieById(id)!!
        assertThat(movie.matchState).isEqualTo(MatchState.TO_VERIFY)
        assertThat(movie.tmdbId).isEqualTo(78) // the proposal is there for the review screen
        assertThat(movie.matchLocked).isFalse()
    }

    @Test
    fun anUnknownTitleStaysUnidentifiedAndIsNotSearchedAgainUntilARetryDelayPasses() = runTest {
        val id = addMovie("Vacances Famille", 2019)

        val first = repo.identifyPending(now)
        assertThat(first.unidentified).isEqualTo(1)
        assertThat(db.movieDao().getMovieById(id)?.matchAttemptedAt).isEqualTo(clock)
        val searchesAfterFirst = provider.searches.size

        val second = repo.identifyPending(now)
        assertThat(second.processed).isEqualTo(0)
        assertThat(provider.searches.size).isEqualTo(searchesAfterFirst)

        clock += MetadataRepository.RETRY_AFTER_MS + 1
        val third = repo.identifyPending(now)
        assertThat(third.unidentified).isEqualTo(1)
        assertThat(provider.searches.size).isGreaterThan(searchesAfterFirst)
    }

    @Test
    fun withoutNetworkNothingIsMarkedAsTried() = runTest {
        val id = addMovie("Dune", 2021)
        provider.offline = true

        val result = repo.identifyPending(now)

        assertThat(result.offline).isTrue()
        assertThat(result.processed).isEqualTo(0)
        assertThat(db.movieDao().getMovieById(id)?.matchAttemptedAt).isNull()

        provider.offline = false
        provider.searchHandler = { _, _, _ -> listOf(movieHit(438631, "Dune", 2021)) }
        provider.movies[438631] = movieMeta(438631, "Dune")
        assertThat(repo.identifyPending(now).identified).isEqualTo(1)
    }

    @Test
    fun withoutAnApiKeyNothingIsAttempted() = runTest {
        addMovie("Dune", 2021)
        provider.isConfigured = false

        val result = repo.identifyPending(now)

        assertThat(result.notConfigured).isTrue()
        assertThat(provider.searches).isEmpty()
        assertThrows(MetadataException.NotConfigured::class.java) { kotlinx.coroutines.runBlocking { repo.search(MediaKind.MOVIE, "Dune") } }
    }

    @Test
    fun lockedItemsAreLeftAlone() = runTest {
        addMovie("Dune", 2021, locked = true)
        provider.searchHandler = { _, _, _ -> listOf(movieHit(438631, "Dune", 2021)) }

        assertThat(repo.identifyPending(now).processed).isEqualTo(0)
        assertThat(provider.searches).isEmpty()
    }

    @Test
    fun aSidecarsSynopsisAndPosterWinOverTmdbButTheTitleComesFromTmdb() = runTest {
        val id = addMovie("dune", 2021, overview = "Synopsis du nfo", poster = "content://disk/poster")
        provider.searchHandler = { _, _, _ -> listOf(movieHit(438631, "Dune", 2021)) }
        provider.movies[438631] = movieMeta(438631, "Dune")

        repo.identifyPending(now)

        val movie = db.movieDao().getMovieById(id)!!
        assertThat(movie.overview).isEqualTo("Synopsis du nfo")
        assertThat(movie.posterPath).isEqualTo("content://disk/poster")
        assertThat(movie.backdropPath).isEqualTo("/back438631.jpg")
        assertThat(movie.title).isEqualTo("Dune")
        assertThat(prefetcher.urls.none { it.contains("content://") }).isTrue()
    }

    @Test
    fun aTmdbIdFromASidecarSkipsTheSearch() = runTest {
        val id = addMovie("Titre Local", 2021)
        db.movieDao().updateMovie(db.movieDao().getMovieById(id)!!.copy(tmdbId = 438631))
        provider.movies[438631] = movieMeta(438631, "Dune")

        val result = repo.identifyPending(now)

        assertThat(result.identified).isEqualTo(1)
        assertThat(provider.searches).isEmpty()
        assertThat(db.movieDao().getMovieById(id)?.title).isEqualTo("Dune")
    }

    @Test
    fun aSidecarsUnknownTmdbIdFallsBackToUnidentified() = runTest {
        val id = addMovie("Titre Local", 2021)
        db.movieDao().updateMovie(db.movieDao().getMovieById(id)!!.copy(tmdbId = 999))

        val result = repo.identifyPending(now)

        assertThat(result.unidentified).isEqualTo(1)
        assertThat(result.offline).isFalse()
    }

    // endregion

    // region manual correction

    @Test
    fun aManualCorrectionOverwritesLocksAndSurvivesTheNextIdentificationPass() = runTest {
        val id = addMovie("Dune", 2021)
        provider.searchHandler = { _, _, _ -> listOf(movieHit(1, "Dune", 2021)) }
        provider.movies[1] = movieMeta(1, "Dune")
        provider.movies[2] = movieMeta(2, "Dune", year = 1984, runtime = 137, overview = "Version 1984")
        repo.identifyPending(now)

        val survivor = repo.applyManual(MediaKind.MOVIE, id, 2, now)

        assertThat(survivor).isEqualTo(id)
        val movie = db.movieDao().getMovieById(id)!!
        assertThat(movie.tmdbId).isEqualTo(2)
        assertThat(movie.year).isEqualTo(1984)
        assertThat(movie.overview).isEqualTo("Version 1984")
        assertThat(movie.matchState).isEqualTo(MatchState.IDENTIFIED)
        assertThat(movie.matchLocked).isTrue()

        db.movieDao().updateMovie(movie.copy(matchState = MatchState.UNIDENTIFIED, matchAttemptedAt = null)) // even if something resets it
        provider.searches.clear()
        repo.identifyPending(now)
        assertThat(provider.searches).isEmpty()
        assertThat(db.movieDao().getMovieById(id)?.tmdbId).isEqualTo(2)
    }

    @Test
    fun correctingToAFilmAlreadyInTheLibraryMergesTheFiles() = runTest {
        val wrong = addMovie("Dune 1984 Rip", file = "wrong")
        val right = addMovie("Dune", 1984, file = "right")
        provider.movies[2] = movieMeta(2, "Dune", year = 1984)
        repo.applyManual(MediaKind.MOVIE, right, 2, now)

        val survivor = repo.applyManual(MediaKind.MOVIE, wrong, 2, now)

        assertThat(survivor).isEqualTo(right)
        assertThat(db.movieDao().getMovieById(wrong)).isNull()
        assertThat(db.mediaFileDao().getMediaFilesForMovie(right)).hasSize(2)
    }

    @Test
    fun anUnknownTmdbIdIsReportedNotSwallowed() = runTest {
        val id = addMovie("Dune", 2021)
        assertThrows(MetadataException.NotFound::class.java) { kotlinx.coroutines.runBlocking { repo.applyManual(MediaKind.MOVIE, id, 123456, now) } }
        assertThat(db.movieDao().getMovieById(id)?.tmdbId).isNull()
    }

    @Test
    fun refreshReplacesWhatTmdbNowSays() = runTest {
        val id = addMovie("Dune", 2021)
        provider.searchHandler = { _, _, _ -> listOf(movieHit(1, "Dune", 2021)) }
        provider.movies[1] = movieMeta(1, "Dune", overview = "Ancien résumé")
        repo.identifyPending(now)

        provider.movies[1] = movieMeta(1, "Dune", overview = "Résumé corrigé", genres = listOf(GenreRef(12, "Aventure")))
        assertThat(repo.refresh(MediaKind.MOVIE, id, now)).isTrue()

        assertThat(db.movieDao().getMovieById(id)?.overview).isEqualTo("Résumé corrigé")
        assertThat(db.metadataDao().getGenresForMovie(id).map { it.name }).containsExactly("Aventure")
        assertThat(repo.refresh(MediaKind.MOVIE, addMovie("Autre", file = "autre"), now)).isFalse() // nothing to refresh without a match
    }

    @Test
    fun confirmingAProposalLocksIt() = runTest {
        val id = addMovie("Blade Runner 2049", 2017)
        db.movieDao().updateMovie(db.movieDao().getMovieById(id)!!.copy(matchState = MatchState.TO_VERIFY, tmdbId = 78))

        repo.confirm(MediaKind.MOVIE, id)

        val movie = db.movieDao().getMovieById(id)!!
        assertThat(movie.matchState).isEqualTo(MatchState.IDENTIFIED)
        assertThat(movie.matchLocked).isTrue()

        repo.unlock(MediaKind.MOVIE, id)
        assertThat(db.movieDao().getMovieById(id)?.matchLocked).isFalse()
    }

    // endregion

    // region series

    private suspend fun addSeries(title: String, year: Int? = null, alias: String? = null): Long {
        val id = db.seriesDao().insertSeries(SeriesEntity(title = title, firstAirDate = year?.toString()))
        alias?.let { db.seriesAliasDao().put(SeriesAliasEntity(it, id)) }
        return id
    }

    @Test
    fun aSeriesIsIdentifiedAndItsWholeEpisodeTreeIsBuiltAroundTheEpisodesWeOwn() = runTest {
        val series = addSeries("Breaking Bad", 2008, alias = "breaking bad")
        val (ownedEpisode, ownedFile) = addEpisodeWithFile(series, 1, 2)
        db.watchStateDao().upsertWatchState(WatchStateEntity(mediaFileId = ownedFile, positionMs = 1_000, durationMs = 2_700_000))
        provider.searchHandler = { kind, _, _ -> if (kind == MediaKind.SERIES) listOf(seriesHit(1396, "Breaking Bad", 2008)) else emptyList() }
        provider.series[1396] = seriesMeta(1396, "Breaking Bad", mapOf(0 to 2, 1 to 7, 2 to 13))

        val result = repo.identifyPending(now)

        assertThat(result.identified).isEqualTo(1)
        val stored = db.seriesDao().getSeriesById(series)!!
        assertThat(stored.tmdbId).isEqualTo(1396)
        assertThat(stored.matchState).isEqualTo(MatchState.IDENTIFIED)
        assertThat(stored.status).isEqualTo("Ended")
        assertThat(stored.firstAirDate).isEqualTo("2008-01-20")
        assertThat(db.metadataDao().getGenresForSeries(series).map { it.name }).containsExactly("Drame")

        val seasons = db.seasonDao().getSeasonsForSeries(series)
        assertThat(seasons.map { it.seasonNumber }).containsExactly(0, 1, 2).inOrder()
        val episodes = db.episodeDao().getEpisodesForSeries(series)
        assertThat(episodes).hasSize(22)
        assertThat(episodes.first { it.id == ownedEpisode }.title).isEqualTo("Épisode 2") // same row, now with its metadata
        assertThat(db.mediaFileDao().getMediaFilesForEpisode(ownedEpisode)).hasSize(1)
        assertThat(db.watchStateDao().getWatchState(ownedFile)?.positionMs).isEqualTo(1_000)
        assertThat(db.episodeDao().getEpisodesWithoutFiles(series)).hasSize(21) // the ones we know of but do not own
        // Stills are warmed only for the episodes we can actually play
        assertThat(prefetcher.urls.filter { it.contains("/w300/") }).containsExactly("https://image.tmdb.org/t/p/w300/still1396-1-2.jpg")
    }

    @Test
    fun theOfficialTitleBecomesAnAliasSoNewEpisodesFindTheSeries() = runTest {
        val series = addSeries("Le Prisonnier", 1967, alias = "le prisonnier")
        provider.searchHandler = { _, _, _ -> listOf(seriesHit(5, "The Prisoner", 1967)) }
        provider.series[5] = seriesMeta(5, "The Prisoner", mapOf(1 to 3), year = 1967)

        repo.applyManual(MediaKind.SERIES, series, 5, now)

        assertThat(db.seriesAliasDao().findSeriesId("the prisoner")).isEqualTo(series)
        assertThat(db.seriesAliasDao().findSeriesId("le prisonnier")).isEqualTo(series)
    }

    @Test
    fun absoluteAnimeNumbersAreMovedOntoTheirRealSeasonAndEpisode() = runTest {
        val series = addSeries("Naruto", alias = "naruto")
        // Parsed as "[Group] Naruto - 112": filed provisionally in season 1 with the absolute number
        val (provisional, fileId) = addEpisodeWithFile(series, 1, 112, absolute = 112)
        provider.series[10] = seriesMeta(10, "Naruto", mapOf(0 to 3, 1 to 52, 2 to 60, 3 to 40), year = 2002)

        repo.applyManual(MediaKind.SERIES, series, 10, now)

        val file = db.mediaFileDao().getMediaFileById(fileId)!!
        val real = db.episodeDao().getEpisodeById(file.episodeId!!)!!
        assertThat(real.seasonNumber to real.episodeNumber).isEqualTo(2 to 60) // 112 = 52 + 60
        assertThat(real.title).isEqualTo("Épisode 60")
        assertThat(real.absoluteNumber).isEqualTo(112)
        assertThat(db.episodeDao().getEpisodeById(provisional)).isNull()
        assertThat(db.episodeDao().getEpisode(series, 1, 112)).isNull()
    }

    @Test
    fun anAbsoluteNumberBeyondTheKnownEpisodesIsLeftWhereItIs() = runTest {
        val series = addSeries("Naruto", alias = "naruto")
        val (provisional, fileId) = addEpisodeWithFile(series, 1, 900, absolute = 900)
        provider.series[10] = seriesMeta(10, "Naruto", mapOf(1 to 52, 2 to 60), year = 2002)

        repo.applyManual(MediaKind.SERIES, series, 10, now)

        assertThat(db.mediaFileDao().getMediaFileById(fileId)?.episodeId).isEqualTo(provisional)
    }

    @Test
    fun aRealSeasonOneEpisodeIsNotMistakenForAnAbsoluteNumber() = runTest {
        val series = addSeries("Show", alias = "show")
        val (episode, fileId) = addEpisodeWithFile(series, 1, 5, absolute = 5)
        provider.series[10] = seriesMeta(10, "Show", mapOf(1 to 12, 2 to 12))

        repo.applyManual(MediaKind.SERIES, series, 10, now)

        assertThat(db.mediaFileDao().getMediaFileById(fileId)?.episodeId).isEqualTo(episode)
    }

    @Test
    fun dateNumberedEpisodesAreMatchedByAirDate() = runTest {
        val series = addSeries("Daily Show", alias = "daily show")
        // "Daily.Show.2024.03.15": season = year, episode = month * 100 + day
        val (provisional, fileId) = addEpisodeWithFile(series, 2024, 315, airDate = "2024-03-15")
        provider.series[20] = seriesMeta(20, "Daily Show", mapOf(29 to 3), year = 1996) { season, episode ->
            if (season == 29) "2024-03-${10 + episode * 2}" else null // episodes aired on the 12th, 14th and 16th... and 15th is not one: use 3 -> 16
        }
        // Make the 2nd episode air on the 15th
        provider.series[20] = provider.series[20]!!.let { meta ->
            meta.copy(seasons = meta.seasons.map { season ->
                season.copy(episodes = season.episodes.map { if (it.episodeNumber == 2) it.copy(airDate = "2024-03-15") else it })
            })
        }

        repo.applyManual(MediaKind.SERIES, series, 20, now)

        val real = db.episodeDao().getEpisodeById(db.mediaFileDao().getMediaFileById(fileId)!!.episodeId!!)!!
        assertThat(real.seasonNumber to real.episodeNumber).isEqualTo(29 to 2)
        assertThat(db.episodeDao().getEpisodeById(provisional)).isNull()
        assertThat(db.seasonDao().getSeasonByNumber(series, 2024)).isNull() // the empty year-season is cleaned up
    }

    @Test
    fun twoRowsOfTheSameShowAreMergedAndTheirAliasesFollow() = runTest {
        val original = addSeries("Breaking Bad", alias = "breaking bad")
        val french = addSeries("Breaking Bad VF", alias = "breaking bad vf")
        addEpisodeWithFile(original, 1, 1)
        val (_, frenchFile) = addEpisodeWithFile(french, 1, 2)
        addEpisodeWithFile(french, 1, 1, file = "dup-s1e1") // the same episode seen twice: one version per row
        provider.searchHandler = { _, _, _ -> listOf(seriesHit(1396, "Breaking Bad", null)) }
        provider.series[1396] = seriesMeta(1396, "Breaking Bad", mapOf(1 to 7))

        val result = repo.identifyPending(now)

        assertThat(result.processed).isEqualTo(2)
        val remaining = db.seriesDao().getAllSeries()
        assertThat(remaining).hasSize(1)
        val survivor = remaining.single().id
        assertThat(db.seriesAliasDao().findSeriesId("breaking bad vf")).isEqualTo(survivor)
        assertThat(db.seriesAliasDao().findSeriesId("breaking bad")).isEqualTo(survivor)
        assertThat(db.mediaFileDao().getMediaFileById(frenchFile)?.episodeId).isNotNull()
        val episodeOne = db.episodeDao().getEpisode(survivor, 1, 1)!!
        assertThat(db.mediaFileDao().getMediaFilesForEpisode(episodeOne.id)).hasSize(2) // both versions of S01E01
    }

    @Test
    fun rematchingASeriesDropsStaleTmdbEpisodesButKeepsOwnedOnes() = runTest {
        val series = addSeries("Show", alias = "show")
        val (owned, fileId) = addEpisodeWithFile(series, 1, 1)
        provider.series[1] = seriesMeta(1, "Show", mapOf(1 to 5, 2 to 5))
        provider.series[2] = seriesMeta(2, "Autre Show", mapOf(1 to 3))
        repo.applyManual(MediaKind.SERIES, series, 1, now)
        assertThat(db.episodeDao().getEpisodesForSeries(series)).hasSize(10)

        repo.applyManual(MediaKind.SERIES, series, 2, now)

        val episodes = db.episodeDao().getEpisodesForSeries(series)
        assertThat(episodes).hasSize(3)
        assertThat(episodes.map { it.title }).containsExactly("Épisode 1", "Épisode 2", "Épisode 3")
        assertThat(db.mediaFileDao().getMediaFileById(fileId)?.episodeId).isEqualTo(owned)
        assertThat(db.seasonDao().getSeasonByNumber(series, 2)).isNull() // season 2 of the old show is gone
        assertThat(db.seriesDao().getSeriesById(series)?.tmdbId).isEqualTo(2)
    }

    @Test
    fun anAmbiguousSeriesWithoutYearGoesToReview() = runTest {
        val series = addSeries("Dune")
        provider.searchHandler = { _, _, _ -> listOf(seriesHit(1, "Dune", 2000), seriesHit(2, "Dune", 2021)) }
        provider.series[1] = seriesMeta(1, "Dune", mapOf(1 to 1), year = 2000)
        provider.series[2] = seriesMeta(2, "Dune", mapOf(1 to 1), year = 2021)

        val result = repo.identifyPending(now)

        assertThat(result.toVerify).isEqualTo(1)
        assertThat(db.seriesDao().getSeriesById(series)?.matchState).isEqualTo(MatchState.TO_VERIFY)
    }

    @Test
    fun aSeriesYearHintNarrowsTheSearch() = runTest {
        addSeries("Doctor Who", 1963)
        provider.searchHandler = { _, _, year -> if (year == 1963) listOf(seriesHit(7, "Doctor Who", 1963)) else listOf(seriesHit(8, "Doctor Who", 2005)) }
        provider.series[7] = seriesMeta(7, "Doctor Who", mapOf(1 to 2), year = 1963)

        repo.identifyPending(now)

        assertThat(db.seriesDao().getAllSeries().single().tmdbId).isEqualTo(7)
    }

    // endregion
}
