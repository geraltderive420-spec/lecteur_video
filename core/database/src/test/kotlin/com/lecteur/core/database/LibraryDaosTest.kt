package com.lecteur.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lecteur.core.database.entity.EpisodeEntity
import com.lecteur.core.database.entity.LibraryFolderEntity
import com.lecteur.core.database.entity.MediaFileEntity
import com.lecteur.core.database.entity.MovieEntity
import com.lecteur.core.database.entity.SeasonEntity
import com.lecteur.core.database.entity.SeriesAliasEntity
import com.lecteur.core.database.entity.SeriesEntity
import com.lecteur.core.database.entity.WatchStateEntity
import com.lecteur.core.model.MatchState
import com.lecteur.core.model.MediaCategory
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
class LibraryDaosTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun folder() = db.libraryFolderDao().insertFolder(
        LibraryFolderEntity(uri = "tree://f", displayPath = "/f", category = MediaCategory.MOVIES)
    )

    private fun file(folderId: Long, uri: String, movieId: Long? = null, episodeId: Long? = null, fp: String = "fp-$uri") = MediaFileEntity(
        folderId = folderId, uri = uri, displayPath = "/f/$uri", fileName = "$uri.mkv", size = 10, fingerprint = fp,
        lastModified = 1, movieId = movieId, episodeId = episodeId
    )

    private suspend fun episode(seriesId: Long, season: Int, number: Int): Long {
        val seasonId = db.seasonDao().getSeasonByNumber(seriesId, season)?.id
            ?: db.seasonDao().insertSeason(SeasonEntity(seriesId = seriesId, seasonNumber = season))
        return db.episodeDao().insertEpisode(EpisodeEntity(seriesId = seriesId, seasonId = seasonId, seasonNumber = season, episodeNumber = number))
    }

    @Test
    fun moviesToIdentifySkipsLockedIdentifiedAndRecentlyTried() = runTest {
        val dao = db.movieDao()
        val fresh = dao.insertMovie(MovieEntity(title = "Fresh"))
        val oldTry = dao.insertMovie(MovieEntity(title = "Old try", matchAttemptedAt = 100))
        dao.insertMovie(MovieEntity(title = "Recent try", matchAttemptedAt = 9_000))
        dao.insertMovie(MovieEntity(title = "Locked", matchLocked = true))
        dao.insertMovie(MovieEntity(title = "Done", matchState = MatchState.IDENTIFIED))

        val todo = dao.getMoviesToIdentify(retryBefore = 5_000, limit = 10)

        assertThat(todo.map { it.id }).containsExactly(fresh, oldTry)
    }

    @Test
    fun reviewListHoldsUnidentifiedAndToVerifyButNotLockedOrIdentified() = runTest {
        val dao = db.movieDao()
        dao.insertMovie(MovieEntity(title = "A", matchState = MatchState.TO_VERIFY))
        dao.insertMovie(MovieEntity(title = "B", matchState = MatchState.UNIDENTIFIED))
        dao.insertMovie(MovieEntity(title = "C", matchState = MatchState.IDENTIFIED))
        dao.insertMovie(MovieEntity(title = "D", matchState = MatchState.UNIDENTIFIED, matchLocked = true))

        assertThat(dao.observeMoviesToReview().first().map { it.title }).containsExactly("A", "B").inOrder()
        assertThat(dao.observeReviewCount().first()).isEqualTo(2)
    }

    @Test
    fun orphanMoviesAreThoseWithoutAnyFileRow() = runTest {
        val folderId = folder()
        val kept = db.movieDao().insertMovie(MovieEntity(title = "Kept"))
        db.movieDao().insertMovie(MovieEntity(title = "Orphan"))
        db.mediaFileDao().insertMediaFile(file(folderId, "a", movieId = kept))

        assertThat(db.movieDao().deleteOrphanMovies()).isEqualTo(1)
        assertThat(db.movieDao().getAllMovies().map { it.title }).containsExactly("Kept")
    }

    @Test
    fun orphanSeriesAreThoseWithoutAnyEpisodeFile() = runTest {
        val folderId = folder()
        val kept = db.seriesDao().insertSeries(SeriesEntity(title = "Kept"))
        val gone = db.seriesDao().insertSeries(SeriesEntity(title = "Gone"))
        val keptEpisode = episode(kept, 1, 1)
        episode(gone, 1, 1)
        db.mediaFileDao().insertMediaFile(file(folderId, "a", episodeId = keptEpisode))

        assertThat(db.seriesDao().deleteOrphanSeries()).isEqualTo(1)
        assertThat(db.seriesDao().getAllSeries().map { it.title }).containsExactly("Kept")
    }

    @Test
    fun episodesWithoutFilesAreTheOnesTmdbCreated() = runTest {
        val folderId = folder()
        val series = db.seriesDao().insertSeries(SeriesEntity(title = "S"))
        val withFile = episode(series, 1, 1)
        val without = episode(series, 1, 2)
        db.mediaFileDao().insertMediaFile(file(folderId, "e1", episodeId = withFile))

        assertThat(db.episodeDao().getEpisodesWithoutFiles(series).map { it.id }).containsExactly(without)
    }

    @Test
    fun relocateKeepsTheRowAndItsWatchState() = runTest {
        val folderId = folder()
        val id = db.mediaFileDao().insertMediaFile(file(folderId, "old"))
        db.watchStateDao().upsertWatchState(WatchStateEntity(mediaFileId = id, positionMs = 42, durationMs = 100))
        db.mediaFileDao().setAvailability(listOf(id), false)

        db.mediaFileDao().relocate(id, "new", "/f/new", "new.mkv", lastModified = 9, size = 10)

        val moved = db.mediaFileDao().getMediaFileById(id)!!
        assertThat(moved.uri).isEqualTo("new")
        assertThat(moved.isAvailable).isTrue()
        assertThat(db.watchStateDao().getWatchState(id)?.positionMs).isEqualTo(42)
    }

    @Test
    fun availabilityIsSetPerIdAndCountedPerFolder() = runTest {
        val folderId = folder()
        val a = db.mediaFileDao().insertMediaFile(file(folderId, "a"))
        db.mediaFileDao().insertMediaFile(file(folderId, "b"))
        db.mediaFileDao().setAvailability(listOf(a), false)

        val counts = db.mediaFileDao().observeFolderCounts().first().single()
        assertThat(counts.fileCount).isEqualTo(2)
        assertThat(counts.availableCount).isEqualTo(1)
    }

    @Test
    fun reassigningAMovieMovesItsFiles() = runTest {
        val folderId = folder()
        val from = db.movieDao().insertMovie(MovieEntity(title = "From"))
        val to = db.movieDao().insertMovie(MovieEntity(title = "To"))
        db.mediaFileDao().insertMediaFile(file(folderId, "1080", movieId = from))
        db.mediaFileDao().insertMediaFile(file(folderId, "2160", movieId = from))

        db.mediaFileDao().reassignMovie(from, to)

        assertThat(db.mediaFileDao().getMediaFilesForMovie(to)).hasSize(2)
        assertThat(db.mediaFileDao().getMediaFilesForMovie(from)).isEmpty()
    }

    @Test
    fun aliasesLeadToTheirSeriesAndFollowAMerge() = runTest {
        val a = db.seriesDao().insertSeries(SeriesEntity(title = "A"))
        val b = db.seriesDao().insertSeries(SeriesEntity(title = "B"))
        db.seriesAliasDao().put(SeriesAliasEntity("show a", a))
        db.seriesAliasDao().put(SeriesAliasEntity("show a 2020", a))

        assertThat(db.seriesAliasDao().findSeriesId("show a")).isEqualTo(a)
        assertThat(db.seriesAliasDao().findSeriesId("nope")).isNull()

        db.seriesAliasDao().repoint(a, b)
        db.seriesDao().deleteSeries(a)

        assertThat(db.seriesAliasDao().findSeriesId("show a")).isEqualTo(b)
        assertThat(db.seriesAliasDao().aliasesOf(b)).containsExactly("show a", "show a 2020")
    }

    @Test
    fun deletingASeriesDropsItsAliases() = runTest {
        val a = db.seriesDao().insertSeries(SeriesEntity(title = "A"))
        db.seriesAliasDao().put(SeriesAliasEntity("a", a))
        db.seriesDao().deleteSeries(a)
        assertThat(db.seriesAliasDao().findSeriesId("a")).isNull()
    }
}
