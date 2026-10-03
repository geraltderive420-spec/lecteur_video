package com.lecteur.core.common.cast

import com.google.common.truth.Truth.assertThat
import com.lecteur.core.common.cast.http.AutoStopPolicy
import com.lecteur.core.common.cast.http.MediaHttpServer
import com.lecteur.core.common.cast.http.StreamOpener
import com.lecteur.core.common.cast.http.StreamRegistry
import com.lecteur.core.common.cast.http.StreamSource
import com.lecteur.core.common.cast.protocol.ControllerListener
import com.lecteur.core.common.cast.protocol.Open
import com.lecteur.core.common.cast.protocol.ReceiverListener
import com.lecteur.core.common.cast.protocol.RemoteControllerClient
import com.lecteur.core.common.cast.protocol.RemoteMessage
import com.lecteur.core.common.cast.protocol.RemoteReceiverServer
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Test

/**
 * The whole companion path on loopback: the phone shares a file and sends its URL through the protocol, the "TV"
 * reads the file from that URL the way a player does (ranges, seeks), and the server then lets go.
 */
class CastEndToEndTest {

    /** A file of [size] bytes generated on the fly: byte i is (i % 251). No memory held, so it can be huge. */
    private class SyntheticOpener : StreamOpener {
        override fun open(source: StreamSource, offset: Long): InputStream = object : InputStream() {
            private var position = offset
            override fun read(): Int = if (position >= source.sizeBytes) -1 else ((position++ % 251).toInt())
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (position >= source.sizeBytes) return -1
                val n = minOf(len.toLong(), source.sizeBytes - position).toInt()
                for (i in 0 until n) b[off + i] = ((position + i) % 251).toByte()
                position += n
                return n
            }
        }
    }

    private val loopback = InetAddress.getLoopbackAddress()

    private fun get(url: String, range: String? = null): Pair<Int, ByteArray> {
        val connection = URL(url).openConnection() as HttpURLConnection
        range?.let { connection.setRequestProperty("Range", it) }
        return try {
            connection.responseCode to connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    @Test fun `phone shares, protocol carries the url, tv reads with seeks`() {
        val size = 10_000_000L
        val registry = StreamRegistry()
        val http = MediaHttpServer(registry, SyntheticOpener(), AutoStopPolicy(), loopback)
        val httpPort = http.start()
        val token = registry.register(StreamSource("mem://film", "video/mp4", size, "film.mp4"))
        val url = "http://127.0.0.1:$httpPort${MediaHttpServer.pathFor(token, "film.mp4")}"

        val received = LinkedBlockingQueue<RemoteMessage>()
        val tv = RemoteReceiverServer("TV", { "654321" }, object : ReceiverListener {
            override fun onControllerConnected(deviceName: String) = Unit
            override fun onControllerDisconnected() = Unit
            override fun onCommand(command: RemoteMessage) { received += command }
        }, loopback)
        val tvPort = tv.start()
        val phone = RemoteControllerClient("Phone", object : ControllerListener {
            override fun onEvent(event: RemoteMessage) = Unit
            override fun onDisconnected(cause: IOException?) = Unit
        })
        try {
            assertThat(phone.connect("127.0.0.1", tvPort, "654321").accepted).isTrue()
            phone.send(Open(url, "Film", mediaFileId = 1, startPositionMs = 5_000))
            val open = received.poll(5, TimeUnit.SECONDS) as Open

            // The TV opens the file at the resume position, then seeks twice: header probe, jump to the middle, jump back.
            val (s1, head) = get(open.streamUrl, "bytes=0-1023")
            val (s2, middle) = get(open.streamUrl, "bytes=5000000-5000999")
            val (s3, tail) = get(open.streamUrl, "bytes=-500")
            assertThat(listOf(s1, s2, s3)).containsExactly(206, 206, 206)
            assertThat(head).isEqualTo(ByteArray(1024) { (it % 251).toByte() })
            assertThat(middle).isEqualTo(ByteArray(1000) { ((5_000_000 + it) % 251).toByte() })
            assertThat(tail).isEqualTo(ByteArray(500) { ((size - 500 + it) % 251).toByte() })
        } finally {
            phone.close(); tv.stop(); http.stop()
        }
    }

    @Test fun `a viewer that hangs up mid stream does not leave the server busy`() {
        val registry = StreamRegistry()
        val policy = AutoStopPolicy(idleTimeoutMs = 60_000)
        val http = MediaHttpServer(registry, SyntheticOpener(), policy, loopback)
        val port = http.start()
        val token = registry.register(StreamSource("mem://big", "video/mp4", 2_000_000_000L, "big.mp4"))
        try {
            Socket("127.0.0.1", port).use { socket ->
                socket.getOutputStream().write("GET /stream/$token HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                val input = socket.getInputStream()
                val buffer = ByteArray(64 * 1024)
                var read = 0
                while (read < 256 * 1024) read += input.read(buffer).also { check(it > 0) }
            } // closed after 256 KB of a 2 GB body
            val deadline = System.currentTimeMillis() + 5_000
            while (policy.activeConnections > 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertThat(policy.activeConnections).isEqualTo(0)
        } finally {
            http.stop()
        }
    }

    @Test fun `throughput is far above a 4k remux bitrate`() {
        val size = 192L * 1024 * 1024
        val registry = StreamRegistry()
        val http = MediaHttpServer(registry, SyntheticOpener(), AutoStopPolicy(), loopback)
        val port = http.start()
        val token = registry.register(StreamSource("mem://uhd", "video/mp4", size, "uhd.mp4"))
        try {
            val connection = URL("http://127.0.0.1:$port/stream/$token").openConnection() as HttpURLConnection
            val started = System.nanoTime()
            var total = 0L
            connection.inputStream.use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                }
            }
            val seconds = (System.nanoTime() - started) / 1e9
            val mbitPerSecond = total * 8 / 1e6 / seconds
            println("loopback throughput: %.0f Mbit/s over %.2f s".format(mbitPerSecond, seconds))
            assertThat(total).isEqualTo(size)
            // A 4K Blu-ray remux peaks around 128 Mbit/s: the server must not be the bottleneck by a wide margin.
            assertThat(mbitPerSecond).isGreaterThan(400.0)
        } finally {
            http.stop()
        }
    }
}
