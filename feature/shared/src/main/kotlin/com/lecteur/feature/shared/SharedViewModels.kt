package com.lecteur.feature.shared

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lecteur.core.common.library.Catalog
import com.lecteur.core.common.library.EpisodeDto
import com.lecteur.core.common.library.LibraryAdvert
import com.lecteur.core.common.library.LibraryException
import com.lecteur.core.common.library.MovieDto
import com.lecteur.core.common.library.PairedServer
import com.lecteur.core.common.library.SeriesDto
import com.lecteur.core.data.shared.CatalogState
import com.lecteur.core.data.shared.SharedLibraryRepository
import com.lecteur.core.model.PlayPlan
import com.lecteur.core.model.QueueEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A paired PC, and whether it answers on the network right now. */
data class ServerRow(val server: PairedServer, val online: Boolean)

/** The pairing dialog: which PC, and how the code entry went. */
data class PairingUi(val name: String, val baseUrl: String, val busy: Boolean = false, val error: String? = null)

data class ServersUi(
    val paired: List<ServerRow> = emptyList(),
    /** PCs on the network this device is not paired with yet. */
    val nearby: List<NearbyServer> = emptyList(),
    val pairing: PairingUi? = null,
    /** An address typed by hand that does not answer, or another problem outside the pairing dialog. */
    val message: String? = null,
    /** Set once a pairing succeeded: the screen opens that library. */
    val openedServerId: String? = null
)

/** The list of PCs: paired ones, the ones found on the network, pairing with the code shown on the PC. */
@HiltViewModel
class SharedServersViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SharedLibraryRepository
) : ViewModel() {
    private val browser = LibraryBrowser(context)
    private val local = MutableStateFlow(ServersUi())

    val ui: StateFlow<ServersUi> = combine(repository.servers, browser.servers, local) { paired, nearby, state ->
        // A paired PC found at a new address (DHCP): follow it
        nearby.forEach { n -> paired.firstOrNull { it.serverId == n.serverId }?.let { repository.updateAddress(it.serverId, n.baseUrl, n.name) } }
        state.copy(
            paired = paired.map { p -> ServerRow(p, nearby.any { it.serverId == p.serverId }) },
            nearby = nearby.filter { n -> paired.none { it.serverId == n.serverId } }.sortedBy { it.name }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServersUi())

    init {
        browser.start()
    }

    override fun onCleared() {
        browser.stop()
    }

    /** Looks for PCs again (a PC switched on after the screen was opened). */
    fun rescan() {
        browser.stop()
        browser.start()
    }

    fun startPairing(server: NearbyServer) = local.update { it.copy(pairing = PairingUi(server.name, server.baseUrl), message = null) }

    /** An address typed by hand ("192.168.1.20", with or without port): checked before asking for the code. */
    fun startPairing(address: String) {
        val base = normalise(address) ?: return local.update { it.copy(message = "Adresse invalide. Exemple : 192.168.1.20") }
        local.update { it.copy(message = null, pairing = PairingUi("…", base, busy = true)) }
        viewModelScope.launch {
            try {
                val info = repository.probe(base)
                local.update { it.copy(pairing = PairingUi(info.name, base)) }
            } catch (e: LibraryException) {
                local.update { it.copy(pairing = null, message = e.message) }
            }
        }
    }

    fun submitCode(code: String) {
        val pairing = local.value.pairing ?: return
        if (code.count(Char::isDigit) != 6) return local.update { it.copy(pairing = pairing.copy(error = "Le code comporte 6 chiffres.")) }
        local.update { it.copy(pairing = pairing.copy(busy = true, error = null)) }
        viewModelScope.launch {
            try {
                val server = repository.pair(pairing.baseUrl, code, deviceName(), platform())
                repository.refresh(server.serverId)
                local.update { it.copy(pairing = null, openedServerId = server.serverId) }
            } catch (e: LibraryException) {
                val text = when (e.kind) {
                    LibraryException.Kind.BAD_CODE -> "Code incorrect. Vérifiez le code affiché sur le PC."
                    LibraryException.Kind.PAIRING_CLOSED -> "Sur le PC, ouvrez « Partage » puis « Associer un appareil » pour afficher un code."
                    else -> e.message
                }
                local.update { it.copy(pairing = pairing.copy(busy = false, error = text)) }
            }
        }
    }

    fun cancelPairing() = local.update { it.copy(pairing = null) }

    fun consumeOpened() = local.update { it.copy(openedServerId = null) }

    fun consumeMessage() = local.update { it.copy(message = null) }

    fun forget(serverId: String) = repository.forget(serverId)

    private fun normalise(address: String): String? {
        val text = address.trim().removePrefix("http://").trimEnd('/')
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        val hasPort = text.substringAfterLast(':', "").let { it.isNotEmpty() && it.all(Char::isDigit) } && !text.startsWith("[")
        return "http://" + if (hasPort) text else "$text:${LibraryAdvert.DEFAULT_PORT}"
    }

    private fun deviceName(): String =
        Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
            ?: Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"

    private fun platform(): String {
        val mode = context.getSystemService(UiModeManager::class.java)?.currentModeType
        return if (mode == Configuration.UI_MODE_TYPE_TELEVISION) "tv" else "phone"
    }
}

data class LibraryUi(
    val serverId: String? = null,
    val serverName: String = "",
    val state: CatalogState = CatalogState.Loading,
    val continueWatching: List<ShelfCard> = emptyList(),
    val nextUp: List<ShelfCard> = emptyList(),
    val recent: List<ShelfCard> = emptyList()
) {
    val catalog: Catalog? get() = (state as? CatalogState.Loaded)?.catalog ?: (state as? CatalogState.Failed)?.previous
}

/**
 * One PC's library: shelves, movies, series, detail pages and the play plans. Phone screens get the PC from the route;
 * the TV picks it with [select].
 */
@HiltViewModel
class SharedLibraryViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: SharedLibraryRepository
) : ViewModel() {
    private val serverId = MutableStateFlow(savedState.get<String>(ARG_SERVER))

    val ui: StateFlow<LibraryUi> = combine(serverId, repository.catalogs, repository.servers) { id, catalogs, servers ->
        if (id == null) return@combine LibraryUi()
        val state = catalogs[id] ?: CatalogState.Loading
        val catalog = (state as? CatalogState.Loaded)?.catalog ?: (state as? CatalogState.Failed)?.previous
        LibraryUi(
            serverId = id,
            serverName = servers.firstOrNull { it.serverId == id }?.name ?: catalog?.serverName.orEmpty(),
            state = state,
            continueWatching = catalog?.let(SharedShelves::continueWatching).orEmpty(),
            nextUp = catalog?.let(SharedShelves::nextUp).orEmpty(),
            recent = catalog?.let(SharedShelves::recentlyAdded).orEmpty()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUi())

    init {
        serverId.value?.let(::ensureLoaded)
    }

    fun select(id: String) {
        serverId.value = id
        ensureLoaded(id)
    }

    /** Reloads, so progress made on the PC or another device shows. */
    fun refresh() {
        serverId.value?.let(repository::refresh)
    }

    /** The PC removed this device: forget it here too, so it can be paired again. */
    fun forget() {
        serverId.value?.let(repository::forget)
    }

    private fun ensureLoaded(id: String) {
        if (repository.catalogs.value[id] !is CatalogState.Loaded) repository.refresh(id)
    }

    fun art(key: String?): String? = serverId.value?.let { repository.artUrl(it, key) }

    fun movie(id: Long): MovieDto? = ui.value.catalog?.movies?.firstOrNull { it.id == id }
        ?: serverId.value?.let { repository.catalog(it) }?.movies?.firstOrNull { it.id == id }

    fun series(id: Long): SeriesDto? = ui.value.catalog?.series?.firstOrNull { it.id == id }
        ?: serverId.value?.let { repository.catalog(it) }?.series?.firstOrNull { it.id == id }

    /** The movie's best file, streamed from the PC; the player offers to resume where the PC says it stopped. */
    fun planForMovie(movie: MovieDto): PlayPlan? {
        val id = serverId.value ?: return null
        val file = SharedShelves.movieFile(movie)?.takeIf { it.available } ?: return null
        val url = repository.streamUrl(id, file) ?: return null
        return PlayPlan(listOf(QueueEntry(url, movie.year?.let { "${movie.title} ($it)" } ?: movie.title)))
    }

    /** [episode] and the following ones, so the player moves on to the next episode by itself. */
    fun planForEpisode(series: SeriesDto, episode: EpisodeDto): PlayPlan? {
        val id = serverId.value ?: return null
        val entries = SharedShelves.queueFrom(series, episode).mapNotNull { (e, file) ->
            repository.streamUrl(id, file)?.let { QueueEntry(it, "${series.title} · ${SharedShelves.episodeLabel(e)}") }
        }
        return if (entries.isEmpty()) null else PlayPlan(entries, label = series.title)
    }

    fun planForSeries(series: SeriesDto): PlayPlan? = SharedShelves.seriesTarget(series)?.let { planForEpisode(series, it) }

    /** The plan behind a home card. */
    fun planFor(card: ShelfCard): PlayPlan? {
        card.movieId?.let { id -> return movie(id)?.let(::planForMovie) }
        val series = card.seriesId?.let(::series) ?: return null
        val episode = series.episodes.firstOrNull { it.id == card.episodeId } ?: return planForSeries(series)
        return planForEpisode(series, episode)
    }

    companion object {
        const val ARG_SERVER = "serverId"
    }
}
