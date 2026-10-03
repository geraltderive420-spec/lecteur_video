package com.lecteur.core.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lecteur.core.data.browse.BrowseEntry
import com.lecteur.core.data.browse.BrowseRepository
import com.lecteur.core.data.browse.DirectoryBrowser
import com.lecteur.core.data.browse.DirectoryListing
import com.lecteur.core.data.details.Badges
import com.lecteur.core.data.details.DetailRepository
import com.lecteur.core.data.library.FavoritesRepository
import com.lecteur.core.data.library.HomeRepository
import com.lecteur.core.data.library.LibraryRepository
import com.lecteur.core.data.library.WatchedRepository
import com.lecteur.core.data.playback.PlayPlanner
import com.lecteur.core.data.playback.PlaybackQueueStore
import com.lecteur.core.data.search.SearchIndex
import com.lecteur.core.data.search.SearchRepository
import com.lecteur.core.data.settings.AppearanceRepository
import com.lecteur.core.data.settings.HomeLayoutRepository
import com.lecteur.core.data.settings.LibraryViewRepository
import com.lecteur.core.data.settings.OnboardingRepository
import com.lecteur.core.database.AppDatabase
import com.lecteur.core.database.entity.AudioTrackInfoEntity
import com.lecteur.core.database.entity.CastMemberEntity
import com.lecteur.core.database.entity.EpisodeEntity
import com.lecteur.core.database.entity.LibraryFolderEntity
import com.lecteur.core.database.entity.MediaFileEntity
import com.lecteur.core.database.entity.MovieEntity
import com.lecteur.core.database.entity.PersonEntity
import com.lecteur.core.database.entity.SeasonEntity
import com.lecteur.core.database.entity.SeriesEntity
import com.lecteur.core.database.entity.WatchStateEntity
import com.lecteur.core.database.relation.PersonRow
import com.lecteur.core.database.relation.TitleRow
import com.lecteur.core.model.AudioCodec
import com.lecteur.core.model.HdrType
import com.lecteur.core.model.HomeRow
import com.lecteur.core.model.LibraryFilters
import com.lecteur.core.model.LibraryQuery
import com.lecteur.core.model.LibrarySection
import com.lecteur.core.model.LibrarySort
import com.lecteur.core.model.LibraryView
import com.lecteur.core.model.MediaCategory
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.PlayPlan
import com.lecteur.core.model.PosterSize
import com.lecteur.core.model.QueueEntry
import com.lecteur.core.model.SeriesPlayKind
import com.lecteur.core.model.SortField
import com.lecteur.core.model.SortOrder
import com.lecteur.core.model.ThemeMode
import com.lecteur.core.model.WatchStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class LibraryUiDataTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private var folderId = 0L
    private var counter = 0

    private lateinit var favorites: FavoritesRepository
    private lateinit var library: LibraryRepository
    private lateinit var home: HomeRepository
    private lateinit var details: DetailRepository
    private lateinit var planner: PlayPlanner
    private lateinit var watched: WatchedRepository

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        folderId = db.libraryFolderDao().insertFolder(LibraryFolderEntity(uri = "tree://f", displayPath = "/f", category = MediaCategory.GENERIC))
        favorites = FavoritesRepository(db.userListDao())
        library = LibraryRepository(db.libraryDao(), db.libraryFolderDao())
        home = HomeRepository(db.libraryDao(), library, favorites)
        details = DetailRepository(db.movieDao(), db.seriesDao(), db.libraryDao(), db.metadataDao(), favorites)
        planner = PlayPlanner(db.mediaFileDao(), db.movieDao(), db.libraryDao())
        watched = WatchedRepository(db.mediaFileDao(), db.watchStateDao(), db.episodeDao())
    }

    @After
    fun tearDown() = db.close()

    // region fixtures

    private suspend fun movie(title: String, year: Int? = 2000, addedAt: Long = 0) =
        db.movieDao().insertMovie(MovieEntity(title = title, sortTitle = title, year = year, addedAt = addedAt))

    private suspend fun movieFile(movieId: Long, height: Int = 1080, available: Boolean = true, size: Long = 100, duration: Long = 6_000_000): Long {
        val n = counter++
        return db.mediaFileDao().insertMediaFile(
            MediaFileEntity(
                folderId = folderId, uri = "content://m/$n", displayPath = "/m/$n", fileName = "movie$n.mkv", size = size, fingerprint = "fp$n",
                lastModified = 1, width = height * 16 / 9, height = height, movieId = movieId, isAvailable = available, durationMs = duration
            )
        )
    }

    private suspend fun series(title: String) = db.seriesDao().insertSeries(SeriesEntity(title = title, sortTitle = title))

    private suspend fun episode(seriesId: Long, season: Int, number: Int, title: String? = null): Long {
        val seasonId = db.seasonDao().getSeasonByNumber(seriesId, season)?.id
            ?: db.seasonDao().insertSeason(SeasonEntity(seriesId = seriesId, seasonNumber = season))
        return db.episodeDao().insertEpisode(
            EpisodeEntity(seriesId = seriesId, seasonId = seasonId, seasonNumber = season, episodeNumber = number, title = title)
        )
    }

    private suspend fun episodeFile(episodeId: Long, height: Int = 1080, available: Boolean = true): Long {
        val n = counter++
        return db.mediaFileDao().insertMediaFile(
            MediaFileEntity(
                folderId = folderId, uri = "content://e/$n", displayPath = "/e/$n", fileName = "ep$n.mkv", size = 10, fingerprint = "efp$n",
                lastModified = 1, width = 1920, height = height, episodeId = episodeId, isAvailable = available, durationMs = 2_400_000
            )
        )
    }

    private suspend fun watch(fileId: Long, position: Long = 0, duration: Long = 2_400_000, completed: Boolean = false, at: Long = 1_000) {
        db.watchStateDao().upsertWatchState(
            WatchStateEntity(mediaFileId = fileId, positionMs = position, durationMs = duration, isCompleted = completed, lastWatchedAt = at)
        )
    }

    // endregion

    // region home

    @Test
    fun continueWatchingShowsOneCardPerSeriesAndPerFilm() = runTest {
        val show = series("Show")
        watch(episodeFile(episode(show, 1, 1)), 600_000, at = 10)
        watch(episodeFile(episode(show, 1, 2, "Pilot")), 700_000, at = 30)
        val film = movie("Film")
        watch(movieFile(film), 3_000_000, duration = 6_000_000, at = 20)
        watch(movieFile(film, height = 2160), 1_000_000, duration = 6_000_000, at = 5)

        val items = home.continueWatching.first()
        assertThat(items.map { it.title }).containsExactly("Show", "Film").inOrder()
        assertThat(items[0].subtitle).isEqualTo("S01E02 · Pilot")
        assertThat(items[0].kind).isEqualTo(MediaKind.SERIES)
        assertThat(items[1].progress).isWithin(0.001f).of(0.5f)
        assertThat(items[1].remainingMinutes).isEqualTo(50)
    }

    @Test
    fun anUnidentifiedFileResumesFromItsNameWithoutALibraryTitle() = runTest {
        val n = counter++
        val file = db.mediaFileDao().insertMediaFile(
            MediaFileEntity(folderId = folderId, uri = "content://x/$n", displayPath = "/x", fileName = "Some.Home.Video.mkv", size = 1, fingerprint = "x$n", lastModified = 1, durationMs = 1_000_000)
        )
        watch(file, 500_000, duration = 1_000_000)
        val item = home.continueWatching.first().single()
        assertThat(item.kind).isNull()
        assertThat(item.title).isEqualTo("Some Home Video")
    }

    @Test
    fun nextUpOffersTheEpisodeAfterTheLastOneSeen() = runTest {
        val show = series("Show")
        watch(episodeFile(episode(show, 1, 1)), completed = true, at = 10)
        val next = episode(show, 1, 2, "Second"); val nextFile = episodeFile(next)
        episodeFile(episode(show, 1, 3))

        val item = home.nextUp.first().single()
        assertThat(item.episodeId).isEqualTo(next)
        assertThat(item.mediaFileId).isEqualTo(nextFile)
        assertThat(item.label).isEqualTo("S01E02 · Second")
        assertThat(item.seriesTitle).isEqualTo("Show")
    }

    @Test
    fun recentlyAddedMixesFilmsAndSeriesNewestFirst() = runTest {
        val old = movie("Old", addedAt = 10); movieFile(old)
        val show = db.seriesDao().insertSeries(SeriesEntity(title = "Fresh", sortTitle = "Fresh", addedAt = 30)); episodeFile(episode(show, 1, 1))
        val mid = movie("Mid", addedAt = 20); movieFile(mid)
        assertThat(home.recentlyAdded.first().map { it.title }).containsExactly("Fresh", "Mid", "Old").inOrder()
    }

    @Test
    fun favouritesRowFollowsTheFavouriteToggle() = runTest {
        val a = movie("A"); movieFile(a); val b = movie("B"); movieFile(b)
        assertThat(home.favoriteTitles.first()).isEmpty()
        assertThat(favorites.toggleMovie(b)).isTrue()
        assertThat(home.favoriteTitles.first().map { it.title }).containsExactly("B")
        assertThat(favorites.toggleMovie(b)).isFalse()
        assertThat(home.favoriteTitles.first()).isEmpty()
        assertThat(a).isNotEqualTo(0L)
    }

    @Test
    fun favouritesListIsCreatedOnceAndKeepsItsId() = runTest {
        val first = favorites.listId()
        assertThat(favorites.listId()).isEqualTo(first)
        val show = series("S")
        assertThat(favorites.toggleSeries(show)).isTrue()
        assertThat(favorites.observeSeries(show).first()).isTrue()
        assertThat(favorites.observeMovie(999).first()).isFalse()
    }

    // endregion

    // region library

    @Test
    fun filterOptionsListDecadesAndLanguagesInUse() = runTest {
        movie("A", year = 1994); movie("B", year = 2005); movie("C", year = 2003)
        val file = movieFile(movie("D", year = null))
        db.mediaFileDao().insertAudioTracks(
            listOf(
                AudioTrackInfoEntity(mediaFileId = file, trackIndex = 1, language = "fre"),
                AudioTrackInfoEntity(mediaFileId = file, trackIndex = 2, language = "fra"),
                AudioTrackInfoEntity(mediaFileId = file, trackIndex = 3, language = "eng")
            )
        )
        val options = library.filterOptions(LibrarySection.MOVIES)
        assertThat(options.decades).containsExactly(2000, 1990).inOrder()
        assertThat(options.audioLanguages.map { it.code }).containsExactly("en", "fr")
        assertThat(options.folders.map { it.id }).containsExactly(folderId)
    }

    @Test
    fun observedItemsCarryTheWatchStatus() = runTest {
        val seen = movie("Seen"); watch(movieFile(seen), completed = true)
        val going = movie("Going"); watch(movieFile(going), position = 3_000_000, duration = 6_000_000)
        val fresh = movie("Fresh"); movieFile(fresh)
        val items = library.observe(LibraryQuery(LibrarySection.MOVIES, LibrarySort(SortField.TITLE, SortOrder.ASC), LibraryFilters(), 10)).first()
        assertThat(items.associate { it.title to it.watch }).containsExactly(
            "Fresh", WatchStatus.UNWATCHED, "Going", WatchStatus.IN_PROGRESS, "Seen", WatchStatus.WATCHED
        )
        assertThat(items.first { it.title == "Going" }.progress).isWithin(0.001f).of(0.5f)
    }

    // endregion

    // region marking watched

    @Test
    fun markingAFilmWatchedMarksEveryCopyAndNeverOffersAResume() = runTest {
        val film = movie("Film"); val hd = movieFile(film); val uhd = movieFile(film, height = 2160)
        watched.setMovieWatched(film, true)
        val states = listOf(hd, uhd).map { db.watchStateDao().getWatchState(it)!! }
        assertThat(states.all { it.isCompleted && it.positionMs == 0L && it.playCount == 1 }).isTrue()

        watched.setMovieWatched(film, true)
        assertThat(db.watchStateDao().getWatchState(hd)!!.playCount).isEqualTo(1)
    }

    @Test
    fun markingUnwatchedKeepsTrackChoicesAndResetsProgress() = runTest {
        val film = movie("Film"); val file = movieFile(film)
        db.watchStateDao().upsertWatchState(
            WatchStateEntity(mediaFileId = file, positionMs = 5_000_000, durationMs = 6_000_000, isCompleted = true, selectedAudioTrack = 3, audioDelayMs = 150)
        )
        watched.setMovieWatched(film, false)
        val state = db.watchStateDao().getWatchState(file)!!
        assertThat(state.isCompleted).isFalse()
        assertThat(state.positionMs).isEqualTo(0)
        assertThat(state.selectedAudioTrack).isEqualTo(3)
        assertThat(state.audioDelayMs).isEqualTo(150)
    }

    @Test
    fun markingUnwatchedWithNothingRecordedCreatesNothing() = runTest {
        val film = movie("Film"); val file = movieFile(film)
        watched.setMovieWatched(film, false)
        assertThat(db.watchStateDao().getWatchState(file)).isNull()
    }

    @Test
    fun markingASeasonLeavesItsLastEpisodeAsTheMostRecent() = runTest {
        var now = 1_000L
        watched.clock = { now }
        val show = series("Show")
        val e1 = episode(show, 1, 1); val f1 = episodeFile(e1)
        val e2 = episode(show, 1, 2); val f2 = episodeFile(e2)
        val e3 = episode(show, 1, 3); val f3 = episodeFile(e3)
        val other = episode(show, 2, 1); val otherFile = episodeFile(other)

        watched.setSeasonWatched(show, 1, true)

        val times = listOf(f1, f2, f3).map { db.watchStateDao().getWatchState(it)!!.lastWatchedAt }
        assertThat(times).isInOrder()
        assertThat(times.toSet()).hasSize(3)
        assertThat(db.watchStateDao().getWatchState(otherFile)).isNull()
        // So the next episode to offer is the first of season 2
        assertThat(home.nextUp.first().single().episodeId).isEqualTo(other)
        now += 1
    }

    @Test
    fun markingASeriesWatchedMarksEverything() = runTest {
        val show = series("Show")
        val files = (1..3).map { episodeFile(episode(show, 1, it)) }
        watched.setSeriesWatched(show, true)
        assertThat(files.all { db.watchStateDao().getWatchState(it)?.isCompleted == true }).isTrue()
        watched.setSeriesWatched(show, false)
        assertThat(files.all { db.watchStateDao().getWatchState(it)?.isCompleted == false }).isTrue()
    }

    // endregion

    // region play plans

    @Test
    fun aFilmPlansItsBestAvailableCopy() = runTest {
        val film = movie("Alien", year = 1979)
        movieFile(film, height = 720)
        val uhd = movieFile(film, height = 2160)
        movieFile(film, height = 2160, available = false)
        val plan = planner.forMovie(film)!!
        assertThat(plan.entries.single().mediaFileId).isEqualTo(uhd)
        assertThat(plan.entries.single().title).isEqualTo("Alien (1979)")
    }

    @Test
    fun aChosenVersionOverridesTheBestOne() = runTest {
        val film = movie("Film")
        val hd = movieFile(film, height = 1080); movieFile(film, height = 2160)
        assertThat(planner.forMovie(film, preferredFileId = hd)!!.entries.single().mediaFileId).isEqualTo(hd)
    }

    @Test
    fun aChosenVersionOnAnUnpluggedDriveFallsBackToAnAvailableOne() = runTest {
        val film = movie("Film")
        val away = movieFile(film, available = false); val here = movieFile(film, height = 720)
        assertThat(planner.forMovie(film, preferredFileId = away)!!.entries.single().mediaFileId).isEqualTo(here)
    }

    @Test
    fun aFilmWithNothingAvailableHasNoPlan() = runTest {
        val film = movie("Film"); movieFile(film, available = false)
        assertThat(planner.forMovie(film)).isNull()
        assertThat(planner.forMovie(404)).isNull()
    }

    @Test
    fun anEpisodeQueuesTheRestOfTheSeriesInOrder() = runTest {
        val show = series("Show")
        val e1 = episode(show, 1, 1, "One"); episodeFile(e1)
        val e2 = episode(show, 1, 2, "Two"); val f2 = episodeFile(e2)
        val e3 = episode(show, 2, 1, "Three"); episodeFile(e3)
        episodeFile(episode(show, 0, 1, "Special"))

        val plan = planner.forEpisode(show, e2)!!
        assertThat(plan.entries.map { it.title }).containsExactly("Show · S01E02 · Two", "Show · S02E01 · Three").inOrder()
        assertThat(plan.entries.first().mediaFileId).isEqualTo(f2)
        assertThat(plan.label).isEqualTo("Show")
        assertThat(plan.startIndex).isEqualTo(0)
    }

    @Test
    fun theQueueSkipsEpisodesThatAreNotAvailable() = runTest {
        val show = series("Show")
        val e1 = episode(show, 1, 1); episodeFile(e1)
        episodeFile(episode(show, 1, 2), available = false)
        val e3 = episode(show, 1, 3); episodeFile(e3)
        val plan = planner.forEpisode(show, e1)!!
        assertThat(plan.entries).hasSize(2)
        assertThat(planner.forEpisode(show, episode(show, 1, 9))).isNull()
    }

    @Test
    fun theSeriesPlanFollowsTheLibraryProgress() = runTest {
        val show = series("Show")
        val e1 = episode(show, 1, 1); val f1 = episodeFile(e1)
        val e2 = episode(show, 1, 2); episodeFile(e2)

        assertThat(planner.forSeries(show)!!.entries.first().mediaFileId).isEqualTo(f1)
        watch(f1, completed = true, at = 5)
        assertThat(planner.forSeries(show)!!.entries.first().title).contains("S01E02")
        watch(episodeFile(episode(show, 1, 3)), position = 1_000_000, at = 9)
        assertThat(planner.forSeries(show)!!.entries.first().title).contains("S01E03")
    }

    @Test
    fun aSpecialQueuesOnlyTheOtherSpecials() = runTest {
        val show = series("Show")
        val regular = episode(show, 1, 1); episodeFile(regular)
        val s1 = episode(show, 0, 1); episodeFile(s1)
        episodeFile(episode(show, 0, 2))
        assertThat(planner.forEpisode(show, s1)!!.entries).hasSize(2)
    }

    @Test
    fun folderEntriesBecomeAPlanWithAClampedStart() {
        val entries = listOf(QueueEntry("u1", "a"), QueueEntry("u2", "b"))
        assertThat(planner.forEntries(entries, startIndex = 9)!!.startIndex).isEqualTo(1)
        assertThat(planner.forEntries(emptyList())).isNull()
    }

    @Test
    fun theQueueStoreHandsAPlanOver() {
        val store = PlaybackQueueStore()
        assertThat(store.take()).isNull()
        val plan = PlayPlan(listOf(QueueEntry("u", "t")))
        store.publish(plan)
        assertThat(store.take()).isEqualTo(plan)
        assertThat(store.take()).isNull()
    }

    // endregion

    // region details

    @Test
    fun movieDetailGathersVersionsCastAndStatus() = runTest {
        val person = db.metadataDao().insertPerson(PersonEntity(tmdbId = 1, name = "Director"))
        val actor = db.metadataDao().insertPerson(PersonEntity(tmdbId = 2, name = "Actor"))
        val film = movie("Film")
        db.metadataDao().insertCastMembers(
            listOf(
                CastMemberEntity(personId = person, personName = "Director", movieId = film, isDirector = true),
                CastMemberEntity(personId = actor, personName = "Actor", movieId = film, character = "Hero", order = 0)
            )
        )
        val hd = movieFile(film, height = 1080)
        val uhd = movieFile(film, height = 2160)
        db.mediaFileDao().insertAudioTracks(
            listOf(
                AudioTrackInfoEntity(mediaFileId = uhd, trackIndex = 1, language = "eng", codec = AudioCodec.AC3, channels = 6),
                AudioTrackInfoEntity(mediaFileId = uhd, trackIndex = 2, language = "fre", codec = AudioCodec.TRUEHD, channels = 8)
            )
        )
        watch(hd, position = 3_000_000, duration = 6_000_000)

        val detail = details.observeMovie(film).first()!!
        assertThat(detail.versions.map { it.mediaFileId }).containsExactly(uhd, hd).inOrder()
        assertThat(detail.versions.first().badges.resolution).isEqualTo("4K")
        assertThat(detail.versions.first().badges.audio).isEqualTo("TrueHD 7.1")
        assertThat(detail.directors.map { it.name }).containsExactly("Director")
        assertThat(detail.cast.single().role).isEqualTo("Hero")
        assertThat(detail.watch).isEqualTo(WatchStatus.IN_PROGRESS)
        assertThat(detail.progress).isWithin(0.001f).of(0.5f)
        assertThat(detail.defaultVersion!!.mediaFileId).isEqualTo(hd)
        assertThat(detail.isFavorite).isFalse()
    }

    @Test
    fun movieDetailListsTheOtherFilmsOfItsSaga() = runTest {
        val collection = db.metadataDao().insertCollection(com.lecteur.core.database.entity.CollectionEntity(tmdbId = 1, name = "Saga"))
        val first = db.movieDao().insertMovie(MovieEntity(title = "One", sortTitle = "One", year = 2001, collectionId = collection))
        val second = db.movieDao().insertMovie(MovieEntity(title = "Two", sortTitle = "Two", year = 2003, collectionId = collection))
        movieFile(first); movieFile(second)
        val detail = details.observeMovie(first).first()!!
        assertThat(detail.collection!!.name).isEqualTo("Saga")
        assertThat(detail.collection!!.others.map { it.title }).containsExactly("Two")
    }

    @Test
    fun unknownTitlesHaveNoDetail() = runTest {
        assertThat(details.observeMovie(404).first()).isNull()
        assertThat(details.observeSeries(404).first()).isNull()
    }

    @Test
    fun seriesDetailListsSeasonsEpisodesAndTheButtonTarget() = runTest {
        val show = series("Show")
        val e1 = episode(show, 1, 1, "Pilot"); val f1 = episodeFile(e1)
        episode(show, 1, 2, "Missing")
        val e3 = episode(show, 2, 1, "Return"); episodeFile(e3)
        watch(f1, completed = true, at = 5)

        val detail = details.observeSeries(show).first()!!
        assertThat(detail.seasons.map { it.seasonNumber }).containsExactly(1, 2).inOrder()
        val season1 = detail.seasons.first()
        assertThat(season1.episodes.map { it.title }).containsExactly("Pilot", "Missing").inOrder()
        assertThat(season1.episodes.map { it.isInLibrary }).containsExactly(true, false).inOrder()
        assertThat(season1.episodes.first().watch).isEqualTo(WatchStatus.WATCHED)
        assertThat(season1.inLibraryCount).isEqualTo(1)
        assertThat(season1.watchedCount).isEqualTo(1)

        // The missing episode is not offered: the next one in the library is
        assertThat(detail.playTarget!!.kind).isEqualTo(SeriesPlayKind.NEXT)
        assertThat(detail.playTarget!!.episode.episodeId).isEqualTo(e3)
        assertThat(detail.watch).isEqualTo(WatchStatus.IN_PROGRESS)
    }

    @Test
    fun seriesDetailMarksTheSeriesWatchedOnceEveryEpisodeIs() = runTest {
        val show = series("Show")
        watch(episodeFile(episode(show, 1, 1)), completed = true, at = 1)
        watch(episodeFile(episode(show, 1, 2)), completed = true, at = 2)
        val detail = details.observeSeries(show).first()!!
        assertThat(detail.watch).isEqualTo(WatchStatus.WATCHED)
        assertThat(detail.playTarget!!.kind).isEqualTo(SeriesPlayKind.REWATCH)
    }

    @Test
    fun detailFollowsFavouriteChanges() = runTest {
        val film = movie("Film"); movieFile(film)
        favorites.toggleMovie(film)
        assertThat(details.observeMovie(film).first()!!.isFavorite).isTrue()
    }

    // endregion

    // region badges

    @Test
    fun audioLabelPicksTheBestCodecThenTheMostChannels() {
        fun track(codec: AudioCodec, channels: Int) = AudioTrackInfoEntity(mediaFileId = 1, trackIndex = channels, codec = codec, channels = channels)
        assertThat(Badges.audioLabel(listOf(track(AudioCodec.AAC, 2), track(AudioCodec.AC3, 6)))).isEqualTo("Dolby Digital 5.1")
        assertThat(Badges.audioLabel(listOf(track(AudioCodec.AC3, 6), track(AudioCodec.E_AC3_JOC, 8)))).isEqualTo("Dolby Atmos 7.1")
        assertThat(Badges.audioLabel(listOf(track(AudioCodec.DTS, 6), track(AudioCodec.DTS_HD_MA, 8), track(AudioCodec.DTS_HD_MA, 6)))).isEqualTo("DTS-HD MA 7.1")
        assertThat(Badges.audioLabel(emptyList())).isNull()
        assertThat(Badges.audioLabel(listOf(track(AudioCodec.UNKNOWN, 2)))).isEqualTo("2.0")
    }

    @Test
    fun languagesAreNamedOnceEach() {
        assertThat(Badges.languages(listOf("fre", "fra", "eng", null, "und"))).hasSize(2)
    }

    @Test
    fun hdrBadgeComesFromTheFile() {
        val file = MediaFileEntity(folderId = 1, uri = "u", displayPath = "p", fileName = "f", size = 1, fingerprint = "x", lastModified = 1, width = 3840, height = 1600, hdrType = HdrType.DOLBY_VISION)
        val badges = Badges.forFile(file, emptyList(), emptyList())
        assertThat(badges.resolution).isEqualTo("4K")
        assertThat(badges.hdr).isEqualTo(HdrType.DOLBY_VISION)
    }

    // endregion

    // region search

    private fun title(id: Long, name: String, original: String? = null, year: Int? = null) = TitleRow(id, name, original, year, null)

    @Test
    fun searchIgnoresAccentsAndCase() {
        val index = SearchIndex(listOf(title(1, "Amélie"), title(2, "Alien")), emptyList(), emptyList())
        assertThat(index.search("amelie").movies.map { it.title }).containsExactly("Amélie")
        assertThat(index.search("ALI").movies.map { it.title }).containsExactly("Alien")
    }

    @Test
    fun searchRanksPrefixMatchesBeforeInnerOnes() {
        val index = SearchIndex(listOf(title(1, "The Star"), title(2, "Star Wars"), title(3, "Mastar")), emptyList(), emptyList())
        assertThat(index.search("star").movies.map { it.title }).containsExactly("Star Wars", "The Star", "Mastar").inOrder()
    }

    @Test
    fun searchFindsAFilmByItsOriginalTitleAndSaysSo() {
        val index = SearchIndex(listOf(title(1, "Le Fabuleux Destin d'Amélie Poulain", original = "Amélie")), emptyList(), emptyList())
        val hit = index.search("amelie").movies.single()
        assertThat(hit.title).isEqualTo("Le Fabuleux Destin d'Amélie Poulain")
        // Both names contain the word, the displayed one wins and nothing is said about the original
        assertThat(index.search("fabuleux").movies.single().matchedOriginalTitle).isNull()
        val onlyOriginal = SearchIndex(listOf(title(1, "Perdu", original = "Lost")), emptyList(), emptyList())
        assertThat(onlyOriginal.search("lost").movies.single().matchedOriginalTitle).isEqualTo("Lost")
    }

    @Test
    fun searchNeedsEveryWord() {
        val index = SearchIndex(listOf(title(1, "Blade Runner 2049"), title(2, "Blade Runner")), emptyList(), emptyList())
        assertThat(index.search("blade 2049").movies.map { it.id }).containsExactly(1L)
    }

    @Test
    fun searchFindsPeopleOrderedByTheirPlaceInTheLibrary() {
        val people = listOf(PersonRow(1, "Tom Hanks", null, 12, false), PersonRow(2, "Tom Hardy", null, 3, false), PersonRow(3, "Thomas", null, 99, false))
        val hits = SearchIndex(emptyList(), emptyList(), people).search("tom ha").people
        assertThat(hits.map { it.name }).containsExactly("Tom Hanks", "Tom Hardy").inOrder()
    }

    @Test
    fun searchSeparatesFilmsFromSeries() {
        val index = SearchIndex(listOf(title(1, "Fargo")), listOf(title(7, "Fargo")), emptyList())
        val results = index.search("fargo")
        assertThat(results.movies.single().kind).isEqualTo(MediaKind.MOVIE)
        assertThat(results.series.single().kind).isEqualTo(MediaKind.SERIES)
        assertThat(results.series.single().id).isEqualTo(7)
    }

    @Test
    fun anEmptyQueryFindsNothing() {
        val index = SearchIndex(listOf(title(1, "Alien")), emptyList(), emptyList())
        assertThat(index.search("  ").isEmpty).isTrue()
        assertThat(index.search("zzz").isEmpty).isTrue()
    }

    @Test
    fun likePatternEscapesWildcards() {
        assertThat(SearchRepository.likePattern("blade runner")).isEqualTo("%blade%runner%")
        assertThat(SearchRepository.likePattern("100%_done")).isEqualTo("%100\\%\\_done%")
    }

    @Test
    fun repositorySearchesTitlesAndFileNames() = runTest {
        val repository = SearchRepository(db.libraryDao())
        val film = movie("Amélie"); movieFile(film)
        val n = counter++
        db.mediaFileDao().insertMediaFile(
            MediaFileEntity(folderId = folderId, uri = "content://x/$n", displayPath = "/x", fileName = "Holiday.2019.Raw.mkv", size = 1, fingerprint = "x$n", lastModified = 1)
        )
        val titles = repository.search("amelie").first()
        assertThat(titles.movies.map { it.title }).containsExactly("Amélie")
        val files = repository.search("holiday").first()
        assertThat(files.files.single().fileName).isEqualTo("Holiday.2019.Raw.mkv")
        assertThat(files.files.single().kind).isNull()
    }

    @Test
    fun aFileOfAListedTitleIsNotShownTwice() = runTest {
        val repository = SearchRepository(db.libraryDao())
        val film = db.movieDao().insertMovie(MovieEntity(title = "Dune", sortTitle = "Dune"))
        val n = counter++
        db.mediaFileDao().insertMediaFile(
            MediaFileEntity(folderId = folderId, uri = "content://d/$n", displayPath = "/d", fileName = "Dune.2021.mkv", size = 1, fingerprint = "d$n", lastModified = 1, movieId = film)
        )
        val results = repository.search("dune").first()
        assertThat(results.movies).hasSize(1)
        assertThat(results.files).isEmpty()
    }

    // endregion

    // region folder explorer

    private class FakeBrowser(val listing: DirectoryListing?) : DirectoryBrowser {
        override suspend fun list(treeUri: String, documentId: String) = listing
    }

    private fun entry(name: String, dir: Boolean = false, video: Boolean = !dir, uri: String? = if (dir) null else "content://tree/$name") =
        BrowseEntry(documentId = "id-$name", name = name, isDirectory = dir, isVideo = video, sizeBytes = 1, lastModified = 1, uri = uri)

    @Test
    fun explorerListsFoldersFirstThenFilesInNaturalOrder() = runTest {
        val listing = DirectoryListing(
            "tree://f", "root",
            listOf(entry("Episode 10.mkv"), entry("notes.txt", video = false), entry("Zoo", dir = true), entry("Episode 2.mkv"), entry("Arc", dir = true), entry(".hidden", dir = true))
        )
        val repo = BrowseRepository(FakeBrowser(listing), db.libraryFolderDao(), db.mediaFileDao())
        val opened = repo.open("tree://f", "root")!!
        assertThat(opened.entries.map { it.name }).containsExactly("Arc", "Zoo", "Episode 2.mkv", "Episode 10.mkv", "notes.txt").inOrder()
    }

    @Test
    fun explorerShowsWhatTheLibraryKnowsAboutAFile() = runTest {
        val film = movie("Film")
        val file = movieFile(film)
        val uri = db.mediaFileDao().getMediaFileById(file)!!.uri
        watch(file, position = 3_000_000, duration = 6_000_000)
        val listing = DirectoryListing("tree://f", "root", listOf(entry("known.mkv", uri = uri), entry("unknown.mkv")))
        val opened = BrowseRepository(FakeBrowser(listing), db.libraryFolderDao(), db.mediaFileDao()).open("tree://f", "root")!!
        val known = opened.entries.first { it.name == "known.mkv" }
        assertThat(known.watched).isFalse()
        assertThat(known.progress).isWithin(0.001f).of(0.5f)
        assertThat(opened.entries.first { it.name == "unknown.mkv" }.watched).isNull()
    }

    @Test
    fun anUnreadableFolderIsNull() = runTest {
        assertThat(BrowseRepository(FakeBrowser(null), db.libraryFolderDao(), db.mediaFileDao()).open("tree://f", "root")).isNull()
    }

    @Test
    fun readingAFolderQueuesItsVideosInOrder() {
        val listing = DirectoryListing("t", "r", listOf(entry("b.mkv"), entry("a.mkv"), entry("sub", dir = true), entry("c.txt", video = false)))
        val repo = BrowseRepository(FakeBrowser(listing), db.libraryFolderDao(), db.mediaFileDao())
        val plan = repo.planFor(listing, "Movies")!!
        // planFor uses the order of the listing it is given (open() has already sorted it)
        assertThat(plan.entries.map { it.title }).containsExactly("b.mkv", "a.mkv").inOrder()
        assertThat(plan.label).isEqualTo("Movies")
        assertThat(repo.planFor(listing, null, start = "id-a.mkv")!!.startIndex).isEqualTo(1)
        assertThat(repo.planFor(DirectoryListing("t", "r", listOf(entry("sub", dir = true))), null)).isNull()
    }

    @Test
    fun shuffledFolderPlayStartsSomewhereAndSaysSo() {
        val listing = DirectoryListing("t", "r", (1..20).map { entry("$it.mkv") })
        val repo = BrowseRepository(FakeBrowser(listing), db.libraryFolderDao(), db.mediaFileDao())
        val plan = repo.planFor(listing, null, shuffle = true, random = kotlin.random.Random(7))!!
        assertThat(plan.shuffle).isTrue()
        assertThat(plan.startIndex).isIn(0..19)
    }

    // endregion

    // region preferences

    private fun TestScope.dataStore() = PreferenceDataStoreFactory.create(
        scope = backgroundScope,
        produceFile = { tmp.newFile("prefs-${System.nanoTime()}.preferences_pb") }
    )

    @Test
    fun appearanceDefaultsToDarkWithDynamicColours() = runTest(UnconfinedTestDispatcher()) {
        val repo = AppearanceRepository(dataStore())
        assertThat(repo.settings.first().themeMode).isEqualTo(ThemeMode.DARK)
        assertThat(repo.settings.first().dynamicColor).isTrue()
        repo.setThemeMode(ThemeMode.SYSTEM); repo.setDynamicColor(false)
        assertThat(repo.settings.first().themeMode).isEqualTo(ThemeMode.SYSTEM)
        assertThat(repo.settings.first().dynamicColor).isFalse()
    }

    @Test
    fun homeLayoutIsRememberedAndCanBeReset() = runTest(UnconfinedTestDispatcher()) {
        val repo = HomeLayoutRepository(dataStore())
        repo.move(HomeRow.FAVORITES, -1)
        repo.setVisible(HomeRow.UNWATCHED, false)
        val layout = repo.layout.first()
        assertThat(layout.rows.indexOfFirst { it.row == HomeRow.FAVORITES }).isEqualTo(HomeRow.entries.size - 2)
        assertThat(layout.visibleRows).doesNotContain(HomeRow.UNWATCHED)
        repo.reset()
        assertThat(repo.layout.first().visibleRows).containsExactlyElementsIn(HomeRow.entries).inOrder()
    }

    @Test
    fun eachShelfRemembersItsOwnSortButSharesThePosterSize() = runTest(UnconfinedTestDispatcher()) {
        val repo = LibraryViewRepository(dataStore())
        repo.setSort(LibrarySection.MOVIES, LibrarySort(SortField.RATING, SortOrder.DESC))
        repo.setView(LibrarySection.SERIES, LibraryView.LIST)
        repo.setPosterSize(PosterSize.LARGE)

        val movies = repo.prefs(LibrarySection.MOVIES).first()
        val series = repo.prefs(LibrarySection.SERIES).first()
        assertThat(movies.sort).isEqualTo(LibrarySort(SortField.RATING, SortOrder.DESC))
        assertThat(movies.view).isEqualTo(LibraryView.GRID)
        assertThat(series.sort).isEqualTo(LibrarySort(SortField.TITLE, SortOrder.ASC))
        assertThat(series.view).isEqualTo(LibraryView.LIST)
        assertThat(listOf(movies.posterSize, series.posterSize)).containsExactly(PosterSize.LARGE, PosterSize.LARGE)
    }

    @Test
    fun onboardingIsSeenOnce() = runTest(UnconfinedTestDispatcher()) {
        val repo = OnboardingRepository(dataStore())
        assertThat(repo.isDone.first()).isFalse()
        repo.markDone()
        assertThat(repo.isDone.first()).isTrue()
    }

    // endregion
}
