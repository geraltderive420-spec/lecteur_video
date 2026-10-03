package com.lecteur.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.lecteur.core.data.settings.DataStorePlayerSettingsRepository
import com.lecteur.core.player.settings.DecoderMode
import com.lecteur.core.player.settings.EqualizerPreset
import com.lecteur.core.player.settings.PassthroughMode
import com.lecteur.core.player.settings.PlayerSettings
import com.lecteur.core.player.settings.SubtitleEdge
import com.lecteur.core.player.settings.SubtitleStyle
import com.lecteur.core.player.tracks.SubtitleMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.repository(): DataStorePlayerSettingsRepository {
        val store = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tmp.newFile("settings-${System.nanoTime()}.preferences_pb") }
        )
        return DataStorePlayerSettingsRepository(store)
    }

    @Test
    fun emptyStoreGivesDefaults() = runTest(UnconfinedTestDispatcher()) {
        assertThat(repository().settings.first()).isEqualTo(PlayerSettings())
    }

    @Test
    fun everySettingSurvivesAWriteAndRead() = runTest(UnconfinedTestDispatcher()) {
        val repo = repository()
        val custom = PlayerSettings(
            seekStepSeconds = 30,
            longPressSpeed = 3f,
            gesturesEnabled = false,
            volumeBoostEnabled = true,
            preferredAudioLanguages = listOf("ja", "en"),
            preferredSubtitleLanguages = listOf("fr"),
            subtitleMode = SubtitleMode.ALWAYS,
            subtitleStyle = SubtitleStyle(
                sizeFraction = 0.08f, textColorArgb = 0xFFFFFF00.toInt(), edge = SubtitleEdge.DROP_SHADOW,
                edgeColorArgb = 0xFF112233.toInt(), backgroundArgb = 0x80000000.toInt(), bottomPaddingFraction = 0.2f
            ),
            decoderMode = DecoderMode.SOFTWARE_PREFERRED,
            tunneling = true,
            passthrough = PassthroughMode.OFF,
            backgroundAudio = true,
            lockedRotation = true,
            nightMode = true,
            equalizer = EqualizerPreset.VOICE
        )
        repo.update { custom }
        assertThat(repo.settings.first()).isEqualTo(custom)
    }

    @Test
    fun updateChangesOnlyWhatTheTransformTouches() = runTest(UnconfinedTestDispatcher()) {
        val repo = repository()
        repo.update { it.copy(seekStepSeconds = 20) }
        repo.update { it.copy(nightMode = true) }
        val settings = repo.settings.first()
        assertThat(settings.seekStepSeconds).isEqualTo(20)
        assertThat(settings.nightMode).isTrue()
        assertThat(settings.preferredAudioLanguages).isEqualTo(PlayerSettings().preferredAudioLanguages)
    }

    @Test
    fun outOfRangeStoredValuesAreClamped() = runTest(UnconfinedTestDispatcher()) {
        val repo = repository()
        repo.update { it.copy(seekStepSeconds = 9_999, longPressSpeed = 50f, subtitleStyle = it.subtitleStyle.copy(sizeFraction = 5f)) }
        val settings = repo.settings.first()
        assertThat(settings.seekStepSeconds).isEqualTo(120)
        assertThat(settings.longPressSpeed).isEqualTo(4f)
        assertThat(settings.subtitleStyle.sizeFraction).isEqualTo(0.15f)
    }
}
