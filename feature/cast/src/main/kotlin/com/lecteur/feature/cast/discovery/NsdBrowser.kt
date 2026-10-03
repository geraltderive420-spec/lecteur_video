package com.lecteur.feature.cast.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.lecteur.core.common.cast.protocol.ReceiverAdvert
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.ArrayDeque

/** A TV found on the network, resolved to something connectable. */
data class DiscoveredReceiver(val serviceName: String, val deviceName: String, val host: String, val port: Int)

/**
 * Phone side: lists the receivers currently on the network. NsdManager resolves one service at a time, so found
 * services queue up; the list follows `onServiceLost` so a TV that is switched off disappears.
 */
class NsdBrowser(context: Context) {
    private val manager = context.getSystemService(NsdManager::class.java)

    private val _receivers = MutableStateFlow<List<DiscoveredReceiver>>(emptyList())
    val receivers: StateFlow<List<DiscoveredReceiver>> = _receivers.asStateFlow()

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
                synchronized(this@NsdBrowser) { discovery = null }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "stop failed: $errorCode")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceName.startsWith(ReceiverAdvert.SERVICE_PREFIX)) return
                enqueue(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                _receivers.update { list -> list.filterNot { it.serviceName == service.serviceName } }
            }
        }
        discovery = listener
        manager.discoverServices(ReceiverAdvert.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @Synchronized
    fun stop() {
        discovery?.let { runCatching { manager.stopServiceDiscovery(it) } }
        discovery = null
        pending.clear()
        resolving = false
        _receivers.value = emptyList()
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
                val advert = ReceiverAdvert.parse(info.serviceName, info.port, txt)
                val host = info.host?.hostAddress
                if (advert != null && host != null) {
                    val found = DiscoveredReceiver(info.serviceName, advert.deviceName, host, advert.port)
                    _receivers.update { list -> list.filterNot { it.serviceName == found.serviceName } + found }
                }
                done()
            }

            private fun done() = synchronized(this@NsdBrowser) {
                resolving = false
                resolveNext()
            }
        })
    }

    private companion object {
        const val TAG = "NsdBrowser"
    }
}
