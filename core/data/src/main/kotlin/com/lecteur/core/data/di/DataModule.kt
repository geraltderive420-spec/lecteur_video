package com.lecteur.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.lecteur.core.data.browse.DirectoryBrowser
import com.lecteur.core.data.browse.DocumentsContractBrowser
import com.lecteur.core.data.identify.CoilImagePrefetcher
import com.lecteur.core.data.identify.ImagePrefetcher
import com.lecteur.core.data.library.AndroidUriPermissions
import com.lecteur.core.data.library.UriPermissions
import com.lecteur.core.data.scan.AndroidFileInspector
import com.lecteur.core.data.scan.ContentResolverSidecarReader
import com.lecteur.core.data.scan.DocumentsContractWalker
import com.lecteur.core.data.scan.FileInspector
import com.lecteur.core.data.scan.FolderWalker
import com.lecteur.core.data.scan.SidecarReader
import com.lecteur.core.data.settings.DataStorePlayerSettingsRepository
import com.lecteur.core.data.settings.LibrarySettingsRepository
import com.lecteur.core.model.MetadataLanguageSource
import com.lecteur.core.data.watch.RoomWatchStateStore
import com.lecteur.core.database.AppDatabase
import com.lecteur.core.database.dao.EpisodeDao
import com.lecteur.core.database.dao.LibraryDao
import com.lecteur.core.database.dao.LibraryFolderDao
import com.lecteur.core.database.dao.MediaFileDao
import com.lecteur.core.database.dao.MetadataDao
import com.lecteur.core.database.dao.MovieDao
import com.lecteur.core.database.dao.SearchDao
import com.lecteur.core.database.dao.SeriesAliasDao
import com.lecteur.core.database.dao.SeriesDao
import com.lecteur.core.database.dao.SeasonDao
import com.lecteur.core.database.dao.UserListDao
import com.lecteur.core.database.dao.WatchStateDao
import com.lecteur.core.player.resume.WatchStateStore
import com.lecteur.core.player.settings.PlayerSettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase = AppDatabase.create(context)

    @Provides fun libraryFolderDao(db: AppDatabase): LibraryFolderDao = db.libraryFolderDao()
    @Provides fun movieDao(db: AppDatabase): MovieDao = db.movieDao()
    @Provides fun seriesDao(db: AppDatabase): SeriesDao = db.seriesDao()
    @Provides fun episodeDao(db: AppDatabase): EpisodeDao = db.episodeDao()
    @Provides fun seasonDao(db: AppDatabase): SeasonDao = db.seasonDao()
    @Provides fun seriesAliasDao(db: AppDatabase): SeriesAliasDao = db.seriesAliasDao()
    @Provides fun mediaFileDao(db: AppDatabase): MediaFileDao = db.mediaFileDao()
    @Provides fun watchStateDao(db: AppDatabase): WatchStateDao = db.watchStateDao()
    @Provides fun searchDao(db: AppDatabase): SearchDao = db.searchDao()
    @Provides fun metadataDao(db: AppDatabase): MetadataDao = db.metadataDao()
    @Provides fun libraryDao(db: AppDatabase): LibraryDao = db.libraryDao()
    @Provides fun userListDao(db: AppDatabase): UserListDao = db.userListDao()

    @Provides
    @Singleton
    fun preferencesDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { context.preferencesDataStoreFile("lecteur_settings") }
        )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun watchStateStore(impl: RoomWatchStateStore): WatchStateStore
    @Binds abstract fun playerSettings(impl: DataStorePlayerSettingsRepository): PlayerSettingsRepository
    @Binds abstract fun folderWalker(impl: DocumentsContractWalker): FolderWalker
    @Binds abstract fun fileInspector(impl: AndroidFileInspector): FileInspector
    @Binds abstract fun sidecarReader(impl: ContentResolverSidecarReader): SidecarReader
    @Binds abstract fun imagePrefetcher(impl: CoilImagePrefetcher): ImagePrefetcher
    @Binds abstract fun uriPermissions(impl: AndroidUriPermissions): UriPermissions
    @Binds abstract fun metadataLanguage(impl: LibrarySettingsRepository): MetadataLanguageSource
    @Binds abstract fun directoryBrowser(impl: DocumentsContractBrowser): DirectoryBrowser
}
