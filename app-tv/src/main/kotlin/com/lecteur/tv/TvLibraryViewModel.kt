package com.lecteur.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.lecteur.core.common.library.Catalog
import com.lecteur.core.common.library.FileDto
import com.lecteur.core.common.library.PairedServer
import com.lecteur.core.common.library.best
import com.lecteur.core.common.text.TextFold
import com.lecteur.core.data.details.DetailRepository
import com.lecteur.core.data.library.ContinueItem
import com.lecteur.core.data.library.FavoritesRepository
import com.lecteur.core.data.library.FolderRepository
import com.lecteur.core.data.library.HomeRepository
import com.lecteur.core.data.library.LibraryRepository
import com.lecteur.core.data.library.NextUpItem
import com.lecteur.core.data.library.WatchedRepository
import com.lecteur.core.data.playback.PlayPlanner
import com.lecteur.core.data.playback.PlaybackRequestFactory
import com.lecteur.core.data.playback.PreparedPlayback
import com.lecteur.core.data.search.SearchRepository
import com.lecteur.core.data.shared.CatalogState
import com.lecteur.core.data.shared.SharedLibraryRepository
import com.lecteur.core.data.shared.SharedMapping
import com.lecteur.core.model.EpisodeItem
import com.lecteur.core.model.LibraryFilters
import com.lecteur.core.model.LibraryItem
import com.lecteur.core.model.LibraryQuery
import com.lecteur.core.model.LibrarySection
import com.lecteur.core.model.LibrarySort
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.MovieDetail
import com.lecteur.core.model.PlayPlan
import com.lecteur.core.model.SeasonDetail
import com.lecteur.core.model.SeriesDetail
import com.lecteur.core.model.WatchStatus
import com.lecteur.feature.cast.receiver.ReceiverHost
import com.lecteur.core.data.shared.SharedShelves
import com.lecteur.core.data.shared.ShelfCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A title of the merged library: of this TV ([serverId] null) or of a PC, whose ids are its own. */
data class TvRef(val serverId: String?, val kind: MediaKind, val id: Long) {
    val key: String get() = "${serverId ?: "tv"}-${kind.name}-$id"
}

/** A poster: the title and, for a PC's, the PC's name (the small "source" mention). */
data class TvTile(val ref: TvRef, val item: LibraryItem, val source: String?, val genres: List<String> = emptyList())

/** What a landscape card plays at once. */
sealed interface TvPlay {
    data class Local(val mediaFileId: Long, val seriesId: Long?, val episodeId: Long?) : TvPlay
    data class Shared(val serverId: String, val card: ShelfCard) : TvPlay
}

/** A landscape card of "Reprendre" or "Épisodes suivants". [image] is a TMDB path or a PC's full URL. */
data class TvWide(
    val key: String,
    val ref: TvRef?,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val progress: Float,
    val source: String?,
    val at: Long,
    val play: TvPlay
)

data class TvHomeUi(
    val continueWatching: List<TvWide> = emptyList(),
    val nextUp: List<TvWide> = emptyList(),
    val recent: List<TvTile> = emptyList(),
    val unwatched: List<TvTile> = emptyList(),
    val loaded: Boolean = false,
    val hasFolders: Boolean = false,
    val hasServers: Boolean = false
)

/** A detail page, whichever library the title comes from. */
sealed interface TvDetail {
    val ref: TvRef
    val source: String?

    data class Film(override val ref: TvRef, override val source: String?, val detail: MovieDetail) : TvDetail
    data class Show(override val ref: TvRef, override val source: String?, val detail: SeriesDetail) : TvDetail
}

/** Pages opened above the tabs; kept here so that they are still there when the player closes. */
sealed interface TvPage {
    data class Detail(val ref: TvRef) : TvPage

    /** Titles of someone of a cast. [personId] is this TV's id when known; the PCs' titles are matched by name. */
    data class Person(val name: String, val personId: Long?) : TvPage
}

data class TvPersonHit(val name: String, val personId: Long?, val profilePath: String?)

data class TvSearchUi(val query: String = "", val titles: List<TvTile> = emptyList(), val people: List<TvPersonHit> = emptyList())

/**
 * The TV's library mixed with those of the paired PCs, Plex style: one home, one film grid, one series grid, one search,
 * every title marked with where it comes from. Playback goes through the shared receiver host whatever the source; a
 * PC's title streams from the PC and keeps the PC's watch state.
 */
@OptIn(UnstableApi::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class TvLibraryViewModel @Inject constructor(
    home: HomeRepository,
    private val library: LibraryRepository,
    folders: FolderRepository,
    private val details: DetailRepository,
    private val shared: SharedLibraryRepository,
    private val search: SearchRepository,
    private val watched: WatchedRepository,
    private val favorites: FavoritesRepository,
    private val planner: PlayPlanner,
    private val requestFactory: PlaybackRequestFactory,
    private val host: ReceiverHost
) : ViewModel() {

    private val _tab = MutableStateFlow(0)
    val tab: StateFlow<Int> = _tab.asStateFlow()

    private val _pages = MutableStateFlow<List<TvPage>>(emptyList())
    val pages: StateFlow<List<TvPage>> = _pages.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** The PCs and what they share, with the mapping that turns their artwork keys into URLs. */
    private data class Remote(val server: PairedServer, val catalog: Catalog, val mapping: SharedMapping)

    private val remotes: Flow<List<Remote>> = combine(shared.servers, shared.catalogs) { servers, catalogs ->
        servers.mapNotNull { server ->
            val catalog = when (val state = catalogs[server.serverId]) {
                is CatalogState.Loaded -> state.catalog
                is CatalogState.Failed -> state.previous
                else -> null
            } ?: return@mapNotNull null
            Remote(server, catalog, SharedMapping { key -> shared.artUrl(server.serverId, key) })
        }
    }

    private val localMovies = library.observe(LibraryQuery(LibrarySection.MOVIES, LibrarySort()))
    private val localSeries = library.observe(LibraryQuery(LibrarySection.SERIES, LibrarySort()))

    val movies: StateFlow<List<TvTile>> = combine(localMovies, remotes) { local, pcs ->
        local.map { TvTile(TvRef(null, MediaKind.MOVIE, it.id), it, null) } +
            pcs.flatMap { r -> r.catalog.movies.map { m -> TvTile(TvRef(r.server.serverId, MediaKind.MOVIE, m.id), r.mapping.movieItem(m), r.server.name, m.genres) } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val series: StateFlow<List<TvTile>> = combine(localSeries, remotes) { local, pcs ->
        local.map { TvTile(TvRef(null, MediaKind.SERIES, it.id), it, null) } +
            pcs.flatMap { r -> r.catalog.series.map { s -> TvTile(TvRef(r.server.serverId, MediaKind.SERIES, s.id), r.mapping.seriesItem(s), r.server.name, s.genres) } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Names of the sources, for the grid's source filter: null is this TV. */
    val sources: StateFlow<List<Pair<String?, String>>> = shared.servers.map { servers ->
        listOf<Pair<String?, String>>(null to "Cette TV") + servers.map { it.serverId to it.name }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), listOf(null to "Cette TV"))

    val homeUi: StateFlow<TvHomeUi> = combine(
        combine(home.continueWatching, home.nextUp, ::Pair),
        movies, series, folders.folders, remotes
    ) { (continueItems, nextUp), movies, series, folders, pcs ->
        val continueRow = continueItems.map(::wide) + pcs.flatMap { r -> SharedShelves.continueWatching(r.catalog).map { wide(r, it) } }
        val nextRow = nextUp.map(::wide) + pcs.flatMap { r -> SharedShelves.nextUp(r.catalog).map { wide(r, it) } }
        TvHomeUi(
            continueWatching = continueRow.sortedByDescending { it.at }.take(ROW_SIZE),
            nextUp = nextRow.sortedByDescending { it.at }.take(ROW_SIZE),
            recent = (movies + series).sortedByDescending { it.item.addedAt }.take(ROW_SIZE),
            unwatched = movies.filter { it.item.watch == WatchStatus.UNWATCHED && it.item.isAvailable }.sortedByDescending { it.item.addedAt }.take(ROW_SIZE),
            loaded = true,
            hasFolders = folders.isNotEmpty(),
            hasServers = pcs.isNotEmpty()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TvHomeUi())

    init {
        shared.refreshAll()
    }

    // region navigation

    fun selectTab(index: Int) {
        _tab.value = index
    }

    fun open(page: TvPage) = _pages.update { it + page }

    fun back() = _pages.update { it.dropLast(1) }

    fun consumeNotice() {
        _notice.value = null
    }

    /** Reads the PCs' catalogs again (progress made on the PC or on the phone since). */
    fun refreshServers() = shared.refreshAll()

    // endregion

    // region detail pages

    fun detail(ref: TvRef): Flow<TvDetail?> {
        val serverId = ref.serverId
        if (serverId == null) {
            return if (ref.kind == MediaKind.MOVIE) {
                details.observeMovie(ref.id).map { d -> d?.let { TvDetail.Film(ref, null, it) } }
            } else {
                details.observeSeries(ref.id).map { d -> d?.let { TvDetail.Show(ref, null, it) } }
            }
        }
        return remotes.map { pcs ->
            val r = pcs.firstOrNull { it.server.serverId == serverId } ?: return@map null
            if (ref.kind == MediaKind.MOVIE) {
                r.catalog.movies.firstOrNull { it.id == ref.id }?.let { TvDetail.Film(ref, r.server.name, r.mapping.movieDetail(it)) }
            } else {
                r.catalog.series.firstOrNull { it.id == ref.id }?.let { TvDetail.Show(ref, r.server.name, r.mapping.seriesDetail(it)) }
            }
        }
    }

    fun person(page: TvPage.Person): Flow<List<TvTile>> {
        val localId: Flow<Long?> = if (page.personId != null) flowOf(page.personId) else search.search(page.name).map { results ->
            results.people.firstOrNull { TextFold.fold(it.name) == TextFold.fold(page.name) }?.personId
        }
        val local = localId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else combine(
                library.observe(LibraryQuery(LibrarySection.MOVIES, LibrarySort(), LibraryFilters(personId = id))),
                library.observe(LibraryQuery(LibrarySection.SERIES, LibrarySort(), LibraryFilters(personId = id)))
            ) { m, s -> m.map { TvTile(TvRef(null, MediaKind.MOVIE, it.id), it, null) } + s.map { TvTile(TvRef(null, MediaKind.SERIES, it.id), it, null) } }
        }
        val name = TextFold.fold(page.name)
        val remote = remotes.map { pcs ->
            pcs.flatMap { r ->
                r.catalog.movies.filter { m -> m.cast.any { TextFold.fold(it.name) == name } }
                    .map { TvTile(TvRef(r.server.serverId, MediaKind.MOVIE, it.id), r.mapping.movieItem(it), r.server.name) } +
                    r.catalog.series.filter { s -> s.cast.any { TextFold.fold(it.name) == name } }
                        .map { TvTile(TvRef(r.server.serverId, MediaKind.SERIES, it.id), r.mapping.seriesItem(it), r.server.name) }
            }
        }
        return combine(local, remote) { l, r -> (l + r).sortedByDescending { it.item.year ?: 0 } }
    }

    private val searchQuery = MutableStateFlow("")

    val searchUi: StateFlow<TvSearchUi> = searchQuery.flatMapLatest { text ->
        val tokens = TextFold.tokens(text)
        if (tokens.isEmpty()) return@flatMapLatest flowOf(TvSearchUi(text))
        combine(movies, series, search.search(text), remotes) { m, s, local, pcs ->
            val titles = (m + s).mapNotNull { tile -> TextFold.score(TextFold.fold(tile.item.title), tokens)?.let { it to tile } }
                .sortedByDescending { it.first }.map { it.second }
            val people = local.people.map { TvPersonHit(it.name, it.personId, it.profilePath) } +
                pcs.flatMap { r ->
                    (r.catalog.movies.flatMap { it.cast } + r.catalog.series.flatMap { it.cast })
                        .filter { TextFold.score(TextFold.fold(it.name), tokens) != null }
                        .map { TvPersonHit(it.name, null, shared.artUrl(r.server.serverId, it.profile)) }
                }
            TvSearchUi(text, titles.take(60), people.distinctBy { TextFold.fold(it.name) }.take(30))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TvSearchUi())

    fun search(text: String) {
        searchQuery.value = text
    }

    // endregion

    // region actions

    fun setWatched(detail: TvDetail, isWatched: Boolean) = viewModelScope.launch {
        val serverId = detail.ref.serverId
        if (serverId == null) {
            if (detail is TvDetail.Film) watched.setMovieWatched(detail.ref.id, isWatched) else watched.setSeriesWatched(detail.ref.id, isWatched)
        } else {
            shared.setWatched(serverId, filesOf(serverId, detail.ref), isWatched)
        }
    }

    fun setEpisodeWatched(detail: TvDetail.Show, episode: EpisodeItem, isWatched: Boolean) = viewModelScope.launch {
        val serverId = detail.ref.serverId
        if (serverId == null) watched.setEpisodeWatched(episode.episodeId, isWatched)
        else shared.setWatched(serverId, episode.versions.mapNotNull { shared.file(serverId, it.mediaFileId) }, isWatched)
    }

    fun setSeasonWatched(detail: TvDetail.Show, season: SeasonDetail, isWatched: Boolean) = viewModelScope.launch {
        val serverId = detail.ref.serverId
        if (serverId == null) watched.setSeasonWatched(detail.ref.id, season.seasonNumber, isWatched)
        else shared.setWatched(serverId, season.episodes.flatMap { e -> e.versions.mapNotNull { shared.file(serverId, it.mediaFileId) } }, isWatched)
    }

    /** Favourites belong to this TV's library. */
    fun toggleFavorite(detail: TvDetail) = viewModelScope.launch {
        if (detail.ref.serverId != null) return@launch
        if (detail is TvDetail.Film) favorites.toggleMovie(detail.ref.id) else favorites.toggleSeries(detail.ref.id)
    }

    fun isFavorite(ref: TvRef): Flow<Boolean> = when {
        ref.serverId != null -> flowOf(false)
        ref.kind == MediaKind.MOVIE -> favorites.observeMovie(ref.id)
        else -> favorites.observeSeries(ref.id)
    }

    private fun filesOf(serverId: String, ref: TvRef): List<FileDto> {
        val catalog = shared.catalog(serverId) ?: return emptyList()
        return if (ref.kind == MediaKind.MOVIE) catalog.movies.firstOrNull { it.id == ref.id }?.files.orEmpty()
        else catalog.series.firstOrNull { it.id == ref.id }?.episodes?.flatMap { it.files }.orEmpty()
    }

    // endregion

    // region playback

    /** A film: [fileId] picks a version (null: the one being watched, else the best). */
    fun playMovie(ref: TvRef, fileId: Long? = null, fromStart: Boolean = false) = viewModelScope.launch {
        val serverId = ref.serverId
        if (serverId == null) return@launch openLocal(planner.forMovie(ref.id, fileId), fromStart)
        val files = filesOf(serverId, ref)
        val file = files.firstOrNull { it.id == fileId } ?: files.filter { it.available }.let { available ->
            available.filter { it.watch?.let { w -> !w.completed && w.positionMs > 0 } == true }.maxByOrNull { it.watch!!.lastWatchedAt } ?: available.best()
        }
        openShared(serverId, file, fromStart)
    }

    fun playEpisode(ref: TvRef, episode: EpisodeItem, fileId: Long? = null, fromStart: Boolean = false) = viewModelScope.launch {
        val serverId = ref.serverId
        if (serverId == null) return@launch openLocal(planner.forEpisode(ref.id, episode.episodeId, fileId), fromStart)
        val versions = episode.versions.filter { it.isAvailable }
        val chosen = versions.firstOrNull { it.mediaFileId == fileId } ?: versions.firstOrNull()
        openShared(serverId, chosen?.let { shared.file(serverId, it.mediaFileId) }, fromStart)
    }

    fun playSeries(detail: TvDetail.Show) {
        val target = detail.detail.playTarget ?: return say("Aucun épisode de cette série n'est disponible.")
        playEpisode(detail.ref, target.episode)
    }

    /** A home card: starts right away where it stopped. */
    fun play(card: TvWide) = viewModelScope.launch {
        when (val p = card.play) {
            is TvPlay.Local -> openLocal(
                if (p.seriesId != null && p.episodeId != null) planner.forEpisode(p.seriesId, p.episodeId, p.mediaFileId) else planner.forFile(p.mediaFileId),
                fromStart = false
            )
            is TvPlay.Shared -> {
                val catalog = shared.catalog(p.serverId) ?: return@launch say("Ce PC ne répond pas.")
                val file = p.card.movieId?.let { id -> catalog.movies.firstOrNull { it.id == id }?.let(SharedShelves::movieFile) }
                    ?: p.card.episodeId?.let { id -> catalog.series.firstNotNullOfOrNull { s -> s.episodes.firstOrNull { it.id == id } }?.files?.filter { it.available }?.best() }
                openShared(p.serverId, file, fromStart = false)
            }
        }
    }

    private suspend fun openLocal(plan: PlayPlan?, fromStart: Boolean) {
        val entry = plan?.entries?.getOrNull(plan.startIndex) ?: return say("Ce fichier n'est pas disponible : le support est peut-être débranché.")
        val prepared = runCatching { entry.mediaFileId?.let { requestFactory.forMediaFile(it) } ?: requestFactory.forUri(entry.uri) }.getOrNull()
            ?: return say("Impossible d'ouvrir ce fichier.")
        start(prepared, fromStart)
    }

    private suspend fun openShared(serverId: String, file: FileDto?, fromStart: Boolean) {
        if (file == null || !file.available) return say("Ce fichier n'est pas disponible sur le PC (disque débranché ?).")
        val url = shared.streamUrl(serverId, file) ?: return say("Ce PC n'est plus associé à cette TV.")
        val prepared = runCatching { requestFactory.forUri(url) }.getOrNull() ?: return say("Le PC ne répond pas.")
        start(prepared, fromStart)
    }

    /** A TV remote has no comfortable "resume or restart?" dialog: it resumes, and "Depuis le début" is its own button. */
    private fun start(prepared: PreparedPlayback, fromStart: Boolean) {
        val position = if (fromStart) 0L else prepared.resumePromptMs ?: 0L
        host.open(prepared.request.copy(startPositionMs = position, playWhenReady = true), trackLocally = true)
    }

    private fun say(text: String) {
        _notice.value = text
    }

    // endregion

    private fun wide(item: ContinueItem) = TvWide(
        key = "c-${item.mediaFileId}",
        ref = item.titleId?.let { id -> item.kind?.let { TvRef(null, it, id) } },
        title = item.title, subtitle = item.subtitle, image = item.imagePath, progress = item.progress, source = null,
        at = item.lastWatchedAt,
        play = TvPlay.Local(item.mediaFileId, if (item.kind == MediaKind.SERIES) item.titleId else null, item.episodeId)
    )

    private fun wide(item: NextUpItem) = TvWide(
        key = "n-${item.episodeId}", ref = TvRef(null, MediaKind.SERIES, item.seriesId), title = item.seriesTitle, subtitle = item.label,
        image = item.imagePath, progress = 0f, source = null, at = 0, play = TvPlay.Local(item.mediaFileId, item.seriesId, item.episodeId)
    )

    private fun wide(r: Remote, card: ShelfCard) = TvWide(
        key = "${r.server.serverId}-${card.key}",
        ref = card.movieId?.let { TvRef(r.server.serverId, MediaKind.MOVIE, it) } ?: card.seriesId?.let { TvRef(r.server.serverId, MediaKind.SERIES, it) },
        title = card.title, subtitle = card.subtitle, image = shared.artUrl(r.server.serverId, card.image), progress = card.progress,
        source = r.server.name, at = card.at, play = TvPlay.Shared(r.server.serverId, card)
    )

    private companion object {
        const val ROW_SIZE = 24
    }
}
