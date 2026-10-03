package com.lecteur.core.common.cast

import com.google.common.truth.Truth.assertThat
import com.lecteur.core.common.cast.http.AutoStopPolicy
import com.lecteur.core.common.cast.http.MediaHttpServer
import com.lecteur.core.common.cast.http.RangeParser
import com.lecteur.core.common.cast.http.RangeResult
import com.lecteur.core.common.cast.http.StreamOpener
import com.lecteur.core.common.cast.http.StreamRegistry
import com.lecteur.core.common.cast.http.StreamSource
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.Test

class RangeParserTest {
    private val size = 1000L

    @Test fun `no header means the whole file`() = assertThat(RangeParser.parse(null, size)).isEqualTo(RangeResult.Full)

    @Test fun `closed range`() =
        assertThat(RangeParser.parse("bytes=0-99", size)).isEqualTo(RangeResult.Partial(0, 99))

    @Test fun `open ended range runs to the end`() =
        assertThat(RangeParser.parse("bytes=500-", size)).isEqualTo(RangeResult.Partial(500, 999))

    @Test fun `end is clamped to the size`() =
        assertThat(RangeParser.parse("bytes=900-5000", size)).isEqualTo(RangeResult.Partial(900, 999))

    @Test fun `suffix range is the last bytes`() =
        assertThat(RangeParser.parse("bytes=-100", size)).isEqualTo(RangeResult.Partial(900, 999))

    @Test fun `suffix longer than the file is the whole file`() =
        assertThat(RangeParser.parse("bytes=-5000", size)).isEqualTo(RangeResult.Partial(0, 999))

    @Test fun `empty suffix is not satisfiable`() =
        assertThat(RangeParser.parse("bytes=-0", size)).isEqualTo(RangeResult.NotSatisfiable)

    @Test fun `start beyond the end is not satisfiable`() {
        assertThat(RangeParser.parse("bytes=1000-", size)).isEqualTo(RangeResult.NotSatisfiable)
        assertThat(RangeParser.parse("bytes=2000-2100", size)).isEqualTo(RangeResult.NotSatisfiable)
    }

    @Test fun `last byte only`() =
        assertThat(RangeParser.parse("bytes=999-999", size)).isEqualTo(RangeResult.Partial(999, 999))

    @Test fun `unknown unit, several ranges and garbage are ignored`() {
        for (h in listOf("items=0-5", "bytes=0-5,10-20", "bytes=abc", "bytes=", "bytes=5-2", "bytes=-x", "bytes=1x-4", "")) {
            assertThat(RangeParser.parse(h, size)).isEqualTo(RangeResult.Full)
        }
    }

    @Test fun `unit is case insensitive and spaces are tolerated`() =
        assertThat(RangeParser.parse(" Bytes= 10 - 19 ", size)).isEqualTo(RangeResult.Partial(10, 19))

    @Test fun `empty resource ignores ranges`() =
        assertThat(RangeParser.parse("bytes=0-10", 0)).isEqualTo(RangeResult.Full)
}

class StreamRegistryTest {
    private var now = 0L
    private val source = StreamSource("content://x/1", "video/mp4", 10, "a.mp4")

    @Test fun `tokens are 128 bit hex and unique`() {
        val registry = StreamRegistry(clock = { now })
        val a = registry.register(source)
        val b = registry.register(source)
        assertThat(a).matches("[0-9a-f]{32}")
        assertThat(a).isNotEqualTo(b)
    }

    @Test fun `unknown token does not resolve`() =
        assertThat(StreamRegistry(clock = { now }).resolve("deadbeef")).isNull()

    @Test fun `token expires after the idle ttl and use renews it`() {
        val registry = StreamRegistry(ttlMs = 100, clock = { now })
        val token = registry.register(source)
        now = 90
        assertThat(registry.resolve(token)).isEqualTo(source)
        now = 180 // 90 after the renewal
        assertThat(registry.resolve(token)).isEqualTo(source)
        now = 400
        assertThat(registry.resolve(token)).isNull()
        assertThat(registry.isEmpty()).isTrue()
    }

    @Test fun `revoke and clear`() {
        val registry = StreamRegistry(clock = { now })
        val a = registry.register(source)
        val b = registry.register(source)
        registry.revoke(a)
        assertThat(registry.resolve(a)).isNull()
        assertThat(registry.resolve(b)).isNotNull()
        registry.clear()
        assertThat(registry.resolve(b)).isNull()
    }
}

class AutoStopPolicyTest {
    private var now = 0L

    @Test fun `stops after the idle timeout with no connection`() {
        val policy = AutoStopPolicy(idleTimeoutMs = 1000, clock = { now })
        now = 999
        assertThat(policy.shouldStop()).isFalse()
        now = 1000
        assertThat(policy.shouldStop()).isTrue()
    }

    @Test fun `an open connection keeps it alive however long`() {
        val policy = AutoStopPolicy(idleTimeoutMs = 1000, clock = { now })
        policy.onRequestStart()
        now = 100_000
        assertThat(policy.shouldStop()).isFalse()
        policy.onRequestEnd()
        assertThat(policy.shouldStop()).isFalse() // the timer restarts when the last request ends
        now = 101_000
        assertThat(policy.shouldStop()).isTrue()
    }

    @Test fun `touch restarts the timer and the counter never goes negative`() {
        val policy = AutoStopPolicy(idleTimeoutMs = 1000, clock = { now })
        now = 900
        policy.touch()
        now = 1500
        assertThat(policy.shouldStop()).isFalse()
        policy.onRequestEnd()
        assertThat(policy.activeConnections).isEqualTo(0)
    }
}

class MediaHttpServerTest {
    private val data = ByteArray(5000) { (it % 251).toByte() }
    private val source = StreamSource("mem://movie", "video/x-matroska", data.size.toLong(), "film é.mkv")
    private val registry = StreamRegistry()
    private lateinit var server: MediaHttpServer
    private var port = 0
    private lateinit var token: String
    private val opened = mutableListOf<Long>()

    @Before fun setUp() {
        server = MediaHttpServer(
            registry,
            StreamOpener { _, offset ->
                synchronized(opened) { opened += offset }
                ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
            },
            bindAddress = InetAddress.getLoopbackAddress()
        )
        port = server.start()
        token = registry.register(source)
    }

    @After fun tearDown() = server.stop()

    private class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    private fun request(method: String, path: String, vararg extra: String): Response {
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 5000
            val head = buildString {
                append("$method $path HTTP/1.1\r\nHost: localhost\r\n")
                extra.forEach { append(it).append("\r\n") }
                append("\r\n")
            }
            socket.getOutputStream().write(head.toByteArray())
            socket.getOutputStream().flush()
            val raw = socket.getInputStream().readBytes()
            val split = String(raw, Charsets.ISO_8859_1).indexOf("\r\n\r\n")
            val lines = String(raw, 0, split, Charsets.ISO_8859_1).split("\r\n")
            val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            return Response(lines[0].split(' ')[1].toInt(), headers, raw.copyOfRange(split + 4, raw.size))
        }
    }

    private val path get() = MediaHttpServer.pathFor(token, source.fileName)

    @Test fun `serves the whole file with range support advertised`() {
        val r = request("GET", path)
        assertThat(r.status).isEqualTo(200)
        assertThat(r.body).isEqualTo(data)
        assertThat(r.headers["content-length"]).isEqualTo("5000")
        assertThat(r.headers["accept-ranges"]).isEqualTo("bytes")
        assertThat(r.headers["content-type"]).isEqualTo("video/x-matroska")
        assertThat(r.headers["access-control-allow-origin"]).isEqualTo("*")
    }

    @Test fun `serves a partial range from the right offset`() {
        val r = request("GET", path, "Range: bytes=1000-1999")
        assertThat(r.status).isEqualTo(206)
        assertThat(r.body).isEqualTo(data.copyOfRange(1000, 2000))
        assertThat(r.headers["content-range"]).isEqualTo("bytes 1000-1999/5000")
        assertThat(r.headers["content-length"]).isEqualTo("1000")
        assertThat(synchronized(opened) { opened.toList() }).containsExactly(1000L)
    }

    @Test fun `open ended and suffix ranges`() {
        assertThat(request("GET", path, "Range: bytes=4900-").body).isEqualTo(data.copyOfRange(4900, 5000))
        assertThat(request("GET", path, "Range: bytes=-10").body).isEqualTo(data.copyOfRange(4990, 5000))
    }

    @Test fun `unsatisfiable range gets 416 with the size`() {
        val r = request("GET", path, "Range: bytes=9000-")
        assertThat(r.status).isEqualTo(416)
        assertThat(r.headers["content-range"]).isEqualTo("bytes */5000")
    }

    @Test fun `HEAD returns headers and no body`() {
        val r = request("HEAD", path)
        assertThat(r.status).isEqualTo(200)
        assertThat(r.headers["content-length"]).isEqualTo("5000")
        assertThat(r.body).isEmpty()
        assertThat(synchronized(opened) { opened.toList() }).isEmpty()
    }

    @Test fun `unknown or expired token is forbidden and a foreign path is not found`() {
        assertThat(request("GET", "/stream/${"0".repeat(32)}").status).isEqualTo(403)
        assertThat(request("GET", "/stream/").status).isEqualTo(404)
        assertThat(request("GET", "/etc/passwd").status).isEqualTo(404)
        registry.revoke(token)
        assertThat(request("GET", path).status).isEqualTo(403)
    }

    @Test fun `path traversal does not reach the stream`() {
        assertThat(request("GET", "/stream/../stream/$token").status).isEqualTo(403)
    }

    @Test fun `other methods are refused and OPTIONS answers the CORS preflight`() {
        assertThat(request("POST", path).status).isEqualTo(405)
        val r = request("OPTIONS", path)
        assertThat(r.status).isEqualTo(204)
        assertThat(r.headers["access-control-allow-headers"]).contains("Range")
    }

    @Test fun `malformed request gets 400`() {
        Socket(InetAddress.getLoopbackAddress(), port).use {
            it.soTimeout = 5000
            it.getOutputStream().write("garbage\r\n\r\n".toByteArray())
            assertThat(String(it.getInputStream().readBytes())).startsWith("HTTP/1.1 400")
        }
    }

    @Test fun `file name is encoded in the path`() {
        assertThat(MediaHttpServer.pathFor("abc", "film é 1.mkv")).isEqualTo("/stream/abc/film%20%C3%A9%201.mkv")
    }

    @Test fun `watchdog stops the server and notifies when idle`() {
        server.stop()
        val stopped = CountDownLatch(1)
        val idle = MediaHttpServer(
            registry, { _, _ -> ByteArrayInputStream(data) },
            autoStop = AutoStopPolicy(idleTimeoutMs = 50),
            bindAddress = InetAddress.getLoopbackAddress(),
            idleCheckIntervalMs = 20,
            onIdle = { stopped.countDown() }
        )
        idle.start()
        assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(idle.isRunning).isFalse()
    }

    @Test fun `stop closes the port`() {
        server.stop()
        assertThat(server.isRunning).isFalse()
        // Closing a listening socket is not instantaneous on every kernel: allow a moment for the refusal.
        val deadline = System.currentTimeMillis() + 3_000
        var refused = false
        while (!refused && System.currentTimeMillis() < deadline) {
            refused = runCatching { Socket(InetAddress.getLoopbackAddress(), port).close() }.isFailure
            if (!refused) Thread.sleep(50)
        }
        assertThat(refused).isTrue()
    }
}
