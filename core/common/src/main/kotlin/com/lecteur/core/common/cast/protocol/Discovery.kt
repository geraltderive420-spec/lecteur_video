package com.lecteur.core.common.cast.protocol

/**
 * What a receiver advertises on the local network (NSD / DNS-SD). Pure data: the Android layer turns it into an
 * `NsdServiceInfo` and back, so the encoding rules are testable without a device.
 */
data class ReceiverAdvert(val deviceName: String, val port: Int, val version: Int = PROTOCOL_VERSION) {

    /** TXT record attributes. */
    fun toTxt(): Map<String, String> = mapOf(TXT_VERSION to version.toString(), TXT_NAME to deviceName)

    /** DNS-SD instance name: unique-ish and without the characters NSD rejects. */
    fun serviceName(): String = SERVICE_PREFIX + deviceName.filter { it.isLetterOrDigit() || it in " -_" }.trim().take(40)

    companion object {
        /** Type registered with NsdManager; the trailing dot is part of the Android API contract. */
        const val SERVICE_TYPE = "_lecteurmedia._tcp."
        const val SERVICE_PREFIX = "LecteurMedia-"
        const val TXT_VERSION = "v"
        const val TXT_NAME = "name"

        /**
         * Rebuilds an advert from a resolved service. Returns null for a service that is not ours or advertises
         * versions this build cannot talk to.
         */
        fun parse(serviceName: String?, port: Int, txt: Map<String, String>): ReceiverAdvert? {
            if (port !in 1..65535) return null
            val version = txt[TXT_VERSION]?.toIntOrNull() ?: return null
            if (version < MIN_PROTOCOL_VERSION) return null
            val name = txt[TXT_NAME]?.takeIf { it.isNotBlank() }
                ?: serviceName?.removePrefix(SERVICE_PREFIX)?.takeIf { it.isNotBlank() }
                ?: return null
            return ReceiverAdvert(name, port, version)
        }
    }
}
