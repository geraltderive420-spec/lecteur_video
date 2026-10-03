package com.lecteur.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.lecteur.core.player.settings.PlayerSettings
import com.lecteur.core.player.settings.PlayerSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject

class DataStorePlayerSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : PlayerSettingsRepository {

    override val settings: Flow<PlayerSettings> = dataStore.data
        .catch { error ->
            // A corrupted preferences file must not make the player unusable: fall back to the defaults
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error
        }
        .map(PlayerSettingsPreferences::read)

    override suspend fun update(transform: (PlayerSettings) -> PlayerSettings) {
        dataStore.edit { prefs ->
            PlayerSettingsPreferences.write(prefs, transform(PlayerSettingsPreferences.read(prefs)))
        }
    }
}
