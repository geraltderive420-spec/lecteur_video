package com.lecteur.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import coil.imageLoader
import com.lecteur.core.model.AppearanceSettings
import com.lecteur.core.model.HomeLayout
import com.lecteur.core.model.HomeRow
import com.lecteur.core.model.LibrarySection
import com.lecteur.core.model.LibrarySort
import com.lecteur.core.model.LibraryView
import com.lecteur.core.model.PosterSize
import com.lecteur.core.model.SectionPrefs
import com.lecteur.core.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private fun DataStore<Preferences>.safeData(): Flow<Preferences> =
    data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }

private inline fun <reified E : Enum<E>> enumOf(name: String?, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default

@Singleton
class AppearanceRepository @Inject constructor(private val dataStore: DataStore<Preferences>) {

    private val theme = stringPreferencesKey("theme_mode")
    private val dynamic = booleanPreferencesKey("dynamic_color")

    val settings: Flow<AppearanceSettings> = dataStore.safeData().map { prefs ->
        val defaults = AppearanceSettings()
        AppearanceSettings(enumOf(prefs[theme], defaults.themeMode), prefs[dynamic] ?: defaults.dynamicColor)
    }.distinctUntilChanged()

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[theme] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[dynamic] = enabled }
    }
}

/** Order and visibility of the home rows. */
@Singleton
class HomeLayoutRepository @Inject constructor(private val dataStore: DataStore<Preferences>) {

    private val key = stringPreferencesKey("home_layout")

    val layout: Flow<HomeLayout> = dataStore.safeData().map { HomeLayout.decode(it[key]) }.distinctUntilChanged()

    suspend fun move(row: HomeRow, delta: Int) = update { it.move(row, delta) }

    suspend fun setVisible(row: HomeRow, visible: Boolean) = update { it.setVisible(row, visible) }

    suspend fun reset() {
        dataStore.edit { it.remove(key) }
    }

    private suspend fun update(transform: (HomeLayout) -> HomeLayout) {
        dataStore.edit { prefs -> prefs[key] = HomeLayout.encode(transform(HomeLayout.decode(prefs[key]))) }
    }
}

/** Sort and view of each library shelf, remembered between launches; the poster size is one choice for both. */
@Singleton
class LibraryViewRepository @Inject constructor(private val dataStore: DataStore<Preferences>) {

    private val posterSize = stringPreferencesKey("poster_size")

    private fun sortField(section: LibrarySection) = stringPreferencesKey("lib_${section.name}_sort_field")
    private fun sortOrder(section: LibrarySection) = stringPreferencesKey("lib_${section.name}_sort_order")
    private fun view(section: LibrarySection) = stringPreferencesKey("lib_${section.name}_view")

    fun prefs(section: LibrarySection): Flow<SectionPrefs> = dataStore.safeData().map { prefs ->
        val defaults = SectionPrefs()
        SectionPrefs(
            sort = LibrarySort(
                enumOf(prefs[sortField(section)], defaults.sort.field),
                enumOf(prefs[sortOrder(section)], defaults.sort.order)
            ),
            view = enumOf(prefs[view(section)], defaults.view),
            posterSize = enumOf(prefs[posterSize], defaults.posterSize)
        )
    }.distinctUntilChanged()

    suspend fun setSort(section: LibrarySection, sort: LibrarySort) {
        dataStore.edit {
            it[sortField(section)] = sort.field.name
            it[sortOrder(section)] = sort.order.name
        }
    }

    suspend fun setView(section: LibrarySection, view: LibraryView) {
        dataStore.edit { it[view(section)] = view.name }
    }

    suspend fun setPosterSize(size: PosterSize) {
        dataStore.edit { it[posterSize] = size.name }
    }
}

/** Whether the guided first launch has been seen: it never comes back once the user got through it or skipped it. */
@Singleton
class OnboardingRepository @Inject constructor(private val dataStore: DataStore<Preferences>) {

    private val done = booleanPreferencesKey("onboarding_done")

    val isDone: Flow<Boolean> = dataStore.safeData().map { it[done] ?: false }.distinctUntilChanged()

    suspend fun markDone() {
        dataStore.edit { it[done] = true }
    }
}

/** The shared image cache (posters, backdrops, stills): its size, and a way to empty it. */
@Singleton
class ImageCacheController @Inject constructor(@ApplicationContext private val context: Context) {

    private val loader get() = context.imageLoader

    fun sizeBytes(): Long = loader.diskCache?.size ?: 0L

    fun maxSizeBytes(): Long = loader.diskCache?.maxSize ?: 0L

    fun clear() {
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
    }
}
