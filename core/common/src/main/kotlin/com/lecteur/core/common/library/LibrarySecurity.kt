package com.lecteur.core.common.library

import com.lecteur.core.common.cast.protocol.PairingCode
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A device the PC agreed to serve. The server keeps [secret] to check signatures; the device keeps it to sign. */
data class PairedDevice(val id: String, val name: String, val secret: String, val platform: String, val pairedAt: Long)

/** The credentials a phone or TV stores for one PC. */
data class DeviceCredentials(val deviceId: String, val secret: String)

/**
 * Request authentication. After pairing, the secret never crosses the network again:
 * - API calls carry `HMAC-SHA256(secret, method, path, time, body hash)` with a timestamp, so a captured request
 *   cannot be altered and is refused once [MAX_SKEW_MS] old (and POSTs cannot be replayed at all, see [ReplayGuard]);
 * - media URLs (streams, subtitles, artwork) cannot carry headers, since players fetch them directly: each one carries a
 *   signature of the resource it names instead. Such a URL only opens that one resource, and stops working the moment
 *   the device is revoked on the PC.
 */
object LibrarySignature {
    const val HEADER_DEVICE = "X-Lecteur-Device"
    const val HEADER_TIME = "X-Lecteur-Time"
    const val HEADER_SIGNATURE = "X-Lecteur-Signature"

    /** Clocks of phones and PCs drift; the client also corrects with the server's time from [ServerInfo]. */
    const val MAX_SKEW_MS = 5L * 60 * 1000

    private const val SECRET_BYTES = 32
    private const val ID_BYTES = 16
    private const val RESOURCE_SIGNATURE_CHARS = 32
    private val random = SecureRandom()

    fun newSecret(): String = randomHex(SECRET_BYTES)
    fun newId(): String = randomHex(ID_BYTES)

    fun request(secret: String, method: String, path: String, timeMs: Long, body: ByteArray = ByteArray(0)): String =
        hmacHex(secret, "req\n${method.uppercase()}\n$path\n$timeMs\n${sha256Hex(body)}")

    /** 128 bits: as hard to guess as the cast tokens, short enough for a URL. */
    fun resource(secret: String, resource: String): String = hmacHex(secret, "res\n$resource").take(RESOURCE_SIGNATURE_CHARS)

    fun matches(expected: String, actual: String?): Boolean =
        actual != null && MessageDigest.isEqual(expected.toByteArray(), actual.lowercase().toByteArray())

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun hmacHex(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun randomHex(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/**
 * URLs of the media a device fetches: `/r/<deviceId>/<signature>/<kind>/<id>[/<cosmetic name>]`.
 * The signature covers `<kind>/<id>`; the trailing name only helps players that sniff extensions.
 */
object LibraryUrls {
    const val RESOURCE_PREFIX = "/r/"
    const val KIND_STREAM = "stream"
    const val KIND_SUBTITLE = "sub"
    const val KIND_ART = "art"

    data class Resource(val deviceId: String, val signature: String, val kind: String, val id: String) {
        val signed: String get() = "$kind/$id"
    }

    /** A stream URL recognised as one of a shared library: which PC ([baseUrl]) and which of its files. */
    data class StreamRef(val baseUrl: String, val deviceId: String, val fileId: Long)

    fun stream(baseUrl: String, credentials: DeviceCredentials, fileId: Long, fileName: String): String =
        build(baseUrl, credentials, KIND_STREAM, fileId.toString(), fileName)

    fun subtitle(baseUrl: String, credentials: DeviceCredentials, fileId: Long, index: Int, fileName: String = "sub"): String =
        build(baseUrl, credentials, KIND_SUBTITLE, "$fileId-$index", fileName)

    fun art(baseUrl: String, credentials: DeviceCredentials, key: String): String = build(baseUrl, credentials, KIND_ART, key, null)

    /** Splits a request path; null when it is not a well-formed resource path. */
    fun parse(path: String): Resource? {
        val clean = path.substringBefore('?').substringBefore('#')
        if (!clean.startsWith(RESOURCE_PREFIX)) return null
        val parts = clean.removePrefix(RESOURCE_PREFIX).split('/')
        if (parts.size < 4) return null
        val (device, signature, kind, id) = parts
        if (!device.isHex() || !signature.isHex() || kind.isEmpty() || id.isEmpty() || !id.all { it.isLetterOrDigit() || it == '-' }) return null
        return Resource(device, signature, kind, id)
    }

    /** Recognises a stream URL built by [stream], whatever the host; null for any other URI. */
    fun parseStream(url: String): StreamRef? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "http" && uri.scheme != "https") return null
        val resource = parse(uri.rawPath ?: return null) ?: return null
        if (resource.kind != KIND_STREAM) return null
        val fileId = resource.id.toLongOrNull() ?: return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return StreamRef("${uri.scheme}://${uri.host}$port", resource.deviceId, fileId)
    }

    private fun build(baseUrl: String, credentials: DeviceCredentials, kind: String, id: String, name: String?): String {
        val signature = LibrarySignature.resource(credentials.secret, "$kind/$id")
        val tail = name?.let { "/" + URLEncoder.encode(it, "UTF-8").replace("+", "%20") }.orEmpty()
        return baseUrl.trimEnd('/') + RESOURCE_PREFIX + credentials.deviceId + "/" + signature + "/" + kind + "/" + id + tail
    }

    private fun String.isHex() = isNotEmpty() && length <= 64 && all { it in '0'..'9' || it in 'a'..'f' }
}

/**
 * The pairing code of the PC. It exists only while the user has the pairing window open on the PC, lasts [ttlMs], works
 * once, and too many wrong guesses close it: a device on the network cannot pair by itself, someone has to read the PC.
 */
class PairingWindow(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxFailures: Int = MAX_FAILURES,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom()
) {
    private var code: String? = null
    private var expiresAt = 0L
    private var failures = 0

    sealed interface Outcome {
        data object Accepted : Outcome
        data object WrongCode : Outcome
        /** Nobody opened the pairing window on the PC, it expired, or too many wrong codes closed it. */
        data object Closed : Outcome
    }

    @get:Synchronized
    val isOpen: Boolean get() = code != null && clock() < expiresAt

    /** Opens (or renews) the window and returns the code to show. */
    @Synchronized
    fun open(): String {
        val fresh = PairingCode.generate(random)
        code = fresh
        expiresAt = clock() + ttlMs
        failures = 0
        return fresh
    }

    @Synchronized
    fun close() {
        code = null
    }

    /** Milliseconds before the code expires, 0 when closed. */
    @Synchronized
    fun remainingMs(): Long = if (isOpen) expiresAt - clock() else 0

    @Synchronized
    fun attempt(typed: String?): Outcome {
        val expected = code?.takeIf { clock() < expiresAt } ?: return Outcome.Closed.also { code = null }
        if (PairingCode.matches(expected, typed)) {
            code = null
            return Outcome.Accepted
        }
        if (++failures >= maxFailures) code = null
        return Outcome.WrongCode
    }

    companion object {
        const val DEFAULT_TTL_MS = 5L * 60 * 1000
        const val MAX_FAILURES = 5
    }
}

/**
 * Slows down guessing: an address that keeps failing authentication is turned away for a while. Signatures are
 * 256-bit and cannot be brute-forced anyway; this mostly keeps a misbehaving device from flooding the PC.
 */
class FailureLimiter(
    private val maxFailures: Int = 20,
    private val windowMs: Long = 60_000,
    private val banMs: Long = 5 * 60_000,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private class Record(var windowStart: Long, var count: Int, var bannedUntil: Long = 0)

    private val records = HashMap<String, Record>()

    @Synchronized
    fun isBlocked(address: String): Boolean = (records[address]?.bannedUntil ?: 0) > clock()

    @Synchronized
    fun failed(address: String) {
        val now = clock()
        if (records.size > 1_000) records.values.removeAll { it.bannedUntil < now && now - it.windowStart > windowMs }
        val record = records.getOrPut(address) { Record(now, 0) }
        if (now - record.windowStart > windowMs) {
            record.windowStart = now
            record.count = 0
        }
        if (++record.count >= maxFailures) record.bannedUntil = now + banMs
    }

    @Synchronized
    fun succeeded(address: String) {
        records.remove(address)
    }
}

/** Remembers the signatures of recent writes, so a captured POST cannot be sent a second time. */
class ReplayGuard(private val windowMs: Long = 2 * LibrarySignature.MAX_SKEW_MS, private val clock: () -> Long = System::currentTimeMillis) {
    private val seen = LinkedHashMap<String, Long>()

    /** False when [signature] was already used. */
    @Synchronized
    fun firstUse(signature: String): Boolean {
        val now = clock()
        val it = seen.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value > windowMs) it.remove() else break
        }
        if (signature in seen) return false
        seen[signature] = now
        return true
    }
}

/** Only devices of the home network: private IPv4 ranges, link-local, loopback, IPv6 unique-local. */
object LanOnly {
    fun allows(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress) return true
        return when (address) {
            is Inet6Address -> {
                val first = address.address[0].toInt() and 0xFF
                // fc00::/7, and IPv4-mapped private addresses (::ffff:192.168.x.y)
                (first and 0xFE) == 0xFC || mappedV4(address)?.let(::allows) == true
            }
            is Inet4Address -> false
            else -> false
        }
    }

    private fun mappedV4(address: Inet6Address): InetAddress? {
        val b = address.address
        val mapped = (0 until 10).all { b[it].toInt() == 0 } && b[10].toInt() == -1 && b[11].toInt() == -1
        return if (mapped) InetAddress.getByAddress(b.copyOfRange(12, 16)) else null
    }
}
