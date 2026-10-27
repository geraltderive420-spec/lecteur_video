package com.lecteur.core.data.shared

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/** Navigation argument of the pages of a PC's title (detail, browse): the PC's server id, absent for this phone's titles. */
const val SERVER_ARG = "server"

/**
 * Which library the phone's screens show: this phone's own (null, the default) or the one a paired PC shares (its server
 * id). Home, library, search and detail pages read the same screens for both; only where the titles come from changes.
 * Forgetting the PC on display brings the phone's library back.
 */
@Singleton
class LibrarySourceRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val shared: SharedLibraryRepository
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val chosen = MutableStateFlow(prefs.getString(KEY_SERVER, null))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The PC whose library is on display, null for this phone's. */
    val active: StateFlow<String?> = combine(chosen, shared.servers) { id, servers -> id?.takeIf { servers.any { it.serverId == id } } }
        .stateIn(scope, SharingStarted.Eagerly, chosen.value?.takeIf { id -> shared.servers.value.any { it.serverId == id } })

    /** Shows a PC's library ([serverId]) or this phone's (null); a PC's catalog is loaded if it is not yet. */
    fun select(serverId: String?) {
        chosen.value = serverId
        prefs.edit().putString(KEY_SERVER, serverId).apply()
        if (serverId != null && shared.catalogs.value[serverId] !is CatalogState.Loaded) shared.refresh(serverId)
    }

    private companion object {
        const val PREFS = "library_source"
        const val KEY_SERVER = "server"
    }
}
