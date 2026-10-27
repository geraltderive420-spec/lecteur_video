package com.lecteur.feature.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lecteur.core.common.library.Catalog
import com.lecteur.core.data.details.DetailRepository
import com.lecteur.core.data.identify.MetadataRepository
import com.lecteur.core.data.library.FavoritesRepository
import com.lecteur.core.data.library.ListPickerModel
import com.lecteur.core.data.library.UserListsRepository
import com.lecteur.core.data.library.WatchedRepository
import com.lecteur.core.data.playback.PlayPlanner
import com.lecteur.core.data.shared.CatalogState
import com.lecteur.core.data.shared.PhoneCopies
import com.lecteur.core.data.shared.SERVER_ARG
import com.lecteur.core.data.shared.SharedLibraryRepository
import com.lecteur.core.data.shared.catalogOrNull
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

/** A PC's title follows the PC's catalog: loading until it is read, missing once the PC no longer shares it. */
private fun <T> SharedLibraryRepository.detailOf(serverId: String, find: (Catalog) -> T?): Flow<DetailState<T>> = observe(serverId).map { state ->
    val catalog = state.catalogOrNull
    when {
        catalog != null -> find(catalog)?.let { DetailState.Ready(it) } ?: DetailState.Missing
        state is CatalogState.Failed -> DetailState.Missing
        else -> DetailState.Loading
    }
}

@HiltViewModel
class MovieDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    detail: DetailRepository,
    private val planner: PlayPlanner,
    private val watched: WatchedRepository,
    private val favorites: FavoritesRepository,
    private val metadata: MetadataRepository,
    lists: UserListsRepository,
    private val shared: SharedLibraryRepository,
    copies: PhoneCopies
) : ViewModel() {

    val listPicker = ListPickerModel(lists, viewModelScope)

    private val movieId: Long = checkNotNull(savedState.get<Long>("movieId")) { "movieId is missing from the navigation arguments" }

    /** Set for a film of a PC's library (its ids are the PC's); null for one of this phone's. */
    private val serverId: String? = savedState.get<String>(SERVER_ARG)?.takeIf { it.isNotBlank() }

    /** The PC the film comes from, named on the page; null for this phone's films. */
    val sourceName: String? = serverId?.let { shared.server(it)?.name ?: "PC" }

    val state: StateFlow<DetailState<MovieDetail>> = (
        if (serverId == null) detail.observeMovie(movieId).map { if (it == null) DetailState.Missing else DetailState.Ready(it) }
        else shared.mapping(serverId).let { mapping -> shared.detailOf(serverId) { c -> c.movies.firstOrNull { it.id == movieId }?.let(mapping::movieDetail) } }
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState.Loading)

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val eventChannel = Channel<DetailEvent>(Channel.BUFFERED)
    val events: Flow<DetailEvent> = eventChannel.receiveAsFlow()

    val copy = CopyRequests(copies, viewModelScope) { eventChannel.send(DetailEvent.Message(it)) }

    /** [fileId] picks a version; null plays the one being watched, else the best available. */
    fun play(fileId: Long?) {
        viewModelScope.launch {
            val plan = if (serverId != null) shared.planForMovie(serverId, movieId, fileId) else planner.forMovie(movieId, fileId)
            eventChannel.send(
                if (plan != null) DetailEvent.Play(plan)
                else DetailEvent.Message(
                    if (serverId != null) "Ce film n'est pas disponible sur le PC : son disque est peut-être débranché."
                    else "Aucune copie de ce film n'est disponible : le support est peut-être débranché."
                )
            )
        }
    }

    fun toggleWatched() {
        val current = (state.value as? DetailState.Ready)?.value ?: return
        val seen = current.watch != WatchStatus.WATCHED
        if (serverId != null) return shared.setWatched(serverId, shared.catalog(serverId)?.movies?.firstOrNull { it.id == movieId }?.files.orEmpty(), seen)
        viewModelScope.launch { watched.setMovieWatched(movieId, seen) }
    }

    fun toggleFavorite() {
        if (serverId != null) return
        viewModelScope.launch { favorites.toggleMovie(movieId) }
    }

    fun openListPicker() {
        if (serverId != null) return
        val movie = (state.value as? DetailState.Ready)?.value?.movie ?: return
        listPicker.open(MediaKind.MOVIE, movieId, movie.title)
    }

    /** Copies the version [fileId] of a PC's film into the phone's films folder. */
    fun copyToPhone(fileId: Long) {
        val id = serverId ?: return
        val movie = (state.value as? DetailState.Ready)?.value?.movie ?: return
        copy.copy(id, MediaKind.MOVIE, listOf(fileId), "« ${movie.year?.let { "${movie.title} ($it)" } ?: movie.title} »")
    }

    /** A PC's film: reads the PC's catalog again. This phone's: asks TMDB again. */
    fun refresh() {
        if (serverId != null) return shared.refresh(serverId)
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
    lists: UserListsRepository,
    private val shared: SharedLibraryRepository,
    copies: PhoneCopies
) : ViewModel() {

    val listPicker = ListPickerModel(lists, viewModelScope)

    private val seriesId: Long = checkNotNull(savedState.get<Long>("seriesId")) { "seriesId is missing from the navigation arguments" }

    /** Set for a series of a PC's library (its ids are the PC's); null for one of this phone's. */
    private val serverId: String? = savedState.get<String>(SERVER_ARG)?.takeIf { it.isNotBlank() }

    /** The PC the series comes from, named on the page; null for this phone's series. */
    val sourceName: String? = serverId?.let { shared.server(it)?.name ?: "PC" }

    val state: StateFlow<DetailState<SeriesDetail>> = (
        if (serverId == null) detail.observeSeries(seriesId).map { if (it == null) DetailState.Missing else DetailState.Ready(it) }
        else shared.mapping(serverId).let { mapping -> shared.detailOf(serverId) { c -> c.series.firstOrNull { it.id == seriesId }?.let(mapping::seriesDetail) } }
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState.Loading)

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val eventChannel = Channel<DetailEvent>(Channel.BUFFERED)
    val events: Flow<DetailEvent> = eventChannel.receiveAsFlow()

    val copy = CopyRequests(copies, viewModelScope) { eventChannel.send(DetailEvent.Message(it)) }

    private val unavailable: String
        get() = if (serverId != null) "sur le PC : son disque est peut-être débranché." else ": le support est peut-être débranché."

    /** What the series' "Lire" button stands for: resume, next episode, first episode, rewatch. */
    fun playSeries() = launchPlan("Aucun épisode de cette série n'est disponible $unavailable") {
        if (serverId != null) shared.planForSeries(serverId, seriesId) else planner.forSeries(seriesId)
    }

    /** The episode and those after it; [fileId] picks one of its versions. */
    fun playEpisode(episode: EpisodeItem, fileId: Long? = null) =
        launchPlan("Cet épisode n'est pas disponible $unavailable") {
            if (serverId != null) shared.planForEpisode(serverId, seriesId, episode.episodeId, fileId)
            else planner.forEpisode(seriesId, episode.episodeId, fileId)
        }

    fun setEpisodeWatched(episode: EpisodeItem, isWatched: Boolean) {
        if (serverId != null) return remoteWatched(listOf(episode), isWatched)
        viewModelScope.launch { watched.setEpisodeWatched(episode.episodeId, isWatched) }
    }

    fun setSeasonWatched(season: SeasonDetail, isWatched: Boolean) {
        if (serverId != null) return remoteWatched(season.episodes, isWatched)
        viewModelScope.launch { watched.setSeasonWatched(seriesId, season.seasonNumber, isWatched) }
    }

    fun setSeriesWatched(isWatched: Boolean) {
        if (serverId != null) return remoteWatched((state.value as? DetailState.Ready)?.value?.seasons?.flatMap { it.episodes }.orEmpty(), isWatched)
        viewModelScope.launch { watched.setSeriesWatched(seriesId, isWatched) }
    }

    fun toggleFavorite() {
        if (serverId != null) return
        viewModelScope.launch { favorites.toggleSeries(seriesId) }
    }

    fun openListPicker() {
        if (serverId != null) return
        val series = (state.value as? DetailState.Ready)?.value?.series ?: return
        listPicker.open(MediaKind.SERIES, seriesId, series.title)
    }

    fun setLanguages(audio: String?, subtitles: String?) {
        if (serverId != null) return
        viewModelScope.launch { detail.setSeriesPreference(seriesId, audio, subtitles) }
    }

    /** Copies an episode of a PC's series into the phone's series folder; [fileId] picks a version, else the best one. */
    fun copyEpisode(episode: EpisodeItem, fileId: Long? = null) = copyEpisodes(listOf(episode), episode.label, fileId)

    fun copySeason(season: SeasonDetail) = copyEpisodes(season.episodes, "« ${season.title} »")

    fun copySeries() {
        val current = (state.value as? DetailState.Ready)?.value ?: return
        copyEpisodes(current.seasons.flatMap { it.episodes }, "« ${current.series.title} »")
    }

    private fun copyEpisodes(episodes: List<EpisodeItem>, what: String, fileId: Long? = null) {
        val id = serverId ?: return
        // Versions come available first, then sharpest: the first available one is what "Lire" plays
        val files = episodes.mapNotNull { e -> (e.versions.firstOrNull { it.mediaFileId == fileId } ?: e.versions.firstOrNull { it.isAvailable })?.mediaFileId }
        copy.copy(id, MediaKind.SERIES, files, what)
    }

    private fun remoteWatched(episodes: List<EpisodeItem>, isWatched: Boolean) {
        val id = serverId ?: return
        shared.setWatched(id, episodes.flatMap { e -> e.versions.mapNotNull { shared.file(id, it.mediaFileId) } }, isWatched)
    }

    /** A PC's series: reads the PC's catalog again. This phone's: asks TMDB again. */
    fun refresh() {
        if (serverId != null) return shared.refresh(serverId)
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
