package com.lecteur.tv

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.lecteur.core.data.identify.LecteurImageLoader
import com.lecteur.core.data.settings.LibrarySettingsRepository
import com.lecteur.feature.scanner.work.ScanScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Same wiring as the phone app: the TV scans its own folders and keeps its own library; companion mode needs none of it. */
@HiltAndroidApp
class TvApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var scanScheduler: ScanScheduler
    @Inject lateinit var librarySettings: LibrarySettingsRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun newImageLoader(): ImageLoader = LecteurImageLoader.create(this)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            if (librarySettings.settings.first().scanOnLaunch) scanScheduler.scanOnLaunch()
        }
        appScope.launch {
            librarySettings.settings.map { it.scanIntervalHours }.distinctUntilChanged().collect { scanScheduler.schedulePeriodic(it) }
        }
    }
}
