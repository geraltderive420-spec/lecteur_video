package com.lecteur.desktop.cast

import com.lecteur.core.common.cast.protocol.ReceiverAdvert
import com.lecteur.core.common.library.LibraryAdvert
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/** The PC's addresses on the local network, real adapters first (virtual ones from Hyper-V, VirtualBox, VPNs last). */
object LocalAddress {
    private val VIRTUAL = Regex("(?i)virtual|vmware|vbox|hyper-v|vethernet|wsl|loopback|bluetooth|tap|tun|vpn|zerotier|tailscale|hamachi|docker")

    fun candidates(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
            .sortedBy { if (VIRTUAL.containsMatchIn(it.displayName + " " + it.name)) 1 else 0 }
            .flatMap { nif -> nif.inetAddresses.asSequence().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { nif to it } }
            .sortedWith(compareBy({ if (VIRTUAL.containsMatchIn(it.first.displayName + " " + it.first.name)) 1 else 0 }, { rank(it.second) }))
            .map { it.second as InetAddress }
            .toList()
    }.getOrDefault(emptyList())

    /** The address a device at [peer] reaches this PC on: the one the system routes through. */
    fun towards(peer: String?): String? {
        if (peer != null) {
            runCatching {
                DatagramSocket().use { s ->
                    s.connect(InetSocketAddress(peer, 9))
                    val local = s.localAddress
                    if (local is Inet4Address && !local.isAnyLocalAddress) return local.hostAddress
                }
            }
        }
        return candidates().firstOrNull()?.hostAddress
    }

    private fun rank(a: InetAddress): Int = when {
        a.hostAddress.startsWith("192.168.") -> 0
        a.hostAddress.startsWith("10.") -> 1
        else -> 2
    }
}

/** A receiver (TV or another PC) found on the network. */
data class DiscoveredReceiver(val serviceName: String, val deviceName: String, val host: String, val port: Int)

/**
 * DNS-SD over jmDNS, the same `_lecteurmedia._tcp` service the Android TV app registers with NsdManager: the phone finds
 * this PC exactly as it finds a TV, and this PC finds the TVs. One responder per real network adapter.
 */
class Mdns {
    private val type = ReceiverAdvert.SERVICE_TYPE.trimEnd('.') + ".local."
    private val libraryType = LibraryAdvert.SERVICE_TYPE.trimEnd('.') + ".local."
    private var responders: List<JmDNS> = emptyList()
    /** Services this PC announces, by role: the companion receiver and the shared library live side by side. */
    private val advertised = HashMap<String, ServiceInfo>()
    private var browsing = false

    private val _receivers = MutableStateFlow<List<DiscoveredReceiver>>(emptyList())
    val receivers: StateFlow<List<DiscoveredReceiver>> = _receivers.asStateFlow()

    @Synchronized
    private fun ensureResponders(): List<JmDNS> {
        if (responders.isEmpty()) {
            responders = LocalAddress.candidates().take(3).mapNotNull { addr ->
                runCatching { JmDNS.create(addr, "lecteurmedia-" + addr.hostAddress.replace('.', '-')) }.getOrNull()
            }
        }
        return responders
    }

    @Synchronized
    fun advertise(advert: ReceiverAdvert) = register(ROLE_RECEIVER, ServiceInfo.create(type, advert.serviceName(), advert.port, 0, 0, advert.toTxt()))

    @Synchronized
    fun unadvertise() = unregister(ROLE_RECEIVER)

    /** The shared library, under its own service type: the phone's library list finds it, its cast picker does not. */
    @Synchronized
    fun advertiseLibrary(advert: LibraryAdvert) =
        register(ROLE_LIBRARY, ServiceInfo.create(libraryType, advert.serviceName(), advert.port, 0, 0, advert.toTxt()))

    @Synchronized
    fun unadvertiseLibrary() = unregister(ROLE_LIBRARY)

    private fun register(role: String, info: ServiceInfo) {
        unregister(role)
        // Each responder needs its own copy: jmDNS binds a ServiceInfo to the instance that registered it
        val copies = ensureResponders().mapNotNull { r -> info.clone().takeIf { runCatching { r.registerService(it) }.isSuccess } }
        advertised[role] = info
        registered[role] = copies
    }

    private fun unregister(role: String) {
        advertised.remove(role) ?: return
        val copies = registered.remove(role).orEmpty()
        responders.forEach { r -> copies.forEach { c -> runCatching { r.unregisterService(c) } } }
    }

    private val registered = HashMap<String, List<ServiceInfo>>()

    @Synchronized
    fun startBrowsing() {
        if (browsing) return
        browsing = true
        ensureResponders().forEach { it.addServiceListener(type, listener) }
    }

    @Synchronized
    fun stopBrowsing() {
        if (!browsing) return
        browsing = false
        responders.forEach { runCatching { it.removeServiceListener(type, listener) } }
        _receivers.value = emptyList()
    }

    /** Asks the network again (a TV that was switched on after the list was opened). */
    fun refresh() {
        Thread {
            responders.forEach { r -> runCatching { r.list(type, 3_000).forEach(::add) } }
        }.apply { isDaemon = true }.start()
    }

    fun close() {
        unadvertise()
        unadvertiseLibrary()
        responders.forEach { runCatching { it.close() } }
        responders = emptyList()
    }

    private fun add(info: ServiceInfo) {
        if (!info.name.startsWith(ReceiverAdvert.SERVICE_PREFIX)) return
        // Our own advert (same name) is not a target
        if (advertised[ROLE_RECEIVER]?.name == info.name) return
        val txt = info.propertyNames.toList().associateWith { info.getPropertyString(it) ?: "" }
        val advert = ReceiverAdvert.parse(info.name, info.port, txt) ?: return
        val host = info.inet4Addresses.firstOrNull()?.hostAddress ?: return
        val found = DiscoveredReceiver(info.name, advert.deviceName, host, info.port)
        _receivers.update { list -> (list.filterNot { it.serviceName == found.serviceName } + found).sortedBy { it.deviceName } }
    }

    private companion object {
        const val ROLE_RECEIVER = "receiver"
        const val ROLE_LIBRARY = "library"
    }

    private val listener = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            event.dns.requestServiceInfo(event.type, event.name, true)
        }

        override fun serviceRemoved(event: ServiceEvent) {
            _receivers.update { list -> list.filterNot { it.serviceName == event.name } }
        }

        override fun serviceResolved(event: ServiceEvent) {
            add(event.info)
        }
    }
}
