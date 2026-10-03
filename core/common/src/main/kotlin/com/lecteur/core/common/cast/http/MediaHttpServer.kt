package com.lecteur.core.common.cast.http

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Opens a shared file for reading, already positioned at [offset]. The Android layer backs this with a content URI. */
fun interface StreamOpener {
    @Throws(IOException::class)
    fun open(source: StreamSource, offset: Long): InputStream
}

/**
 * Minimal HTTP/1.1 file server for the phone: GET/HEAD/OPTIONS on `/stream/<token>[/name]`, byte ranges, one request
 * per connection. Deliberately tiny (no keep-alive, no chunked encoding): ExoPlayer and Chromecast only need ranges.
 */
class MediaHttpServer(
    private val registry: StreamRegistry,
    private val opener: StreamOpener,
    private val autoStop: AutoStopPolicy = AutoStopPolicy(),
    private val bindAddress: InetAddress? = null,
    private val requestedPort: Int = 0,
    private val idleCheckIntervalMs: Long = 15_000,
    /** Called once from the watchdog when the policy says nobody needs the server any more. */
    private val onIdle: () -> Unit = {}
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var workers = Executors.newCachedThreadPool(daemonFactory("media-http-worker"))
    private var watchdog: ScheduledExecutorService? = null

    val isRunning: Boolean get() = running.get()
    val port: Int get() = serverSocket?.localPort ?: -1

    /** Starts listening and returns the port actually bound. */
    @Synchronized
    fun start(): Int {
        check(!running.get()) { "already running" }
        val socket = ServerSocket(requestedPort, BACKLOG, bindAddress)
        serverSocket = socket
        workers = Executors.newCachedThreadPool(daemonFactory("media-http-worker"))
        running.set(true)
        autoStop.touch()

        Thread({ acceptLoop(socket) }, "media-http-accept").apply { isDaemon = true }.start()
        watchdog = Executors.newSingleThreadScheduledExecutor(daemonFactory("media-http-watchdog")).also {
            it.scheduleWithFixedDelay(::checkIdle, idleCheckIntervalMs, idleCheckIntervalMs, TimeUnit.MILLISECONDS)
        }
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        watchdog?.shutdownNow()
        watchdog = null
        workers.shutdownNow()
    }

    private fun checkIdle() {
        if (running.get() && autoStop.shouldStop()) {
            stop()
            onIdle()
        }
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
            try {
                workers.execute { handle(client) }
            } catch (_: RuntimeException) {
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket) {
        autoStop.onRequestStart()
        try {
            client.use {
                it.soTimeout = READ_TIMEOUT_MS
                val input = BufferedInputStream(it.getInputStream())
                val output = it.getOutputStream()
                val request = HttpRequest.read(input)
                if (request == null) {
                    respond(output, 400, "Bad Request")
                } else {
                    serve(request, output)
                }
            }
        } catch (_: IOException) {
            // The player closed the connection (seek, stop): normal.
        } finally {
            autoStop.onRequestEnd()
        }
    }

    private fun serve(request: HttpRequest, output: OutputStream) {
        if (request.method == "OPTIONS") {
            return respond(output, 204, "No Content", mapOf("Allow" to "GET, HEAD, OPTIONS"))
        }
        if (request.method != "GET" && request.method != "HEAD") {
            return respond(output, 405, "Method Not Allowed", mapOf("Allow" to "GET, HEAD, OPTIONS"))
        }
        val token = tokenOf(request.path) ?: return respond(output, 404, "Not Found")
        val source = registry.resolve(token) ?: return respond(output, 403, "Forbidden")

        val size = source.sizeBytes
        val common = mapOf(
            "Content-Type" to source.mimeType,
            "Accept-Ranges" to "bytes"
        )
        when (val range = RangeParser.parse(request.header("range"), size)) {
            RangeResult.NotSatisfiable ->
                respond(output, 416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */$size"))

            RangeResult.Full -> {
                writeHead(output, 200, "OK", common + ("Content-Length" to size.toString()))
                if (request.method == "GET") copyBody(source, 0, size, output)
            }

            is RangeResult.Partial -> {
                val headers = common + mapOf(
                    "Content-Length" to range.length.toString(),
                    "Content-Range" to "bytes ${range.start}-${range.endInclusive}/$size"
                )
                writeHead(output, 206, "Partial Content", headers)
                if (request.method == "GET") copyBody(source, range.start, range.length, output)
            }
        }
        output.flush()
    }

    private fun copyBody(source: StreamSource, offset: Long, length: Long, output: OutputStream) {
        opener.open(source, offset).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var remaining = length
            while (remaining > 0 && running.get()) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                output.write(buffer, 0, read)
                remaining -= read
                autoStop.touch()
            }
        }
    }

    private fun respond(output: OutputStream, code: Int, reason: String, extra: Map<String, String> = emptyMap()) {
        writeHead(output, code, reason, extra + ("Content-Length" to "0"))
        output.flush()
    }

    private fun writeHead(output: OutputStream, code: Int, reason: String, headers: Map<String, String>) {
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            // Chromecast fetches media from a web origin and refuses responses without CORS.
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: Range\r\n")
            append("Access-Control-Expose-Headers: Content-Range, Content-Length, Accept-Ranges\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
    }

    private fun tokenOf(path: String): String? {
        val clean = path.substringBefore('?').substringBefore('#')
        if (!clean.startsWith(STREAM_PREFIX)) return null
        return clean.removePrefix(STREAM_PREFIX).substringBefore('/').takeIf { it.isNotEmpty() }
    }

    private fun daemonFactory(name: String) = java.util.concurrent.ThreadFactory { runnable ->
        Thread(runnable, name).apply { isDaemon = true }
    }

    companion object {
        const val STREAM_PREFIX = "/stream/"
        private const val BACKLOG = 16
        private const val READ_TIMEOUT_MS = 30_000
        private const val BUFFER_SIZE = 64 * 1024

        /** The path to append to `http://host:port` for [token]; the file name is cosmetic (players sniff extensions). */
        fun pathFor(token: String, fileName: String): String =
            STREAM_PREFIX + token + "/" + java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
    }
}

/** Request line and headers; bodies are never read (GET/HEAD only). */
internal class HttpRequest(val method: String, val path: String, private val headers: Map<String, String>) {
    fun header(name: String): String? = headers[name.lowercase()]

    companion object {
        private const val MAX_HEAD_BYTES = 16 * 1024

        /** Null when the head is malformed or too large. */
        fun read(input: InputStream): HttpRequest? {
            val lines = ArrayList<String>()
            val line = StringBuilder()
            var total = 0
            while (true) {
                val b = input.read()
                if (b < 0) return null
                if (++total > MAX_HEAD_BYTES) return null
                if (b == '\n'.code) {
                    val text = line.toString().trimEnd('\r')
                    line.setLength(0)
                    if (text.isEmpty()) break
                    lines += text
                } else {
                    line.append(b.toChar())
                }
            }
            val requestLine = lines.firstOrNull()?.split(' ') ?: return null
            if (requestLine.size < 3 || !requestLine[2].startsWith("HTTP/")) return null
            val headers = HashMap<String, String>()
            for (raw in lines.drop(1)) {
                val colon = raw.indexOf(':')
                if (colon <= 0) return null
                headers[raw.substring(0, colon).trim().lowercase()] = raw.substring(colon + 1).trim()
            }
            return HttpRequest(requestLine[0].uppercase(), requestLine[1], headers)
        }
    }
}
