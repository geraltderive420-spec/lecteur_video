package com.lecteur.audio

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

@HiltAndroidApp
class LecteurApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var scanScheduler: ScanScheduler
    @Inject lateinit var librarySettings: LibrarySettingsRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    /** One image loader for the whole app: display and the prefetching done at identification share its disk cache. */
    override fun newImageLoader(): ImageLoader = LecteurImageLoader.create(this)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            if (librarySettings.settings.first().scanOnLaunch) scanScheduler.scanOnLaunch()
        }
        // The settings screen changes the interval at any time: the schedule follows without the screen knowing the scheduler
        appScope.launch {
            librarySettings.settings.map { it.scanIntervalHours }.distinctUntilChanged().collect { scanScheduler.schedulePeriodic(it) }
        }
    }
}
