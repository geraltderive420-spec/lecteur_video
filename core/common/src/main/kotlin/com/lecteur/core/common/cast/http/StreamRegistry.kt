package com.lecteur.core.common.cast.http

import java.security.SecureRandom

/** A file the phone agrees to serve. [id] is opaque to the server: the Android layer puts the content URI in it. */
data class StreamSource(
    val id: String,
    val mimeType: String,
    val sizeBytes: Long,
    val fileName: String
)

/**
 * The tokens that unlock streams. A token is 128 random bits, so the URL itself is the credential: nothing on the
 * LAN can guess a path to a file that was not explicitly shared. Tokens expire after [ttlMs] without being used.
 */
class StreamRegistry(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom()
) {
    private class Entry(val source: StreamSource, var lastUsedAt: Long)

    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun register(source: StreamSource): String {
        purgeExpired()
        val token = newToken()
        entries[token] = Entry(source, clock())
        return token
    }

    /** The source behind [token], or null when unknown, revoked or expired. A hit renews the token. */
    @Synchronized
    fun resolve(token: String): StreamSource? {
        val entry = entries[token] ?: return null
        val now = clock()
        if (now - entry.lastUsedAt > ttlMs) {
            entries.remove(token)
            return null
        }
        entry.lastUsedAt = now
        return entry.source
    }

    @Synchronized
    fun revoke(token: String) {
        entries.remove(token)
    }

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun isEmpty(): Boolean {
        purgeExpired()
        return entries.isEmpty()
    }

    private fun purgeExpired() {
        val now = clock()
        entries.values.removeAll { now - it.lastUsedAt > ttlMs }
    }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val TOKEN_BYTES = 16
        const val DEFAULT_TTL_MS = 6L * 60 * 60 * 1000
    }
}
