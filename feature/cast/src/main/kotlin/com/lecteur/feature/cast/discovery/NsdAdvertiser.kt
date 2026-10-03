package com.lecteur.feature.cast.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.lecteur.core.common.cast.protocol.ReceiverAdvert

/** TV side: publishes the receiver on the local network so phones can list it. */
class NsdAdvertiser(context: Context) {
    private val manager = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    @Synchronized
    fun register(advert: ReceiverAdvert) {
        unregister()
        val info = NsdServiceInfo().apply {
            serviceName = advert.serviceName()
            serviceType = ReceiverAdvert.SERVICE_TYPE
            port = advert.port
            advert.toTxt().forEach { (key, value) -> setAttribute(key, value) }
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "advertised as ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "advert failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "unregister failed: $errorCode")
            }
        }
        listener = registration
        manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
    }

    @Synchronized
    fun unregister() {
        listener?.let { runCatching { manager.unregisterService(it) } }
        listener = null
    }

    private companion object {
        const val TAG = "NsdAdvertiser"
    }
}
