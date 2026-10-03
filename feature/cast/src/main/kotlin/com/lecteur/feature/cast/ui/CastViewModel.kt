package com.lecteur.feature.cast.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lecteur.core.common.cast.compat.CastVerdict
import com.lecteur.feature.cast.chromecast.CastRoute
import com.lecteur.feature.cast.chromecast.ChromecastController
import com.lecteur.feature.cast.chromecast.ChromecastResult
import com.lecteur.feature.cast.chromecast.ChromecastRoutes
import com.lecteur.feature.cast.controller.ConnectionState
import com.lecteur.feature.cast.controller.RemoteSession
import com.lecteur.feature.cast.discovery.DiscoveredReceiver
import com.lecteur.feature.cast.discovery.NsdBrowser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CastUi(
    val receivers: List<DiscoveredReceiver> = emptyList(),
    val chromecasts: List<CastRoute> = emptyList(),
    val chromecastAvailable: Boolean = false,
    /** Null until the check ran. */
    val chromecastVerdict: CastVerdict? = null,
    val working: Boolean = false,
    val message: String? = null
)

sealed interface CastEvent {
    /** The file is playing on the TV: show the remote. */
    data object OpenRemote : CastEvent

    /** Cast started on a Chromecast: the sheet can close. */
    data object Done : CastEvent
}

/** Everything the "Diffuser" sheet does for one library file. */
@HiltViewModel
class CastViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val session: RemoteSession,
    private val chromecast: ChromecastController
) : ViewModel() {

    private val browser = NsdBrowser(context)
    private val routes = ChromecastRoutes(context)
    private var mediaFileId: Long = 0

    private val local = MutableStateFlow(CastUi(chromecastAvailable = chromecast.isAvailable))

    val ui: StateFlow<CastUi> = combine(local, browser.receivers, routes.routes) { local, receivers, routes ->
        local.copy(receivers = receivers, chromecasts = routes)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CastUi())

    private val _events = Channel<CastEvent>(Channel.BUFFERED)
    val events: Flow<CastEvent> = _events.receiveAsFlow()

    init {
        browser.start()
        if (chromecast.isAvailable) routes.start()
    }

    /** Sets the file the sheet is about and checks it against Chromecast limits. */
    fun load(mediaFileId: Long) {
        if (this.mediaFileId == mediaFileId) return
        this.mediaFileId = mediaFileId
        viewModelScope.launch {
            val verdict = chromecast.check(mediaFileId)
            local.update { it.copy(chromecastVerdict = verdict) }
        }
    }

    /** Connects to a TV with the code typed by the user, then sends the file. */
    fun castToReceiver(receiver: DiscoveredReceiver, code: String) {
        viewModelScope.launch {
            local.update { it.copy(working = true, message = null) }
            val current = session.ui.value.connection
            val alreadyThere = current is ConnectionState.Connected && current.receiverName == receiver.deviceName
            val connected = alreadyThere || session.connect(receiver, code)
            if (connected) {
                session.cast(mediaFileId)
                local.update { it.copy(working = false) }
                _events.send(CastEvent.OpenRemote)
            } else {
                val failure = (session.ui.value.connection as? ConnectionState.Failed)?.message
                local.update { it.copy(working = false, message = failure ?: "Connexion impossible.") }
            }
        }
    }

    fun selectChromecast(route: CastRoute) = routes.select(route.id)

    fun castToChromecast() {
        viewModelScope.launch {
            local.update { it.copy(working = true, message = null) }
            when (val result = chromecast.cast(mediaFileId)) {
                is ChromecastResult.Started -> {
                    local.update { it.copy(working = false) }
                    _events.send(CastEvent.Done)
                }
                is ChromecastResult.Refused -> local.update { it.copy(working = false, message = result.message) }
                is ChromecastResult.Failed -> local.update { it.copy(working = false, message = result.message) }
            }
        }
    }

    override fun onCleared() {
        browser.stop()
        routes.stop()
    }
}
