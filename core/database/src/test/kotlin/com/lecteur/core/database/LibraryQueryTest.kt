package com.lecteur.core.database

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lecteur.core.database.entity.AudioTrackInfoEntity
import com.lecteur.core.database.entity.CastMemberEntity
import com.lecteur.core.database.entity.EpisodeEntity
import com.lecteur.core.database.entity.GenreEntity
import com.lecteur.core.database.entity.LibraryFolderEntity
import com.lecteur.core.database.entity.MediaFileEntity
import com.lecteur.core.database.entity.MovieEntity
import com.lecteur.core.database.entity.MovieGenreCrossRef
import com.lecteur.core.database.entity.PersonEntity
import com.lecteur.core.database.entity.SeasonEntity
import com.lecteur.core.database.entity.SeriesEntity
import com.lecteur.core.database.entity.UserListEntity
import com.lecteur.core.database.entity.UserListItemEntity
import com.lecteur.core.database.entity.WatchStateEntity
import com.lecteur.core.database.query.LibraryQueryBuilder
import com.lecteur.core.model.HdrFilter
import com.lecteur.core.model.HdrType
import com.lecteur.core.model.LibraryFilters
import com.lecteur.core.model.LibraryQuery
import com.lecteur.core.model.LibrarySection
import com.lecteur.core.model.LibrarySort
import com.lecteur.core.model.MediaCategory
import com.lecteur.core.model.ResolutionTier
import com.lecteur.core.model.SortField
import com.lecteur.core.model.SortOrder
import com.lecteur.core.model.WatchFilter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class LibraryQueryTest {

    private lateinit var db: AppDatabase
    private var folderId = 0L
    private var fileCounter = 0

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        folderId = db.libraryFolderDao().insertFolder(LibraryFolderEntity(uri = "tree://f", displayPath = "/f", category = MediaCategory.GENERIC))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun movie(
        title: String, year: Int? = null, rating: Float? = null, addedAt: Long = 0, certification: String? = null,
        collectionId: Long? = null, runtime: Int? = null, releaseDate: String? = null
    ) = db.movieDao().insertMovie(
        MovieEntity(
            title = title, sortTitle = title, year = year, rating = rating, addedAt = addedAt, certification = certification,
            collectionId = collectionId, runtime = runtime, releaseDate = releaseDate
        )
    )

    private suspend fun movieFile(
        movieId: Long, width: Int = 1920, height: Int = 1080, hdr: HdrType = HdrType.NONE, size: Long = 100,
        available: Boolean = true, folder: Long = folderId, durationMs: Long? = 6_000_000
    ): Long {
        val n = fileCounter++
        return db.mediaFileDao().insertMediaFile(
            MediaFileEntity(
                folderId = folder, uri = "content://f/$n", displayPath = "/f/$n", fileName = "$n.mkv", size = size, fingerprint = "fp$n",
                lastModified = 1, width = width, height = height, hdrType = hdr, movieId = movieId, isAvailable = available, durationMs = durationMs
            )
        )
    }

    private suspend fun watch(fileId: Long, position: Long, duration: Long = 6_000_000, completed: Boolean = false, at: Long = 1_000) {
        db.watchStateDao().upsertWatchState(
            WatchStateEntity(mediaFileId = fileId, positionMs = position, durationMs = duration, isCompleted = completed, lastWatchedAt = at)
        )
    }

    private suspend fun series(title: String, firstAirDate: String? = null) =
        db.seriesDao().insertSeries(SeriesEntity(title = title, sortTitle = title, firstAirDate = firstAirDate))

    private suspend fun episode(seriesId: Long, season: Int, number: Int): Long {
        val seasonId = db.seasonDao().getSeasonByNumber(seriesId, season)?.id
            ?: db.seasonDao().insertSeason(SeasonEntity(seriesId = seriesId, seasonNumber = season))
        return db.episodeDao().insertEpisode(EpisodeEntity(seriesId = seriesId, seasonId = seasonId, seasonNumber = season, episodeNumber = number))
    }

    private suspend fun episodeFile(episodeId: Long, height: Int = 1080, available: Boolean = true): Long {
        val n = fileCounter++
        return db.mediaFileDao().insertMediaFile(
            MediaFileEntity(
                folderId = folderId, uri = "content://e/$n", displayPath = "/e/$n", fileName = "e$n.mkv", size = 10, fingerprint = "efp$n",
                lastModified = 1, width = 1920, height = height, episodeId = episodeId, isAvailable = available, durationMs = 2_400_000
            )
        )
    }

    private suspend fun titles(query: LibraryQuery) = db.libraryDao().rows(LibraryQueryBuilder.build(query)).map { it.title }

    private fun movies(
        sort: LibrarySort = LibrarySort(), filters: LibraryFilters = LibraryFilters(), limit: Int? = null
    ) = LibraryQuery(LibrarySection.MOVIES, sort, filters, limit)

    private fun seriesQuery(
        sort: LibrarySort = LibrarySort(), filters: LibraryFilters = LibraryFilters(), limit: Int? = null
    ) = LibraryQuery(LibrarySection.SERIES, sort, filters, limit)

    // region sorting

    @Test
    fun titleSortIsCaseInsensitiveAndReversible() = runTest {
        movie("banana"); movie("Apple"); movie("cherry")
        assertThat(titles(movies())).containsExactly("Apple", "banana", "cherry").inOrder()
        assertThat(titles(movies(LibrarySort(SortField.TITLE, SortOrder.DESC)))).containsExactly("cherry", "banana", "Apple").inOrder()
    }

    @Test
    fun unknownRatingsGoLastInBothDirections() = runTest {
        movie("Good", rating = 8f); movie("Unrated"); movie("Poor", rating = 3f)
        assertThat(titles(movies(LibrarySort(SortField.RATING, SortOrder.DESC)))).containsExactly("Good", "Poor", "Unrated").inOrder()
        assertThat(titles(movies(LibrarySort(SortField.RATING, SortOrder.ASC)))).containsExactly("Poor", "Good", "Unrated").inOrder()
    }

    @Test
    fun addedAndReleaseSorts() = runTest {
        movie("Old", year = 1999, addedAt = 30, releaseDate = "1999-05-01")
        movie("New", year = 2021, addedAt = 10, releaseDate = "2021-02-03")
        movie("Undated", addedAt = 20)
        assertThat(titles(movies(LibrarySort(SortField.ADDED, SortOrder.DESC)))).containsExactly("Old", "Undated", "New").inOrder()
        assertThat(titles(movies(LibrarySort(SortField.RELEASE, SortOrder.DESC)))).containsExactly("New", "Old", "Undated").inOrder()
    }

    @Test
    fun durationFallsBackToTheFileWhenTheRuntimeIsUnknown() = runTest {
        val short = movie("Short", runtime = 80)
        val noRuntime = movie("NoRuntime")
        movieFile(noRuntime, durationMs = 9_000_000) // 150 min
        movieFile(short)
        assertThat(titles(movies(LibrarySort(SortField.DURATION, SortOrder.DESC)))).containsExactly("NoRuntime", "Short").inOrder()
    }

    @Test
    fun fileSizeAndLastPlayedSorts() = runTest {
        val big = movie("Big"); val small = movie("Small"); val untouched = movie("Untouched")
        val bigFile = movieFile(big, size = 9_000); val smallFile = movieFile(small, size = 100); movieFile(untouched, size = 500)
        watch(smallFile, 1_000_000, at = 5_000); watch(bigFile, 1_000_000, at = 1_000)
        assertThat(titles(movies(LibrarySort(SortField.FILE_SIZE, SortOrder.DESC)))).containsExactly("Big", "Untouched", "Small").inOrder()
        assertThat(titles(movies(LibrarySort(SortField.LAST_PLAYED, SortOrder.DESC)))).containsExactly("Small", "Big", "Untouched").inOrder()
    }

    @Test
    fun equalValuesFallBackToTheTitleSoPagingIsStable() = runTest {
        movie("B", rating = 5f); movie("A", rating = 5f); movie("C", rating = 5f)
        assertThat(titles(movies(LibrarySort(SortField.RATING, SortOrder.DESC)))).containsExactly("A", "B", "C").inOrder()
    }

    @Test
    fun limitCapsTheRows() = runTest {
        repeat(5) { movie("M$it") }
        assertThat(titles(movies(limit = 2))).hasSize(2)
    }

    // endregion

    // region filters

    @Test
    fun genreAndYearAndCertificationFilters() = runTest {
        val action = db.metadataDao().insertGenre(GenreEntity(name = "Action"))
        val a = movie("A", year = 1994, certification = "12"); val b = movie("B", year = 2005, certification = "16"); movie("C", year = 1999)
        db.metadataDao().insertMovieGenreCrossRef(MovieGenreCrossRef(a, action))
        db.metadataDao().insertMovieGenreCrossRef(MovieGenreCrossRef(b, action))

        assertThat(titles(movies(filters = LibraryFilters(genreId = action)))).containsExactly("A", "B")
        assertThat(titles(movies(filters = LibraryFilters(yearFrom = 1990, yearTo = 1999)))).containsExactly("A", "C")
        assertThat(titles(movies(filters = LibraryFilters(certification = "16")))).containsExactly("B")
        assertThat(titles(movies(filters = LibraryFilters(genreId = action, yearFrom = 2000, yearTo = 2009)))).containsExactly("B")
    }

    @Test
    fun resolutionTierCountsACroppedWidescreenFilmAsFullHd() = runTest {
        val scope = movie("Scope"); movieFile(scope, width = 1920, height = 800)
        val sd = movie("Sd"); movieFile(sd, width = 720, height = 480)
        val uhd = movie("Uhd"); movieFile(uhd, width = 3840, height = 2160)

        assertThat(titles(movies(filters = LibraryFilters(resolution = ResolutionTier.FULL_HD)))).containsExactly("Scope", "Uhd")
        assertThat(titles(movies(filters = LibraryFilters(resolution = ResolutionTier.UHD)))).containsExactly("Uhd")
        assertThat(titles(movies(filters = LibraryFilters(resolution = ResolutionTier.HD)))).containsExactly("Scope", "Uhd")
        assertThat(sd).isNotEqualTo(0L)
    }

    @Test
    fun hdrFilters() = runTest {
        val plain = movie("Plain"); movieFile(plain)
        val hdr = movie("Hdr"); movieFile(hdr, hdr = HdrType.HDR10)
        val dv = movie("Dv"); movieFile(dv, hdr = HdrType.DOLBY_VISION)
        assertThat(titles(movies(filters = LibraryFilters(hdr = HdrFilter.ANY_HDR)))).containsExactly("Dv", "Hdr")
        assertThat(titles(movies(filters = LibraryFilters(hdr = HdrFilter.DOLBY_VISION)))).containsExactly("Dv")
        assertThat(plain).isNotEqualTo(0L)
    }

    @Test
    fun folderAndAudioLanguageFilters() = runTest {
        val other = db.libraryFolderDao().insertFolder(LibraryFolderEntity(uri = "tree://g", displayPath = "/g", category = MediaCategory.MOVIES))
        val fr = movie("Fr"); val frFile = movieFile(fr)
        val en = movie("En"); movieFile(en, folder = other)
        db.mediaFileDao().insertAudioTracks(listOf(AudioTrackInfoEntity(mediaFileId = frFile, trackIndex = 1, language = "fre")))

        assertThat(titles(movies(filters = LibraryFilters(folderId = other)))).containsExactly("En")
        assertThat(titles(movies(filters = LibraryFilters(audioLanguage = "fr")))).containsExactly("Fr")
    }

    @Test
    fun audioLanguageMatchesEverySpellingOfTheCode() = runTest {
        val a = movie("Bibliographic"); val b = movie("Terminologic"); val c = movie("TwoLetters"); val d = movie("English")
        db.mediaFileDao().insertAudioTracks(
            listOf(
                AudioTrackInfoEntity(mediaFileId = movieFile(a), trackIndex = 1, language = "fre"),
                AudioTrackInfoEntity(mediaFileId = movieFile(b), trackIndex = 1, language = "FRA"),
                AudioTrackInfoEntity(mediaFileId = movieFile(c), trackIndex = 1, language = "fr"),
                AudioTrackInfoEntity(mediaFileId = movieFile(d), trackIndex = 1, language = "eng")
            )
        )
        assertThat(titles(movies(filters = LibraryFilters(audioLanguage = "fr")))).containsExactly("Bibliographic", "Terminologic", "TwoLetters")
    }

    @Test
    fun personCollectionAndListScopes() = runTest {
        val person = db.metadataDao().insertPerson(PersonEntity(tmdbId = 1, name = "Actor"))
        val collection = db.metadataDao().insertCollection(com.lecteur.core.database.entity.CollectionEntity(tmdbId = 7, name = "Saga"))
        val a = movie("A", collectionId = collection); val b = movie("B"); movie("C")
        db.metadataDao().insertCastMembers(listOf(CastMemberEntity(personId = person, personName = "Actor", movieId = a), CastMemberEntity(personId = person, personName = "Actor", movieId = b)))
        val list = db.userListDao().insertList(UserListEntity(name = "Favoris"))
        db.userListDao().insertItem(UserListItemEntity(listId = list, movieId = b))

        assertThat(titles(movies(filters = LibraryFilters(personId = person)))).containsExactly("A", "B")
        assertThat(titles(movies(filters = LibraryFilters(collectionId = collection)))).containsExactly("A")
        assertThat(titles(movies(filters = LibraryFilters(userListId = list)))).containsExactly("B")
    }

    @Test
    fun theCollectionScopeDoesNotEmptyTheSeriesShelf() = runTest {
        series("S")
        assertThat(titles(seriesQuery(filters = LibraryFilters(collectionId = 99)))).containsExactly("S")
    }

    @Test
    fun userTextNeverReachesTheSqlText() = runTest {
        movie("Safe")
        val evil = LibraryFilters(certification = "x'; DROP TABLE movies; --")
        assertThat(titles(movies(filters = evil))).isEmpty()
        assertThat(titles(movies())).containsExactly("Safe")
    }

    // endregion

    // region watch status

    @Test
    fun movieWatchFilters() = runTest {
        val unwatched = movie("Unwatched"); movieFile(unwatched)
        val started = movie("Started"); watch(movieFile(started), position = 3_000_000)
        val barely = movie("Barely"); watch(movieFile(barely), position = 60_000) // 1%: not started
        val done = movie("Done"); watch(movieFile(done), position = 0, completed = true)

        assertThat(titles(movies(filters = LibraryFilters(watch = WatchFilter.UNWATCHED)))).containsExactly("Barely", "Unwatched")
        assertThat(titles(movies(filters = LibraryFilters(watch = WatchFilter.IN_PROGRESS)))).containsExactly("Started")
        assertThat(titles(movies(filters = LibraryFilters(watch = WatchFilter.WATCHED)))).containsExactly("Done")
    }

    @Test
    fun aMovieIsWatchedWhenAnyOfItsVersionsIs() = runTest {
        val m = movie("Both")
        movieFile(m, height = 2160)
        watch(movieFile(m, height = 1080), position = 0, completed = true)
        assertThat(titles(movies(filters = LibraryFilters(watch = WatchFilter.WATCHED)))).containsExactly("Both")
    }

    @Test
    fun rowCarriesTheProgressOfTheFilmBeingWatched() = runTest {
        val m = movie("Halfway"); watch(movieFile(m), position = 3_000_000)
        val row = db.libraryDao().rows(LibraryQueryBuilder.build(movies())).single()
        assertThat(row.progress).isWithin(0.001f).of(0.5f)
        assertThat(row.startedCount).isEqualTo(1)
        assertThat(row.availableCount).isEqualTo(1)
    }

    @Test
    fun seriesWatchStatusIsDerivedFromItsEpisodes() = runTest {
        val none = series("None"); episodeFile(episode(none, 1, 1))
        val partial = series("Partial")
        watch(episodeFile(episode(partial, 1, 1)), 0, completed = true); episodeFile(episode(partial, 1, 2))
        val all = series("All")
        watch(episodeFile(episode(all, 1, 1)), 0, completed = true); watch(episodeFile(episode(all, 1, 2)), 0, completed = true)
        val mid = series("Mid"); watch(episodeFile(episode(mid, 1, 1)), 1_000_000, duration = 2_400_000)

        assertThat(titles(seriesQuery(filters = LibraryFilters(watch = WatchFilter.UNWATCHED)))).containsExactly("None")
        assertThat(titles(seriesQuery(filters = LibraryFilters(watch = WatchFilter.IN_PROGRESS)))).containsExactly("Mid", "Partial")
        assertThat(titles(seriesQuery(filters = LibraryFilters(watch = WatchFilter.WATCHED)))).containsExactly("All")

        val rows = db.libraryDao().rows(LibraryQueryBuilder.build(seriesQuery())).associateBy { it.title }
        assertThat(rows.getValue("Partial").unitCount).isEqualTo(2)
        assertThat(rows.getValue("Partial").watchedCount).isEqualTo(1)
    }

    @Test
    fun seriesYearComesFromTheFirstAirDate() = runTest {
        val s = series("Old", firstAirDate = "1994-09-22"); episodeFile(episode(s, 1, 1))
        series("Undated")
        assertThat(titles(seriesQuery(filters = LibraryFilters(yearFrom = 1990, yearTo = 1999)))).containsExactly("Old")
        assertThat(db.libraryDao().rows(LibraryQueryBuilder.build(seriesQuery())).first { it.title == "Old" }.year).isEqualTo(1994)
    }

    @Test
    fun seriesFilesFilterThroughTheirEpisodes() = runTest {
        val uhd = series("Uhd"); episodeFile(episode(uhd, 1, 1), height = 2160)
        val hd = series("Hd"); episodeFile(episode(hd, 1, 1), height = 720)
        assertThat(titles(seriesQuery(filters = LibraryFilters(resolution = ResolutionTier.UHD)))).containsExactly("Uhd")
        assertThat(hd).isNotEqualTo(0L)
    }

    @Test
    fun unavailableFilesAreCountedApart() = runTest {
        val m = movie("Away"); movieFile(m, available = false)
        val row = db.libraryDao().rows(LibraryQueryBuilder.build(movies())).single()
        assertThat(row.availableCount).isEqualTo(0)
    }

    // endregion

    // region paging and flows

    @Test
    fun pagingSourceLoadsTheSameRowsInOrder() = runTest {
        repeat(30) { movie("M%02d".format(it)) }
        val source = db.libraryDao().pagedRows(LibraryQueryBuilder.build(movies()))
        val page = source.load(PagingSource.LoadParams.Refresh(null, 10, false)) as PagingSource.LoadResult.Page
        assertThat(page.data.map { it.title }).containsExactly("M00", "M01", "M02", "M03", "M04", "M05", "M06", "M07", "M08", "M09").inOrder()
        assertThat(page.nextKey).isNotNull()
    }

    @Test
    fun observedRowsReactToWatchChanges() = runTest {
        val m = movie("Live"); val f = movieFile(m)
        val flow = db.libraryDao().observeRows(LibraryQueryBuilder.build(movies()))
        assertThat(flow.first().single().watchedCount).isEqualTo(0)
        watch(f, 0, completed = true)
        assertThat(flow.first().single().watchedCount).isEqualTo(1)
    }

    // endregion

    // region home rows

    @Test
    fun continueWatchingListsStartedFilesMostRecentFirst() = runTest {
        val a = movie("A"); val b = movie("B"); val done = movie("Done")
        watch(movieFile(a), 1_000_000, at = 10)
        watch(movieFile(b), 2_000_000, at = 20)
        watch(movieFile(done), 5_900_000, completed = true, at = 30)
        val ep = episode(series("Show"), 2, 5)
        watch(episodeFile(ep), 600_000, duration = 2_400_000, at = 15)

        val rows = db.libraryDao().observeContinueWatching(10).first()
        assertThat(rows.map { it.title }).containsExactly("B", "Show", "A").inOrder()
        val episodeRow = rows.first { it.title == "Show" }
        assertThat(episodeRow.seasonNumber).isEqualTo(2)
        assertThat(episodeRow.episodeNumber).isEqualTo(5)
    }

    @Test
    fun followedSeriesExposeEveryEpisodeFile() = runTest {
        val s = series("Followed"); val e1 = episode(s, 1, 1); val e2 = episode(s, 1, 2)
        watch(episodeFile(e1), 0, completed = true); episodeFile(e2)
        series("Ignored").also { episodeFile(episode(it, 1, 1)) }

        val rows = db.libraryDao().observeFollowedEpisodeStates().first()
        assertThat(rows.map { it.seriesId }.distinct()).containsExactly(s)
        assertThat(rows).hasSize(2)
        assertThat(rows.first { it.episodeId == e1 }.completed).isTrue()
        assertThat(rows.first { it.episodeId == e2 }.completed).isFalse()
    }

    @Test
    fun episodeRowsIncludeEpisodesWithoutAFile() = runTest {
        val s = series("Show"); val e1 = episode(s, 1, 1); episode(s, 1, 2)
        episodeFile(e1)
        val rows = db.libraryDao().observeEpisodeRows(s).first()
        assertThat(rows).hasSize(2)
        assertThat(rows[0].mediaFileId).isNotNull()
        assertThat(rows[1].mediaFileId).isNull()
    }

    // endregion

    // region facets and lists

    @Test
    fun facetsOnlyListWhatTheLibraryHolds() = runTest {
        val used = db.metadataDao().insertGenre(GenreEntity(name = "Drame"))
        db.metadataDao().insertGenre(GenreEntity(name = "Western"))
        val m = movie("M", year = 1994, certification = "12")
        db.metadataDao().insertMovieGenreCrossRef(MovieGenreCrossRef(m, used))
        movie("N", year = 2003)

        val dao = db.libraryDao()
        assertThat(dao.movieGenres().map { it.name }).containsExactly("Drame")
        assertThat(dao.movieYears()).containsExactly(1994, 2003)
        assertThat(dao.movieCertifications()).containsExactly("12")
    }

    @Test
    fun aListNameIsUniqueAndItsItemsSurviveARepeatedCreate() = runTest {
        val dao = db.userListDao()
        val id = dao.insertList(UserListEntity(name = "Favoris"))
        val movie = movie("Kept")
        dao.insertItem(UserListItemEntity(listId = id, movieId = movie))

        assertThat(dao.insertList(UserListEntity(name = "Favoris"))).isEqualTo(-1L)
        assertThat(dao.findListId("Favoris")).isEqualTo(id)
        assertThat(dao.hasMovie(id, movie)).isTrue()
    }

    // endregion
}
