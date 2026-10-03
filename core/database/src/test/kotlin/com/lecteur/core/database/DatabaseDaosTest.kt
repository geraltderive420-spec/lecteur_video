package com.lecteur.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lecteur.core.database.entity.AudioTrackInfoEntity
import com.lecteur.core.database.entity.EpisodeEntity
import com.lecteur.core.database.entity.LibraryFolderEntity
import com.lecteur.core.database.entity.MediaFileEntity
import com.lecteur.core.database.entity.MovieEntity
import com.lecteur.core.database.entity.SeasonEntity
import com.lecteur.core.database.entity.SeriesEntity
import com.lecteur.core.database.entity.SubtitleTrackInfoEntity
import com.lecteur.core.database.entity.WatchStateEntity
import com.lecteur.core.model.AudioCodec
import com.lecteur.core.model.HdrType
import com.lecteur.core.model.MatchState
import com.lecteur.core.model.MediaCategory
import com.lecteur.core.model.VideoCodec
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
class DatabaseDaosTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testLibraryFolderInsertionAndQuery() = runTest {
        val folder = LibraryFolderEntity(
            uri = "content://com.android.externalstorage.documents/tree/primary%3AMovies",
            displayPath = "/storage/emulated/0/Movies",
            category = MediaCategory.MOVIES
        )

        val id = database.libraryFolderDao().insertFolder(folder)
        assertThat(id).isGreaterThan(0L)

        val retrieved = database.libraryFolderDao().getFolderById(id)
        assertThat(retrieved).isNotNull()
        assertThat(retrieved?.displayPath).isEqualTo("/storage/emulated/0/Movies")
        assertThat(retrieved?.category).isEqualTo(MediaCategory.MOVIES)
    }

    @Test
    fun testMultipleMediaFilesPerMovie() = runTest {
        val folderId = database.libraryFolderDao().insertFolder(
            LibraryFolderEntity(
                uri = "content://test/movies",
                displayPath = "/Movies",
                category = MediaCategory.MOVIES
            )
        )

        val movieId = database.movieDao().insertMovie(
            MovieEntity(
                title = "Inception",
                year = 2010,
                matchState = MatchState.IDENTIFIED
            )
        )

        val file1080pId = database.mediaFileDao().insertMediaFile(
            MediaFileEntity(
                folderId = folderId,
                uri = "content://test/inception_1080p.mkv",
                displayPath = "/Movies/inception_1080p.mkv",
                fileName = "inception_1080p.mkv",
                size = 8_000_000_000L,
                fingerprint = "fp_1080p",
                lastModified = 1234567L,
                width = 1920,
                height = 1080,
                videoCodec = VideoCodec.H264,
                movieId = movieId
            )
        )

        val file4kId = database.mediaFileDao().insertMediaFile(
            MediaFileEntity(
                folderId = folderId,
                uri = "content://test/inception_4k.mkv",
                displayPath = "/Movies/inception_4k.mkv",
                fileName = "inception_4k.mkv",
                size = 25_000_000_000L,
                fingerprint = "fp_4k",
                lastModified = 1234568L,
                width = 3840,
                height = 2160,
                videoCodec = VideoCodec.HEVC,
                hdrType = HdrType.HDR10,
                movieId = movieId
            )
        )

        val movieWithFiles = database.movieDao().getMovieWithFiles(movieId)
        assertThat(movieWithFiles).isNotNull()
        assertThat(movieWithFiles?.files).hasSize(2)
        assertThat(movieWithFiles?.files?.map { it.id }).containsExactly(file1080pId, file4kId)
    }

    @Test
    fun testSeriesCascadeDeletion() = runTest {
        val seriesId = database.seriesDao().insertSeries(
            SeriesEntity(
                title = "Breaking Bad",
                matchState = MatchState.IDENTIFIED
            )
        )

        val seasonId = database.seasonDao().insertSeason(
            SeasonEntity(
                seriesId = seriesId,
                seasonNumber = 1
            )
        )

        val episodeId = database.episodeDao().insertEpisode(
            EpisodeEntity(
                seriesId = seriesId,
                seasonId = seasonId,
                seasonNumber = 1,
                episodeNumber = 1,
                title = "Pilot"
            )
        )

        assertThat(database.seriesDao().getSeriesById(seriesId)).isNotNull()
        assertThat(database.seasonDao().getSeasonByNumber(seriesId, 1)).isNotNull()
        assertThat(database.episodeDao().getEpisodeById(episodeId)).isNotNull()

        // Deleting series must cascade to seasons and episodes
        database.seriesDao().deleteSeries(seriesId)

        assertThat(database.seriesDao().getSeriesById(seriesId)).isNull()
        assertThat(database.seasonDao().getSeasonByNumber(seriesId, 1)).isNull()
        assertThat(database.episodeDao().getEpisodeById(episodeId)).isNull()
    }

    @Test
    fun testWatchStateResumeList() = runTest {
        val folderId = database.libraryFolderDao().insertFolder(
            LibraryFolderEntity(uri = "uri://folder", displayPath = "/dir", category = MediaCategory.MOVIES)
        )
        val fileId = database.mediaFileDao().insertMediaFile(
            MediaFileEntity(
                folderId = folderId,
                uri = "uri://file1",
                displayPath = "/file1.mkv",
                fileName = "file1.mkv",
                size = 1000,
                fingerprint = "fp1",
                lastModified = 1000
            )
        )

        // Scenario 1: Watched only 1% -> Should NOT appear in resume list (< 2%)
        database.watchStateDao().upsertWatchState(
            WatchStateEntity(
                mediaFileId = fileId,
                positionMs = 10_000, // 10s
                durationMs = 1_000_000, // 1000s
                isCompleted = false
            )
        )
        var inProgress = database.watchStateDao().getInProgressWatchStates().first()
        assertThat(inProgress).isEmpty()

        // Scenario 2: Watched 50% -> SHOULD appear in resume list
        database.watchStateDao().upsertWatchState(
            WatchStateEntity(
                mediaFileId = fileId,
                positionMs = 500_000,
                durationMs = 1_000_000,
                isCompleted = false
            )
        )
        inProgress = database.watchStateDao().getInProgressWatchStates().first()
        assertThat(inProgress).hasSize(1)
        assertThat(inProgress.first().positionMs).isEqualTo(500_000)

        // Scenario 3: Watched 95% -> Considered finished (> 90%), should NOT appear
        database.watchStateDao().upsertWatchState(
            WatchStateEntity(
                mediaFileId = fileId,
                positionMs = 950_000,
                durationMs = 1_000_000,
                isCompleted = false
            )
        )
        inProgress = database.watchStateDao().getInProgressWatchStates().first()
        assertThat(inProgress).isEmpty()
    }

    @Test
    fun testTracksCascadeOnMediaFileDelete() = runTest {
        val folderId = database.libraryFolderDao().insertFolder(
            LibraryFolderEntity(uri = "uri://folder2", displayPath = "/dir2", category = MediaCategory.MOVIES)
        )
        val fileId = database.mediaFileDao().insertMediaFile(
            MediaFileEntity(
                folderId = folderId,
                uri = "uri://file2",
                displayPath = "/file2.mkv",
                fileName = "file2.mkv",
                size = 1000,
                fingerprint = "fp2",
                lastModified = 1000
            )
        )

        database.mediaFileDao().insertAudioTracks(
            listOf(
                AudioTrackInfoEntity(
                    mediaFileId = fileId,
                    trackIndex = 0,
                    language = "fra",
                    codec = AudioCodec.E_AC3,
                    channels = 6
                )
            )
        )

        database.mediaFileDao().insertSubtitleTracks(
            listOf(
                SubtitleTrackInfoEntity(
                    mediaFileId = fileId,
                    trackIndex = 1,
                    language = "fra"
                )
            )
        )

        val fileWithTracks = database.mediaFileDao().getMediaFileWithTracks(fileId)
        assertThat(fileWithTracks?.audioTracks).hasSize(1)
        assertThat(fileWithTracks?.subtitleTracks).hasSize(1)

        // Delete file -> tracks must cascade
        database.mediaFileDao().deleteMediaFile(fileId)

        val deletedTracks = database.mediaFileDao().getMediaFileWithTracks(fileId)
        assertThat(deletedTracks).isNull()

        // Raw table check: the child rows themselves must be gone, not just the parent
        val db = database.openHelper.writableDatabase
        db.query("SELECT COUNT(*) FROM audio_tracks").use { c ->
            c.moveToFirst()
            assertThat(c.getInt(0)).isEqualTo(0)
        }
        db.query("SELECT COUNT(*) FROM subtitle_tracks").use { c ->
            c.moveToFirst()
            assertThat(c.getInt(0)).isEqualTo(0)
        }
    }

    private suspend fun newFolder(uri: String = "uri://f"): Long =
        database.libraryFolderDao().insertFolder(
            LibraryFolderEntity(uri = uri, displayPath = "/d", category = MediaCategory.MOVIES)
        )

    private fun newFile(folderId: Long, uri: String, fingerprint: String, id: Long = 0, movieId: Long? = null) =
        MediaFileEntity(
            id = id,
            folderId = folderId,
            uri = uri,
            displayPath = "/$uri",
            fileName = uri,
            size = 1000,
            fingerprint = fingerprint,
            lastModified = 1000,
            movieId = movieId
        )

    @Test
    fun testRescanUpsertKeepsWatchStateAndTracks() = runTest {
        val folderId = newFolder()
        val fileId = database.mediaFileDao().insertMediaFile(newFile(folderId, "uri://a", "fpA"))
        database.watchStateDao().upsertWatchState(
            WatchStateEntity(mediaFileId = fileId, positionMs = 500_000, durationMs = 1_000_000)
        )
        database.mediaFileDao().insertAudioTracks(
            listOf(AudioTrackInfoEntity(mediaFileId = fileId, trackIndex = 0, language = "fra", codec = AudioCodec.AAC, channels = 2))
        )

        // A re-scan updates the same row (same id) with new metadata
        database.mediaFileDao().insertMediaFile(newFile(folderId, "uri://a", "fpA", id = fileId).copy(width = 1920, height = 1080))

        assertThat(database.mediaFileDao().getMediaFileById(fileId)?.width).isEqualTo(1920)
        assertThat(database.watchStateDao().getWatchState(fileId)?.positionMs).isEqualTo(500_000)
        assertThat(database.mediaFileDao().getMediaFileWithTracks(fileId)?.audioTracks).hasSize(1)
    }

    @Test
    fun testMovieUpsertKeepsLinkedFiles() = runTest {
        val folderId = newFolder()
        val movieId = database.movieDao().insertMovie(MovieEntity(title = "Heat", year = 1995, matchState = MatchState.TO_VERIFY))
        database.mediaFileDao().insertMediaFile(newFile(folderId, "uri://heat", "fpH", movieId = movieId))

        database.movieDao().insertMovie(
            MovieEntity(id = movieId, title = "Heat", year = 1995, matchState = MatchState.IDENTIFIED)
        )

        assertThat(database.movieDao().getMovieWithFiles(movieId)?.files).hasSize(1)
        assertThat(database.movieDao().getMovieById(movieId)?.matchState).isEqualTo(MatchState.IDENTIFIED)
    }

    @Test
    fun testFolderUpsertKeepsMediaFiles() = runTest {
        val folderId = newFolder()
        database.mediaFileDao().insertMediaFile(newFile(folderId, "uri://x", "fpX"))

        database.libraryFolderDao().insertFolder(
            LibraryFolderEntity(id = folderId, uri = "uri://f", displayPath = "/renamed", category = MediaCategory.MOVIES)
        )

        assertThat(database.mediaFileDao().getMediaFilesByFolder(folderId)).hasSize(1)
    }

    @Test
    fun testFingerprintLookupFindsMovedFile() = runTest {
        val folderId = newFolder()
        val fileId = database.mediaFileDao().insertMediaFile(newFile(folderId, "uri://old/name.mkv", "fpMoved"))

        val found = database.mediaFileDao().getMediaFileByFingerprint("fpMoved")
        assertThat(found?.id).isEqualTo(fileId)
        assertThat(database.mediaFileDao().getMediaFileByFingerprint("unknown")).isNull()
    }

    @Test
    fun testFolderAvailabilityIsReversibleAndKeepsRows() = runTest {
        val folderId = newFolder()
        val fileId = database.mediaFileDao().insertMediaFile(newFile(folderId, "uri://u", "fpU"))

        database.mediaFileDao().setFolderAvailability(folderId, false)
        assertThat(database.mediaFileDao().getMediaFileById(fileId)?.isAvailable).isFalse()

        database.mediaFileDao().setFolderAvailability(folderId, true)
        assertThat(database.mediaFileDao().getMediaFileById(fileId)?.isAvailable).isTrue()
    }

    @Test
    fun testFtsSearchFindsMovieAndSeriesAndFollowsUpdates() = runTest {
        val movieId = database.movieDao().insertMovie(
            MovieEntity(title = "Inception", originalTitle = "Inception", overview = "A thief enters dreams")
        )
        database.movieDao().insertMovie(MovieEntity(title = "Heat"))
        database.seriesDao().insertSeries(SeriesEntity(title = "Breaking Bad"))

        assertThat(database.searchDao().searchMovies("incep*").first().map { it.title }).containsExactly("Inception")
        assertThat(database.searchDao().searchMovies("dreams").first()).hasSize(1)
        assertThat(database.searchDao().searchMovies("nothing").first()).isEmpty()
        assertThat(database.searchDao().searchSeries("breaking").first().map { it.title }).containsExactly("Breaking Bad")

        // FTS index must follow content-table updates
        database.movieDao().insertMovie(MovieEntity(id = movieId, title = "Interstellar"))
        assertThat(database.searchDao().searchMovies("incep*").first()).isEmpty()
        assertThat(database.searchDao().searchMovies("interstellar").first()).hasSize(1)
    }

    @Test
    fun testResumeListOrderedByLastWatchedAndIgnoresCompleted() = runTest {
        val folderId = newFolder()
        val f1 = database.mediaFileDao().insertMediaFile(newFile(folderId, "uri://1", "fp1"))
        val f2 = database.mediaFileDao().insertMediaFile(newFile(folderId, "uri://2", "fp2"))
        val f3 = database.mediaFileDao().insertMediaFile(newFile(folderId, "uri://3", "fp3"))
        val dao = database.watchStateDao()

        dao.upsertWatchState(WatchStateEntity(mediaFileId = f1, positionMs = 400_000, durationMs = 1_000_000, lastWatchedAt = 100))
        dao.upsertWatchState(WatchStateEntity(mediaFileId = f2, positionMs = 400_000, durationMs = 1_000_000, lastWatchedAt = 200))
        dao.upsertWatchState(WatchStateEntity(mediaFileId = f3, positionMs = 400_000, durationMs = 1_000_000, isCompleted = true, lastWatchedAt = 300))

        assertThat(dao.getInProgressWatchStates().first().map { it.mediaFileId }).containsExactly(f2, f1).inOrder()
    }

    @Test
    fun testSaveScannedFileReusesRowForKnownUri() = runTest {
        val folderId = newFolder()
        val dao = database.mediaFileDao()
        val id1 = dao.saveScannedFile(newFile(folderId, "uri://dup", "fpD1"))
        database.watchStateDao().upsertWatchState(
            WatchStateEntity(mediaFileId = id1, positionMs = 500_000, durationMs = 1_000_000)
        )

        dao.saveScannedFile(newFile(folderId, "uri://dup", "fpD2"))

        assertThat(dao.getMediaFilesByFolder(folderId)).hasSize(1)
        assertThat(dao.getMediaFileByUri("uri://dup")?.fingerprint).isEqualTo("fpD2")
        assertThat(database.watchStateDao().getWatchState(id1)?.positionMs).isEqualTo(500_000)
    }
}
