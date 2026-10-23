package com.lecteur.core.common.library

/**
 * What a PC sharing its library advertises on the network (DNS-SD), next to the companion receiver: a different
 * service type, so the cast pickers never list a library and the library list never lists a TV.
 */
data class LibraryAdvert(val serverName: String, val serverId: String, val port: Int, val version: Int = LIBRARY_API_VERSION) {

    fun toTxt(): Map<String, String> = mapOf(TXT_VERSION to version.toString(), TXT_NAME to serverName, TXT_ID to serverId)

    fun serviceName(): String = SERVICE_PREFIX + serverName.filter { it.isLetterOrDigit() || it in " -_" }.trim().take(40)

    companion object {
        const val SERVICE_TYPE = "_lecteurlib._tcp."
        const val SERVICE_PREFIX = "LecteurLib-"
        const val TXT_VERSION = "v"
        const val TXT_NAME = "name"
        const val TXT_ID = "id"

        /** Default port, so an address typed by hand (when multicast discovery is blocked) needs no port. */
        const val DEFAULT_PORT = 47_810

        fun parse(serviceName: String?, port: Int, txt: Map<String, String>): LibraryAdvert? {
            if (port !in 1..65535) return null
            val version = txt[TXT_VERSION]?.toIntOrNull() ?: return null
            if (version < 1) return null
            val id = txt[TXT_ID]?.takeIf { it.isNotBlank() } ?: return null
            val name = txt[TXT_NAME]?.takeIf { it.isNotBlank() }
                ?: serviceName?.removePrefix(SERVICE_PREFIX)?.takeIf { it.isNotBlank() }
                ?: return null
            return LibraryAdvert(name, id, port, version)
        }
    }
}
