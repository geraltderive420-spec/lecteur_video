package com.lecteur.core.common.library

import com.lecteur.core.common.cast.http.HttpRequest
import com.lecteur.core.common.cast.http.RangeParser
import com.lecteur.core.common.cast.http.RangeResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlin.math.abs

/** A file the server agrees to send: a video, a subtitle, a poster. [open] returns a stream already at the offset. */
class ServedFile(val name: String, val mimeType: String, val sizeBytes: Long, private val opener: (Long) -> InputStream) {
    fun open(offset: Long): InputStream = opener(offset)
}

/** What the PC shares. Called on the server's worker threads. */
interface LibraryBackend {
    val serverId: String
    val serverName: String

    /** The [Catalog] as JSON, already encoded: the backend rebuilds it only when the library changes. */
    fun catalogJson(): ByteArray
    fun watch(fileId: Long): WatchDto?
    fun saveWatch(device: PairedDevice, report: WatchReport): Boolean
    fun stream(fileId: Long): ServedFile?
    fun subtitle(fileId: Long, index: Int): ServedFile?
    fun art(key: String): ServedFile?
}

/** The devices allowed to read the library. Revoking a device is removing it: its signatures stop matching at once. */
interface DeviceDirectory {
    fun find(deviceId: String): PairedDevice?
    fun add(device: PairedDevice)
    fun seen(device: PairedDevice, address: String) {}
}

/**
 * The PC's library server: a small HTTP/1.1 server (one request per connection, like the cast server) with
 * - `GET /api/v1/info` (public), `POST /api/v1/pair` (pairing code), signed `GET /api/v1/catalog`, `GET /api/v1/watch/<id>`,
 *   `POST /api/v1/watch`;
 * - `GET|HEAD /r/...` for media, with byte ranges, authenticated by a per-resource signature (see [LibraryUrls]).
 * Only the local network is answered ([LanOnly]); repeated failures from one address get it turned away for a while.
 */
class LibraryServer(
    private val backend: LibraryBackend,
    private val devices: DeviceDirectory,
    private val pairing: PairingWindow,
    private val requestedPort: Int = LibraryAdvert.DEFAULT_PORT,
    private val bindAddress: InetAddress? = null,
    private val allowAddress: (InetAddress) -> Boolean = LanOnly::allows,
    private val clock: () -> Long = System::currentTimeMillis,
    private val limiter: FailureLimiter = FailureLimiter(clock = clock),
    private val onPaired: (PairedDevice) -> Unit = {}
) {
    private val running = AtomicBoolean(false)
    private val active = AtomicInteger(0)
    private val replay = ReplayGuard(clock = clock)
    private var serverSocket: ServerSocket? = null
    private var workers = Executors.newCachedThreadPool(daemon("library-http-worker"))

    val isRunning: Boolean get() = running.get()
    val port: Int get() = serverSocket?.localPort ?: -1

    /** Listens on [requestedPort], or on any free port when it is taken (another copy of the app, another program). */
    @Synchronized
    fun start(): Int {
        check(!running.get()) { "already running" }
        val socket = try {
            ServerSocket(requestedPort, BACKLOG, bindAddress)
        } catch (_: BindException) {
            ServerSocket(0, BACKLOG, bindAddress)
        }
        serverSocket = socket
        workers = Executors.newCachedThreadPool(daemon("library-http-worker"))
        running.set(true)
        Thread({ acceptLoop(socket) }, "library-http-accept").apply { isDaemon = true }.start()
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        workers.shutdownNow()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                return
            } catch (_: IOException) {
                continue
            }
            // Outside the home network: not even an answer
            if (!allowAddress(client.inetAddress)) {
                runCatching { client.close() }
                continue
            }
            try {
                workers.execute { handle(client) }
            } catch (_: RuntimeException) {
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket) {
        val address = client.inetAddress.hostAddress ?: "?"
        val count = active.incrementAndGet()
        try {
            client.use {
                it.soTimeout = READ_TIMEOUT_MS
                val input = BufferedInputStream(it.getInputStream())
                val output = it.getOutputStream()
                if (count > MAX_CONNECTIONS) return respond(output, 503, "Service Unavailable")
                if (limiter.isBlocked(address)) return error(output, 429, "Too Many Requests", "blocked", "Trop d'essais refusés : réessayez dans quelques minutes.")
                val request = HttpRequest.read(input) ?: return respond(output, 400, "Bad Request")
                val body = readBody(request, input) ?: return respond(output, 413, "Payload Too Large")
                Exchange(request, body, output, address).route()
                output.flush()
            }
        } catch (_: IOException) {
            // The player closed the connection (seek, stop): normal.
        } finally {
            active.decrementAndGet()
        }
    }

    private inner class Exchange(val request: HttpRequest, val body: ByteArray, val output: OutputStream, val address: String) {
        private val path = request.path.substringBefore('#')
        private val route = path.substringBefore('?')

        fun route() {
            when {
                request.method == "OPTIONS" -> respond(output, 204, "No Content", mapOf("Allow" to "GET, HEAD, POST, OPTIONS"))
                route.startsWith(LibraryUrls.RESOURCE_PREFIX) && (request.method == "GET" || request.method == "HEAD") -> resource()
                route == "/api/v1/info" && request.method == "GET" -> json(ServerInfo.serializer(), info())
                route == "/api/v1/pair" && request.method == "POST" -> pair()
                route.startsWith("/api/") -> api()
                else -> respond(output, 404, "Not Found")
            }
        }

        private fun info() = ServerInfo(backend.serverId, backend.serverName, LIBRARY_API_VERSION, clock(), pairing.isOpen)

        private fun pair() {
            val req = decode(PairRequest.serializer()) ?: return respond(output, 400, "Bad Request")
            when (pairing.attempt(req.code)) {
                PairingWindow.Outcome.Closed ->
                    error(output, 403, "Forbidden", "pairing_closed", "Ouvrez « Associer un appareil » sur le PC pour afficher un code.")

                PairingWindow.Outcome.WrongCode -> {
                    limiter.failed(address)
                    error(output, 403, "Forbidden", "bad_code", "Code incorrect.")
                }

                PairingWindow.Outcome.Accepted -> {
                    val device = PairedDevice(
                        id = LibrarySignature.newId(),
                        name = req.deviceName.take(60).ifBlank { "Appareil" },
                        secret = LibrarySignature.newSecret(),
                        platform = req.platform.take(20),
                        pairedAt = clock()
                    )
                    devices.add(device)
                    onPaired(device)
                    json(PairResponse.serializer(), PairResponse(backend.serverId, backend.serverName, device.id, device.secret))
                }
            }
        }

        private fun api() {
            val device = authenticate() ?: return
            when {
                route == "/api/v1/catalog" && request.method == "GET" -> bytes(backend.catalogJson(), JSON)

                route.startsWith("/api/v1/watch/") && request.method == "GET" -> {
                    val id = route.removePrefix("/api/v1/watch/").toLongOrNull() ?: return respond(output, 400, "Bad Request")
                    val watch = backend.watch(id) ?: WatchDto()
                    json(WatchDto.serializer(), watch)
                }

                route == "/api/v1/watch" && request.method == "POST" -> {
                    // A captured write must not be replayable: each signature works once
                    if (!replay.firstUse(request.header(LibrarySignature.HEADER_SIGNATURE).orEmpty())) {
                        return error(output, 409, "Conflict", "replay", "Requête déjà reçue.")
                    }
                    val report = decode(WatchReport.serializer()) ?: return respond(output, 400, "Bad Request")
                    if (backend.saveWatch(device, report)) respond(output, 204, "No Content") else respond(output, 404, "Not Found")
                }

                else -> respond(output, 404, "Not Found")
            }
        }

        /** The device behind a signed API call, or null once the refusal is written. */
        private fun authenticate(): PairedDevice? {
            val deviceId = request.header(LibrarySignature.HEADER_DEVICE)
            val time = request.header(LibrarySignature.HEADER_TIME)?.toLongOrNull()
            val signature = request.header(LibrarySignature.HEADER_SIGNATURE)
            val device = deviceId?.let(devices::find)
            if (device == null) {
                limiter.failed(address)
                error(output, 401, "Unauthorized", "revoked", "Cet appareil n'est pas (ou plus) autorisé sur ce PC. Associez-le à nouveau.")
                return null
            }
            if (time == null || abs(clock() - time) > LibrarySignature.MAX_SKEW_MS) {
                error(output, 401, "Unauthorized", "clock", "L'heure de cet appareil et celle du PC sont trop différentes.")
                return null
            }
            val expected = LibrarySignature.request(device.secret, request.method, path, time, body)
            if (!LibrarySignature.matches(expected, signature)) {
                limiter.failed(address)
                error(output, 401, "Unauthorized", "signature", "Signature refusée.")
                return null
            }
            limiter.succeeded(address)
            devices.seen(device, address)
            return device
        }

        private fun resource() {
            val res = LibraryUrls.parse(route) ?: return respond(output, 404, "Not Found")
            val device = devices.find(res.deviceId)
            if (device == null || !LibrarySignature.matches(LibrarySignature.resource(device.secret, res.signed), res.signature)) {
                limiter.failed(address)
                return respond(output, 403, "Forbidden")
            }
            val file = when (res.kind) {
                LibraryUrls.KIND_STREAM -> res.id.toLongOrNull()?.let(backend::stream)
                LibraryUrls.KIND_SUBTITLE -> {
                    val fileId = res.id.substringBefore('-').toLongOrNull()
                    val index = res.id.substringAfter('-', "").toIntOrNull()
                    if (fileId != null && index != null) backend.subtitle(fileId, index) else null
                }
                LibraryUrls.KIND_ART -> backend.art(res.id)
                else -> null
            } ?: return respond(output, 404, "Not Found")
            if (res.kind == LibraryUrls.KIND_STREAM) devices.seen(device, address)
            serveFile(file, cacheable = res.kind == LibraryUrls.KIND_ART)
        }

        private fun serveFile(file: ServedFile, cacheable: Boolean) {
            val size = file.sizeBytes
            val common = mapOf(
                "Content-Type" to file.mimeType,
                "Accept-Ranges" to "bytes",
                "Cache-Control" to if (cacheable) "private, max-age=604800" else "no-store"
            )
            val head = request.method == "HEAD"
            when (val range = RangeParser.parse(request.header("range"), size)) {
                RangeResult.NotSatisfiable -> respond(output, 416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */$size"))
                RangeResult.Full -> {
                    writeHead(output, 200, "OK", common + ("Content-Length" to size.toString()))
                    if (!head) copy(file, 0, size)
                }
                is RangeResult.Partial -> {
                    writeHead(
                        output, 206, "Partial Content",
                        common + mapOf("Content-Length" to range.length.toString(), "Content-Range" to "bytes ${range.start}-${range.endInclusive}/$size")
                    )
                    if (!head) copy(file, range.start, range.length)
                }
            }
        }

        private fun copy(file: ServedFile, offset: Long, length: Long) {
            file.open(offset).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var remaining = length
                while (remaining > 0 && running.get()) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }

        private fun <T> decode(serializer: KSerializer<T>): T? =
            runCatching { wire.decodeFromString(serializer, body.toString(Charsets.UTF_8)) }.getOrNull()

        private fun <T> json(serializer: KSerializer<T>, value: T) = bytes(wire.encodeToString(serializer, value).toByteArray(), JSON)

        private fun bytes(content: ByteArray, type: String) {
            val gzip = content.size > GZIP_MIN_BYTES && request.header("accept-encoding")?.contains("gzip") == true
            val payload = if (gzip) ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(content) } }.toByteArray() else content
            val headers = mapOf("Content-Type" to type, "Content-Length" to payload.size.toString(), "Cache-Control" to "no-store") +
                (if (gzip) mapOf("Content-Encoding" to "gzip") else emptyMap())
            writeHead(output, 200, "OK", headers)
            if (request.method != "HEAD") output.write(payload)
        }
    }

    private fun error(output: OutputStream, code: Int, reason: String, error: String, message: String) {
        val payload = wire.encodeToString(ErrorDto.serializer(), ErrorDto(error, message)).toByteArray()
        writeHead(output, code, reason, mapOf("Content-Type" to JSON, "Content-Length" to payload.size.toString()))
        output.write(payload)
        output.flush()
    }

    private fun respond(output: OutputStream, code: Int, reason: String, extra: Map<String, String> = emptyMap()) {
        writeHead(output, code, reason, extra + ("Content-Length" to "0"))
        output.flush()
    }

    private fun writeHead(output: OutputStream, code: Int, reason: String, headers: Map<String, String>) {
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("X-Content-Type-Options: nosniff\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
    }

    /** The body of a POST (JSON, small); empty for other methods; null when it is larger than allowed. */
    private fun readBody(request: HttpRequest, input: InputStream): ByteArray? {
        if (request.method != "POST") return ByteArray(0)
        val length = request.header("content-length")?.toIntOrNull() ?: return ByteArray(0)
        if (length < 0 || length > MAX_BODY_BYTES) return null
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw IOException("body cut short")
            read += n
        }
        return body
    }

    private fun daemon(name: String) = ThreadFactory { runnable -> Thread(runnable, name).apply { isDaemon = true } }

    companion object {
        private const val BACKLOG = 32
        private const val READ_TIMEOUT_MS = 30_000
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_BODY_BYTES = 64 * 1024
        private const val MAX_CONNECTIONS = 64
        private const val GZIP_MIN_BYTES = 2_048
        private const val JSON = "application/json; charset=utf-8"

        internal val wire = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

        /** Encodes a catalog for [LibraryBackend.catalogJson]. */
        fun encodeCatalog(catalog: Catalog): ByteArray = wire.encodeToString(Catalog.serializer(), catalog).toByteArray()
    }
}
