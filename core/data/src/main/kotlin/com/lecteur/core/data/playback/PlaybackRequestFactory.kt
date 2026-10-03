package com.lecteur.core.data.playback

import com.lecteur.core.common.parser.FilenameParser
import com.lecteur.core.database.dao.EpisodeDao
import com.lecteur.core.database.dao.MediaFileDao
import com.lecteur.core.database.dao.MetadataDao
import com.lecteur.core.database.dao.MovieDao
import com.lecteur.core.database.dao.SeriesDao
import com.lecteur.core.database.entity.MediaFileEntity
import com.lecteur.core.database.entity.SeriesPreferenceEntity
import com.lecteur.core.model.WatchState
import com.lecteur.core.player.display.DisplayMode
import com.lecteur.core.player.engine.PlaybackRequest
import com.lecteur.core.player.resume.ResumePolicy
import com.lecteur.core.player.resume.WatchStateStore
import com.lecteur.core.player.settings.PlayerSettingsRepository
import com.lecteur.core.player.tracks.LanguagePreferences
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Everything the player needs to start a media: the engine request plus what to ask the user before starting. */
data class PreparedPlayback(
    val request: PlaybackRequest,
    val watchState: WatchState?,
    /** Position to offer in "Reprendre à HH:MM:SS"; null when playback starts from the beginning without asking. */
    val resumePromptMs: Long?,
    val displayMode: DisplayMode
)

class PlaybackRequestFactory @Inject constructor(
    private val reader: UriFileInfoReader,
    private val registrar: MediaFileRegistrar,
    private val subtitleLoader: ExternalSubtitleLoader,
    private val mediaFileDao: MediaFileDao,
    private val episodeDao: EpisodeDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val metadataDao: MetadataDao,
    private val watchStateStore: WatchStateStore,
    private val settingsRepository: PlayerSettingsRepository
) {
    /** File chosen by hand or opened from another app. */
    suspend fun forUri(uri: String): PreparedPlayback {
        val info = reader.read(uri)
        return build(registrar.register(uri, info))
    }

    /** File already in the library. */
    suspend fun forMediaFile(mediaFileId: Long): PreparedPlayback? =
        mediaFileDao.getMediaFileById(mediaFileId)?.let { build(it) }

    private suspend fun build(file: MediaFileEntity): PreparedPlayback {
        val settings = settingsRepository.settings.first()
        val watch = watchStateStore.get(file.id)
        val seriesPreference = file.episodeId
            ?.let { episodeDao.getEpisodeById(it)?.seriesId }
            ?.let { metadataDao.getSeriesPreference(it) }

        val request = PlaybackRequest(
            uri = file.uri,
            title = libraryTitle(file) ?: displayTitle(file.fileName),
            mediaFileId = file.id,
            externalSubtitles = subtitleLoader.find(file.uri),
            languagePreferences = mergeLanguagePreferences(settings.languagePreferences, seriesPreference),
            savedAudioIndex = watch?.selectedAudioTrackIndex,
            savedSubtitleIndex = watch?.selectedSubtitleTrackIndex,
            audioDelayMs = watch?.audioDelayMs ?: 0,
            subtitleDelayMs = watch?.subtitleDelayMs ?: 0
        )
        val resume = watch?.let { ResumePolicy.resumePosition(it.positionMs, it.durationMs, it.isCompleted) }
        return PreparedPlayback(request, watch, resume, DisplayMode.fromName(watch?.displayMode))
    }

    /** "Alien (1979)" or "Show · S02E05 · Title" once the file is identified; null falls back to the cleaned file name. */
    private suspend fun libraryTitle(file: MediaFileEntity): String? = when {
        file.movieId != null -> movieDao.getMovieById(file.movieId!!)?.let { movie ->
            movie.year?.let { "${movie.title} ($it)" } ?: movie.title
        }
        file.episodeId != null -> episodeDao.getEpisodeById(file.episodeId!!)?.let { episode ->
            seriesDao.getSeriesById(episode.seriesId)?.let { series ->
                "%s · S%02dE%02d".format(series.title, episode.seasonNumber, episode.episodeNumber) +
                    episode.title?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            }
        }
        else -> null
    }

    companion object {
        /** A series' own language choice outranks the global one but keeps it as a fallback. */
        fun mergeLanguagePreferences(global: LanguagePreferences, series: SeriesPreferenceEntity?): LanguagePreferences {
            if (series == null) return global
            return global.copy(
                audio = listOfNotNull(series.preferredAudioLanguage).plus(global.audio).distinct(),
                subtitles = listOfNotNull(series.preferredSubtitleLanguage).plus(global.subtitles).distinct()
            )
        }

        /** "Show.Name.S01E02.1080p.mkv" -> "Show Name S01E02"; falls back to the raw file name. */
        fun displayTitle(fileName: String): String {
            val parsed = FilenameParser.parse(fileName)
            if (parsed.cleanTitle.isBlank()) return fileName
            val episode = parsed.episodeNumbers.firstOrNull()
            return if (parsed.seasonNumber != null && episode != null) {
                "%s S%02dE%02d".format(parsed.cleanTitle, parsed.seasonNumber, episode)
            } else {
                parsed.cleanTitle
            }
        }
    }
}
