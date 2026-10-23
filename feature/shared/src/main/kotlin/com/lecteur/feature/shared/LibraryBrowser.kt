package com.lecteur.feature.shared

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.lecteur.core.common.library.LibraryAdvert
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet6Address
import java.util.ArrayDeque

/** A PC sharing its library, found on the network. */
data class NearbyServer(val serviceName: String, val serverId: String, val name: String, val baseUrl: String)

/**
 * Lists the PCs that share their library (`_lecteurlib._tcp`), like the cast picker lists TVs. NsdManager resolves one
 * service at a time, so found services queue up; a PC that closes the app disappears from the list.
 */
class LibraryBrowser(context: Context) {
    private val manager = context.getSystemService(NsdManager::class.java)

    private val _servers = MutableStateFlow<List<NearbyServer>>(emptyList())
    val servers: StateFlow<List<NearbyServer>> = _servers.asStateFlow()

    private var discovery: NsdManager.DiscoveryListener? = null
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    @Synchronized
    fun start() {
        if (discovery != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery failed: $errorCode")
                synchronized(this@LibraryBrowser) { discovery = null }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "stop failed: $errorCode")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceName.startsWith(LibraryAdvert.SERVICE_PREFIX)) return
                enqueue(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                _servers.update { list -> list.filterNot { it.serviceName == service.serviceName } }
            }
        }
        discovery = listener
        runCatching { manager.discoverServices(LibraryAdvert.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { discovery = null }
    }

    @Synchronized
    fun stop() {
        discovery?.let { runCatching { manager.stopServiceDiscovery(it) } }
        discovery = null
        pending.clear()
        resolving = false
        _servers.value = emptyList()
    }

    @Synchronized
    private fun enqueue(service: NsdServiceInfo) {
        pending.add(service)
        resolveNext()
    }

    @Synchronized
    private fun resolveNext() {
        if (resolving) return
        val next = pending.poll() ?: return
        resolving = true
        manager.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "resolve failed for ${info.serviceName}: $errorCode")
                done()
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                val txt = info.attributes.mapValues { (_, bytes) -> bytes?.toString(Charsets.UTF_8).orEmpty() }
                val advert = LibraryAdvert.parse(info.serviceName, info.port, txt)
                val address = info.host
                if (advert != null && address != null) {
                    val host = if (address is Inet6Address) "[${address.hostAddress?.substringBefore('%')}]" else address.hostAddress
                    val found = NearbyServer(info.serviceName, advert.serverId, advert.serverName, "http://$host:${advert.port}")
                    _servers.update { list -> list.filterNot { it.serviceName == found.serviceName } + found }
                }
                done()
            }

            private fun done() = synchronized(this@LibraryBrowser) {
                resolving = false
                resolveNext()
            }
        })
    }

    private companion object {
        const val TAG = "LibraryBrowser"
    }
}
