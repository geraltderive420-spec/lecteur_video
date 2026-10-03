package com.lecteur.feature.cast.chromecast

import android.content.Context
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CastRoute(val id: String, val name: String, val isSelected: Boolean)

/**
 * Chromecast devices on the network, listed with the MediaRouter API instead of the stock route button: that button
 * needs an AppCompat theme, which this app's activities do not use. Selecting a route makes the Cast SDK open a session.
 */
class ChromecastRoutes(context: Context) {
    private val router = MediaRouter.getInstance(context)
    private val selector = MediaRouteSelector.Builder()
        .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
        .build()

    private val _routes = MutableStateFlow<List<CastRoute>>(emptyList())
    val routes: StateFlow<List<CastRoute>> = _routes.asStateFlow()

    private val callback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
        override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refresh()
    }

    fun start() {
        router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        refresh()
    }

    fun stop() {
        router.removeCallback(callback)
        _routes.value = emptyList()
    }

    fun select(routeId: String) {
        router.routes.firstOrNull { it.id == routeId }?.select()
    }

    /** Back to the phone speaker: ends the Cast session. */
    fun disconnect() {
        router.unselect(MediaRouter.UNSELECT_REASON_STOPPED)
    }

    private fun refresh() {
        _routes.value = router.routes
            .filter { it.matchesSelector(selector) }
            .map { CastRoute(it.id, it.name, it.isSelected) }
    }
}
