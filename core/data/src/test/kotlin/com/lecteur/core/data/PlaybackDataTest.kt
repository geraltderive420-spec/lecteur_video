package com.lecteur.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lecteur.core.data.playback.ExternalSubtitleLoader
import com.lecteur.core.data.playback.MediaFileRegistrar
import com.lecteur.core.data.playback.PlaybackRequestFactory
import com.lecteur.core.data.playback.StoragePaths
import com.lecteur.core.data.playback.UriFileInfo
import com.lecteur.core.data.watch.RoomWatchStateStore
import com.lecteur.core.database.AppDatabase
import com.lecteur.core.database.entity.LibraryFolderEntity
import com.lecteur.core.database.entity.MediaFileEntity
import com.lecteur.core.database.entity.MovieEntity
import com.lecteur.core.database.entity.SeriesPreferenceEntity
import com.lecteur.core.model.MediaCategory
import com.lecteur.core.model.WatchState
import com.lecteur.core.player.tracks.LanguagePreferences
import com.lecteur.core.player.tracks.SubtitleMode
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PlaybackDataTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = AppDatabase.create(ApplicationProvider.getApplicationContext<Context>(), inMemory = true)
    }

    @After
    fun tearDown() = db.close()

    private fun info(name: String = "Film.2020.1080p.mkv", fingerprint: String = "fp") =
        UriFileInfo(displayName = name, sizeBytes = 1_000, lastModified = 5, fingerprint = fingerprint)

    private fun registrar() = MediaFileRegistrar(db.libraryFolderDao(), db.mediaFileDao())

    // region StoragePaths

    @Test
    fun primaryStorageDocumentUriMapsToPath() {
        val uri = "content://com.android.externalstorage.documents/document/primary%3AMovies%2FFilm.mkv"
        assertThat(StoragePaths.fromUri(uri)).isEqualTo("/storage/emulated/0/Movies/Film.mkv")
    }

    @Test
    fun treeDocumentAndSdCardUrisMapToPath() {
        val uri = "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3AVideos/document/1A2B-3C4D%3AVideos%2FS01%2Fe1.mkv"
        assertThat(StoragePaths.fromUri(uri)).isEqualTo("/storage/1A2B-3C4D/Videos/S01/e1.mkv")
    }

    @Test
    fun fileUriMapsAndOtherProvidersDoNot() {
        assertThat(StoragePaths.fromUri("file:///storage/emulated/0/Movies/a%20b.mkv")).isEqualTo("/storage/emulated/0/Movies/a b.mkv")
        assertThat(StoragePaths.fromUri("content://com.android.providers.downloads.documents/document/42")).isNull()
        assertThat(StoragePaths.fromUri("content://media/external/video/media/12")).isNull()
        assertThat(StoragePaths.fromUri("content://com.android.externalstorage.documents/document/")).isNull()
    }

    @Test
    fun pathHelpers() {
        assertThat(StoragePaths.parentOf("/a/b/c.mkv")).isEqualTo("/a/b")
        assertThat(StoragePaths.nameOf("/a/b/c.mkv")).isEqualTo("c.mkv")
        assertThat(StoragePaths.parentOf("c.mkv")).isNull()
    }

    // endregion

    // region MediaFileRegistrar

    @Test
    fun newFileIsRegisteredInTheHiddenDisabledFolder() = runTest {
        val file = registrar().register("content://x/film", info())

        assertThat(file.id).isGreaterThan(0)
        val stored = db.mediaFileDao().getMediaFileById(file.id)!!
        assertThat(stored.fileName).isEqualTo("Film.2020.1080p.mkv")
        val folder = db.libraryFolderDao().getFolderById(stored.folderId)!!
        assertThat(folder.uri).isEqualTo(MediaFileRegistrar.MANUAL_FOLDER_URI)
        assertThat(folder.enabled).isFalse()
        assertThat(db.libraryFolderDao().getActiveFolders()).isEmpty()
    }

    @Test
    fun reopeningTheSameFileReusesTheRow() = runTest {
        val first = registrar().register("content://x/film", info())
        val second = registrar().register("content://x/film", info())
        assertThat(second.id).isEqualTo(first.id)
        assertThat(db.mediaFileDao().getMediaFilesByFolder(first.folderId)).hasSize(1)
    }

    @Test
    fun movedFileKeepsItsRowAndItsProgress() = runTest {
        val first = registrar().register("content://old/film", info(fingerprint = "same-content"))
        RoomWatchStateStore(db.watchStateDao()).save(WatchState(mediaFileId = first.id, positionMs = 600_000, durationMs = 1_200_000))

        val moved = registrar().register("content://new/Film renamed.mkv", info(name = "Film renamed.mkv", fingerprint = "same-content"))

        assertThat(moved.id).isEqualTo(first.id)
        assertThat(db.mediaFileDao().getMediaFileById(first.id)?.uri).isEqualTo("content://new/Film renamed.mkv")
        assertThat(RoomWatchStateStore(db.watchStateDao()).get(first.id)?.positionMs).isEqualTo(600_000)
    }

    @Test
    fun scannedFileOpenedManuallyKeepsItsFolderAndMetadataLinks() = runTest {
        val folderId = db.libraryFolderDao().insertFolder(
            LibraryFolderEntity(uri = "u", displayPath = "/m", category = MediaCategory.MOVIES)
        )
        val movieId = db.movieDao().insertMovie(MovieEntity(title = "Film"))
        val scannedId = db.mediaFileDao().insertMediaFile(
            MediaFileEntity(
                folderId = folderId, uri = "content://x/film", displayPath = "/m/film", fileName = "film.mkv",
                size = 1, fingerprint = "fp", lastModified = 1, movieId = movieId
            )
        )

        val registered = registrar().register("content://x/film", info())

        assertThat(registered.id).isEqualTo(scannedId)
        val stored = db.mediaFileDao().getMediaFileById(scannedId)!!
        assertThat(stored.folderId).isEqualTo(folderId)
        assertThat(stored.movieId).isEqualTo(movieId)
    }

    // endregion

    // region RoomWatchStateStore

    @Test
    fun watchStateRoundTripKeepsEveryField() = runTest {
        val file = registrar().register("content://x/f", info())
        val store = RoomWatchStateStore(db.watchStateDao())
        val state = WatchState(
            mediaFileId = file.id, positionMs = 42_000, durationMs = 100_000, isCompleted = false, playCount = 2,
            lastWatchedAt = 99, selectedAudioTrackIndex = 1, selectedSubtitleTrackIndex = -1,
            audioDelayMs = 150, subtitleDelayMs = -250, displayMode = "FILL"
        )
        store.save(state)

        assertThat(store.get(file.id)!!.copy(id = 0)).isEqualTo(state)
    }

    @Test
    fun savingTwiceUpdatesTheSameRow() = runTest {
        val file = registrar().register("content://x/f", info())
        val store = RoomWatchStateStore(db.watchStateDao())
        store.save(WatchState(mediaFileId = file.id, positionMs = 10))
        val first = store.get(file.id)!!
        store.save(first.copy(positionMs = 20))
        assertThat(store.get(file.id)?.positionMs).isEqualTo(20)
        assertThat(store.get(file.id)?.id).isEqualTo(first.id)
    }

    // endregion

    // region factory helpers

    @Test
    fun seriesLanguageChoiceOutranksGlobalButKeepsItAsFallback() {
        val global = LanguagePreferences(audio = listOf("fr"), subtitles = listOf("fr"), subtitleMode = SubtitleMode.AUTO)
        val merged = PlaybackRequestFactory.mergeLanguagePreferences(global, SeriesPreferenceEntity(1, "ja", "fr"))
        assertThat(merged.audio).containsExactly("ja", "fr").inOrder()
        assertThat(merged.subtitles).containsExactly("fr")
        assertThat(merged.subtitleMode).isEqualTo(SubtitleMode.AUTO)
        assertThat(PlaybackRequestFactory.mergeLanguagePreferences(global, null)).isEqualTo(global)
        assertThat(PlaybackRequestFactory.mergeLanguagePreferences(global, SeriesPreferenceEntity(1, null, null))).isEqualTo(global)
    }

    @Test
    fun displayTitleCleansReleaseNames() {
        assertThat(PlaybackRequestFactory.displayTitle("Blade.Runner.2049.2017.2160p.UHD.mkv")).isEqualTo("Blade Runner 2049")
        assertThat(PlaybackRequestFactory.displayTitle("The.Show.S02E05.720p.mkv")).isEqualTo("The Show S02E05")
        assertThat(PlaybackRequestFactory.displayTitle("1080p.mkv")).isNotEmpty()
    }

    // endregion

    // region ExternalSubtitleLoader

    @Test
    fun siblingSubtitlesAreFoundNextToARealFile() {
        val dir = tmp.newFolder("movies")
        File(dir, "Film.mkv").writeText("x")
        File(dir, "Film.fr.srt").writeText("1")
        File(dir, "Film.en.forced.ass").writeText("1")
        File(dir, "Film.idx").writeText("1")
        File(dir, "Other.srt").writeText("1")

        val found = ExternalSubtitleLoader().find("file://" + File(dir, "Film.mkv").absolutePath.replace('\\', '/'))

        assertThat(found.map { it.language }).containsExactly("en", "fr")
        assertThat(found.first { it.language == "en" }.isForced).isTrue()
        assertThat(found.all { it.uri.startsWith("file:") }).isTrue()
        assertThat(found.map { it.mimeType }).containsExactly("text/x-ssa", "application/x-subrip")
    }

    @Test
    fun unknownProvidersYieldNoSiblings() {
        assertThat(ExternalSubtitleLoader().find("content://media/external/video/media/12")).isEmpty()
    }

    // endregion
}
