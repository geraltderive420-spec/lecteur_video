package com.lecteur.feature.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lecteur.core.data.details.DetailRepository
import com.lecteur.core.data.identify.MetadataRepository
import com.lecteur.core.data.library.FavoritesRepository
import com.lecteur.core.data.library.ListPickerModel
import com.lecteur.core.data.library.UserListsRepository
import com.lecteur.core.data.library.WatchedRepository
import com.lecteur.core.data.playback.PlayPlanner
import com.lecteur.core.model.EpisodeItem
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.MetadataException
import com.lecteur.core.model.MovieDetail
import com.lecteur.core.model.PlayPlan
import com.lecteur.core.model.SeasonDetail
import com.lecteur.core.model.SeriesDetail
import com.lecteur.core.model.WatchStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface DetailState<out T> {
    data object Loading : DetailState<Nothing>

    /** The title is gone: merged into another by a correction, or its folder was removed. */
    data object Missing : DetailState<Nothing>
    data class Ready<T>(val value: T) : DetailState<T>
}

sealed interface DetailEvent {
    data class Play(val plan: PlayPlan) : DetailEvent
    data class Message(val text: String) : DetailEvent
}

private fun describe(error: Throwable): String = when (error) {
    is MetadataException.Offline -> "Pas de connexion : les informations ne peuvent pas être actualisées pour le moment."
    is MetadataException.NotConfigured -> "Clé API TMDB absente : ajoutez tmdb.apiKey dans local.properties."
    is MetadataException.NotFound -> "Ce titre n'existe plus sur TMDB."
    is MetadataException.Http -> "TMDB a répondu par une erreur (${error.code}). Réessayez dans un moment."
    else -> "Erreur inattendue : ${error.message ?: error::class.simpleName}"
}

@HiltViewModel
class MovieDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    detail: DetailRepository,
    private val planner: PlayPlanner,
    private val watched: WatchedRepository,
    private val favorites: FavoritesRepository,
    private val metadata: MetadataRepository,
    lists: UserListsRepository
) : ViewModel() {

    val listPicker = ListPickerModel(lists, viewModelScope)

    private val movieId: Long = checkNotNull(savedState.get<Long>("movieId")) { "movieId is missing from the navigation arguments" }

    val state: StateFlow<DetailState<MovieDetail>> = detail.observeMovie(movieId)
        .map { if (it == null) DetailState.Missing else DetailState.Ready(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState.Loading)

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val eventChannel = Channel<DetailEvent>(Channel.BUFFERED)
    val events: Flow<DetailEvent> = eventChannel.receiveAsFlow()

    /** [fileId] picks a version; null plays the one being watched, else the best available. */
    fun play(fileId: Long?) {
        viewModelScope.launch {
            val plan = planner.forMovie(movieId, fileId)
            eventChannel.send(
                if (plan != null) DetailEvent.Play(plan)
                else DetailEvent.Message("Aucune copie de ce film n'est disponible : le support est peut-être débranché.")
            )
        }
    }

    fun toggleWatched() {
        val current = (state.value as? DetailState.Ready)?.value ?: return
        viewModelScope.launch { watched.setMovieWatched(movieId, current.watch != WatchStatus.WATCHED) }
    }

    fun toggleFavorite() {
        viewModelScope.launch { favorites.toggleMovie(movieId) }
    }

    fun openListPicker() {
        val movie = (state.value as? DetailState.Ready)?.value?.movie ?: return
        listPicker.open(MediaKind.MOVIE, movieId, movie.title)
    }

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            val outcome = runCatching { metadata.refresh(MediaKind.MOVIE, movieId) }
            _refreshing.value = false
            eventChannel.send(
                DetailEvent.Message(
                    outcome.fold(
                        onSuccess = { if (it) "Informations actualisées" else "Rien à actualiser : ce film n'est pas encore identifié." },
                        onFailure = ::describe
                    )
                )
            )
        }
    }
}

@HiltViewModel
class SeriesDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val detail: DetailRepository,
    private val planner: PlayPlanner,
    private val watched: WatchedRepository,
    private val favorites: FavoritesRepository,
    private val metadata: MetadataRepository,
    lists: UserListsRepository
) : ViewModel() {

    val listPicker = ListPickerModel(lists, viewModelScope)

    private val seriesId: Long = checkNotNull(savedState.get<Long>("seriesId")) { "seriesId is missing from the navigation arguments" }

    val state: StateFlow<DetailState<SeriesDetail>> = detail.observeSeries(seriesId)
        .map { if (it == null) DetailState.Missing else DetailState.Ready(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState.Loading)

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val eventChannel = Channel<DetailEvent>(Channel.BUFFERED)
    val events: Flow<DetailEvent> = eventChannel.receiveAsFlow()

    /** What the series' "Lire" button stands for: resume, next episode, first episode, rewatch. */
    fun playSeries() = launchPlan("Aucun épisode de cette série n'est disponible : le support est peut-être débranché.") {
        planner.forSeries(seriesId)
    }

    /** The episode and those after it; [fileId] picks one of its versions. */
    fun playEpisode(episode: EpisodeItem, fileId: Long? = null) =
        launchPlan("Cet épisode n'est pas disponible : le support est peut-être débranché.") {
            planner.forEpisode(seriesId, episode.episodeId, fileId)
        }

    fun setEpisodeWatched(episode: EpisodeItem, isWatched: Boolean) {
        viewModelScope.launch { watched.setEpisodeWatched(episode.episodeId, isWatched) }
    }

    fun setSeasonWatched(season: SeasonDetail, isWatched: Boolean) {
        viewModelScope.launch { watched.setSeasonWatched(seriesId, season.seasonNumber, isWatched) }
    }

    fun setSeriesWatched(isWatched: Boolean) {
        viewModelScope.launch { watched.setSeriesWatched(seriesId, isWatched) }
    }

    fun toggleFavorite() {
        viewModelScope.launch { favorites.toggleSeries(seriesId) }
    }

    fun openListPicker() {
        val series = (state.value as? DetailState.Ready)?.value?.series ?: return
        listPicker.open(MediaKind.SERIES, seriesId, series.title)
    }

    fun setLanguages(audio: String?, subtitles: String?) {
        viewModelScope.launch { detail.setSeriesPreference(seriesId, audio, subtitles) }
    }

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            val outcome = runCatching { metadata.refresh(MediaKind.SERIES, seriesId) }
            _refreshing.value = false
            eventChannel.send(
                DetailEvent.Message(
                    outcome.fold(
                        onSuccess = { if (it) "Informations actualisées" else "Rien à actualiser : cette série n'est pas encore identifiée." },
                        onFailure = ::describe
                    )
                )
            )
        }
    }

    private fun launchPlan(unavailable: String, plan: suspend () -> PlayPlan?) {
        viewModelScope.launch {
            val result = plan()
            eventChannel.send(if (result != null) DetailEvent.Play(result) else DetailEvent.Message(unavailable))
        }
    }
}
