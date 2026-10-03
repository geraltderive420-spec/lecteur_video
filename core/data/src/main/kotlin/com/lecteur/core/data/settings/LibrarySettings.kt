package com.lecteur.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lecteur.core.model.MetadataLanguageSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class LibrarySettings(
    /** TMDB language tag of the metadata: French by default, English fills what is missing. */
    val metadataLanguage: String = "fr-FR",
    val scanOnLaunch: Boolean = true,
    /** 0 turns the periodic scan off. */
    val scanIntervalHours: Int = 6
) {
    companion object {
        val SUPPORTED_LANGUAGES = listOf("fr-FR", "en-US", "es-ES", "de-DE", "it-IT", "pt-PT", "ja-JP")
    }
}

@Singleton
class LibrarySettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : MetadataLanguageSource {

    private val language = stringPreferencesKey("metadata_language")
    private val onLaunch = booleanPreferencesKey("scan_on_launch")
    private val interval = intPreferencesKey("scan_interval_hours")

    val settings: Flow<LibrarySettings> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            val defaults = LibrarySettings()
            LibrarySettings(
                metadataLanguage = prefs[language]?.takeIf { it in LibrarySettings.SUPPORTED_LANGUAGES } ?: defaults.metadataLanguage,
                scanOnLaunch = prefs[onLaunch] ?: defaults.scanOnLaunch,
                scanIntervalHours = (prefs[interval] ?: defaults.scanIntervalHours).coerceIn(0, 24 * 7)
            )
        }

    override suspend fun primary(): String = settings.first().metadataLanguage

    suspend fun setMetadataLanguage(tag: String) {
        if (tag in LibrarySettings.SUPPORTED_LANGUAGES) dataStore.edit { it[language] = tag }
    }

    suspend fun setScanOnLaunch(enabled: Boolean) = dataStore.edit { it[onLaunch] = enabled }.let { }

    suspend fun setScanIntervalHours(hours: Int) = dataStore.edit { it[interval] = hours.coerceIn(0, 24 * 7) }.let { }
}
