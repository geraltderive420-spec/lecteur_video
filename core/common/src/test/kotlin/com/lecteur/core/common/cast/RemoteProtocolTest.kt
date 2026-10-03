package com.lecteur.core.common.cast

import com.google.common.truth.Truth.assertThat
import com.lecteur.core.common.cast.protocol.ControllerListener
import com.lecteur.core.common.cast.protocol.Decoded
import com.lecteur.core.common.cast.protocol.Ended
import com.lecteur.core.common.cast.protocol.Handshake
import com.lecteur.core.common.cast.protocol.Hello
import com.lecteur.core.common.cast.protocol.HelloRefusal
import com.lecteur.core.common.cast.protocol.MessageChannel
import com.lecteur.core.common.cast.protocol.Open
import com.lecteur.core.common.cast.protocol.PairingCode
import com.lecteur.core.common.cast.protocol.PeerRole
import com.lecteur.core.common.cast.protocol.Pause
import com.lecteur.core.common.cast.protocol.Ping
import com.lecteur.core.common.cast.protocol.Play
import com.lecteur.core.common.cast.protocol.Pong
import com.lecteur.core.common.cast.protocol.ReceiverAdvert
import com.lecteur.core.common.cast.protocol.ReceiverListener
import com.lecteur.core.common.cast.protocol.RemoteCodec
import com.lecteur.core.common.cast.protocol.RemoteControllerClient
import com.lecteur.core.common.cast.protocol.RemoteMessage
import com.lecteur.core.common.cast.protocol.RemoteProgress
import com.lecteur.core.common.cast.protocol.RemoteReceiverServer
import com.lecteur.core.common.cast.protocol.RemoteState
import com.lecteur.core.common.cast.protocol.RemoteStatus
import com.lecteur.core.common.cast.protocol.RemoteSubtitle
import com.lecteur.core.common.cast.protocol.RemoteTrack
import com.lecteur.core.common.cast.protocol.RequestState
import com.lecteur.core.common.cast.protocol.SeekBy
import com.lecteur.core.common.cast.protocol.SeekTo
import com.lecteur.core.common.cast.protocol.SelectAudio
import com.lecteur.core.common.cast.protocol.SelectSubtitle
import com.lecteur.core.common.cast.protocol.SetMuted
import com.lecteur.core.common.cast.protocol.SetSpeed
import com.lecteur.core.common.cast.protocol.SetVolume
import com.lecteur.core.common.cast.protocol.Stop
import com.lecteur.core.common.cast.protocol.Welcome
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Test

class RemoteCodecTest {

    private fun roundTrip(message: RemoteMessage) {
        val line = RemoteCodec.encode(message)
        assertThat(line).doesNotContain("\n")
        assertThat(RemoteCodec.decode(line)).isEqualTo(Decoded.Message(message))
    }

    @Test fun `every message survives a round trip`() {
        val messages = listOf(
            Hello(role = PeerRole.CONTROLLER, deviceName = "Pixel de Léa", pairingCode = "123456"),
            Welcome(accepted = true, version = 1, deviceName = "Salon"),
            Welcome(accepted = false, deviceName = "Salon", refusal = HelloRefusal.BAD_PAIRING_CODE),
            Open(
                streamUrl = "http://192.168.1.20:8123/stream/abc/film.mkv", title = "Film", mediaFileId = 42,
                startPositionMs = 61_000, audioIndex = 1, subtitleIndex = -1,
                subtitles = listOf(RemoteSubtitle("http://x/s.srt", "application/x-subrip", "fr", "Français", true))
            ),
            Play, Pause, Stop, RequestState,
            SeekTo(90_000), SeekBy(-10_000), SelectAudio(2), SelectSubtitle(-1), SetSpeed(1.5f),
            SetVolume(0.4f), SetMuted(true),
            RemoteState(
                status = RemoteStatus.READY, mediaFileId = 42, title = "Film", isPlaying = true, positionMs = 5,
                durationMs = 7_000_000, audio = listOf(RemoteTrack(0, "fr", "VF", "ac3", true)),
                subtitles = listOf(RemoteTrack(0, "en", null, "srt", false, isSupported = false)),
                errorMessage = "x", errorSuggestion = "y"
            ),
            RemoteProgress(42, 1000, 7000, 3000), RemoteProgress(null, 0, 0),
            Ended(42, 6_990_000, 7_000_000),
            Ping(7), Pong(7)
        )
        messages.forEach(::roundTrip)
    }

    @Test fun `wire format is stable`() {
        assertThat(RemoteCodec.encode(SeekTo(1500))).isEqualTo("""{"type":"seek_to","positionMs":1500}""")
        assertThat(RemoteCodec.encode(Play)).isEqualTo("""{"type":"play"}""")
        assertThat(RemoteCodec.encode(Ping(3))).isEqualTo("""{"type":"ping","nonce":3}""")
    }

    @Test fun `optional fields can be omitted by an older sender`() {
        val decoded = RemoteCodec.decode("""{"type":"open","streamUrl":"http://h/stream/t","mediaFileId":9}""")
        assertThat(decoded).isEqualTo(Decoded.Message(Open(streamUrl = "http://h/stream/t", mediaFileId = 9)))
    }

    @Test fun `unknown fields from a newer sender are ignored`() {
        val decoded = RemoteCodec.decode("""{"type":"seek_by","deltaMs":5000,"futureField":{"a":1}}""")
        assertThat(decoded).isEqualTo(Decoded.Message(SeekBy(5000)))
    }

    @Test fun `unknown message type is reported, not fatal`() {
        assertThat(RemoteCodec.decode("""{"type":"set_brightness","level":1}""")).isEqualTo(Decoded.UnknownType("set_brightness"))
    }

    @Test fun `malformed input is reported`() {
        assertThat(RemoteCodec.decode("not json")).isInstanceOf(Decoded.Malformed::class.java)
        assertThat(RemoteCodec.decode("[1,2]")).isInstanceOf(Decoded.Malformed::class.java)
        assertThat(RemoteCodec.decode("""{"nothing":1}""")).isInstanceOf(Decoded.Malformed::class.java)
        // Known type with a missing required field.
        assertThat(RemoteCodec.decode("""{"type":"seek_to"}""")).isInstanceOf(Decoded.Malformed::class.java)
    }

    @Test fun `channel frames one message per line and survives non ascii text`() {
        val out = ByteArrayOutputStream()
        val sender = MessageChannel(ByteArrayInputStream(ByteArray(0)), out)
        sender.send(Open("http://h/s", title = "Amélie — « Poulain »", mediaFileId = 1))
        sender.send(Pause)
        val receiver = MessageChannel(ByteArrayInputStream(out.toByteArray()), ByteArrayOutputStream())
        assertThat((receiver.receive() as Decoded.Message).message).isEqualTo(Open("http://h/s", title = "Amélie — « Poulain »", mediaFileId = 1))
        assertThat(receiver.receive()).isEqualTo(Decoded.Message(Pause))
        assertThat(receiver.receive()).isNull()
    }

    @Test fun `oversized line is rejected without exhausting memory`() {
        val huge = "x".repeat(MessageChannel.MAX_LINE_CHARS + 100) + "\n" + """{"type":"play"}""" + "\n"
        val channel = MessageChannel(ByteArrayInputStream(huge.toByteArray()), ByteArrayOutputStream())
        assertThat(channel.receive()).isInstanceOf(Decoded.Malformed::class.java)
        assertThat(channel.receive()).isEqualTo(Decoded.Message(Play))
    }
}

class HandshakeTest {
    private val hello = Hello(role = PeerRole.CONTROLLER, deviceName = "Phone", pairingCode = "123456")

    @Test fun `version negotiation picks the highest common version`() {
        assertThat(Handshake.negotiate(3, 1, 2, 1)).isEqualTo(2)
        assertThat(Handshake.negotiate(2, 1, 3, 1)).isEqualTo(2)
        assertThat(Handshake.negotiate(2, 1, 2, 2)).isEqualTo(2)
    }

    @Test fun `no overlap means no version`() {
        assertThat(Handshake.negotiate(1, 1, 3, 2)).isNull() // peer dropped v1
        assertThat(Handshake.negotiate(3, 3, 2, 1)).isNull() // we dropped v2
    }

    @Test fun `matching code is accepted`() {
        val w = Handshake.answer(hello, "TV", "123456")
        assertThat(w.accepted).isTrue()
        assertThat(w.version).isEqualTo(1)
        assertThat(w.deviceName).isEqualTo("TV")
    }

    @Test fun `wrong or missing code is refused`() {
        assertThat(Handshake.answer(hello.copy(pairingCode = "000000"), "TV", "123456").refusal).isEqualTo(HelloRefusal.BAD_PAIRING_CODE)
        assertThat(Handshake.answer(hello.copy(pairingCode = null), "TV", "123456").refusal).isEqualTo(HelloRefusal.BAD_PAIRING_CODE)
    }

    @Test fun `pairing not enforced when no code is set`() =
        assertThat(Handshake.answer(hello.copy(pairingCode = null), "TV", null).accepted).isTrue()

    @Test fun `future only controller is refused for version`() {
        val w = Handshake.answer(hello.copy(version = 5, minVersion = 4), "TV", "123456")
        assertThat(w.accepted).isFalse()
        assertThat(w.refusal).isEqualTo(HelloRefusal.INCOMPATIBLE_VERSION)
    }

    @Test fun `a receiver cannot connect to a receiver`() =
        assertThat(Handshake.answer(hello.copy(role = PeerRole.RECEIVER), "TV", "123456").refusal).isEqualTo(HelloRefusal.WRONG_ROLE)

    @Test fun `pairing code is six digits and tolerant of typed spaces`() {
        val code = PairingCode.generate()
        assertThat(code).matches("[0-9]{6}")
        assertThat(PairingCode.matches("123456", " 123 456 ")).isTrue()
        assertThat(PairingCode.matches("123456", "12345")).isFalse()
        assertThat(PairingCode.matches("123456", null)).isFalse()
    }
}

class DiscoveryTest {
    @Test fun `advert round trips through TXT attributes`() {
        val advert = ReceiverAdvert("Salon", 5555)
        assertThat(ReceiverAdvert.parse(advert.serviceName(), 5555, advert.toTxt())).isEqualTo(advert)
    }

    @Test fun `name falls back to the service name when TXT lacks it`() {
        assertThat(ReceiverAdvert.parse("LecteurMedia-Chambre", 1234, mapOf("v" to "1"))).isEqualTo(ReceiverAdvert("Chambre", 1234))
    }

    @Test fun `unusable services are rejected`() {
        assertThat(ReceiverAdvert.parse("x", 0, mapOf("v" to "1", "name" to "A"))).isNull()
        assertThat(ReceiverAdvert.parse("x", 70000, mapOf("v" to "1", "name" to "A"))).isNull()
        assertThat(ReceiverAdvert.parse("x", 80, mapOf("name" to "A"))).isNull()
        assertThat(ReceiverAdvert.parse("x", 80, mapOf("v" to "0", "name" to "A"))).isNull()
        assertThat(ReceiverAdvert.parse(null, 80, mapOf("v" to "1"))).isNull()
    }

    @Test fun `service name drops characters NSD rejects`() {
        assertThat(ReceiverAdvert("Salon.TV/4K!", 1).serviceName()).isEqualTo("LecteurMedia-SalonTV4K")
        assertThat(ReceiverAdvert("x".repeat(100), 1).serviceName()).hasLength(ReceiverAdvert.SERVICE_PREFIX.length + 40)
        assertThat(ReceiverAdvert.SERVICE_TYPE).isEqualTo("_lecteurmedia._tcp.")
    }
}

/** Real sockets on loopback: handshake, commands, events, replacement, loss. */
class RemoteTransportTest {
    private class RecordingReceiver : ReceiverListener {
        val commands = LinkedBlockingQueue<RemoteMessage>()
        val connected = LinkedBlockingQueue<String>()
        val disconnected = CountDownLatch(1)
        override fun onControllerConnected(deviceName: String) { connected += deviceName }
        override fun onControllerDisconnected() = disconnected.countDown()
        override fun onCommand(command: RemoteMessage) { commands += command }
    }

    private class RecordingController : ControllerListener {
        companion object { val CLOSED = Any() }
        val events = LinkedBlockingQueue<RemoteMessage>()
        val disconnected = LinkedBlockingQueue<Any>()
        override fun onEvent(event: RemoteMessage) { events += event }
        override fun onDisconnected(cause: IOException?) { disconnected += (cause ?: CLOSED) }
    }

    private val receiverListener = RecordingReceiver()
    private val server = RemoteReceiverServer("Salon", { "123456" }, receiverListener, InetAddress.getLoopbackAddress())
    private val clients = CopyOnWriteArrayList<RemoteControllerClient>()
    private val port = server.start()

    @After fun tearDown() {
        clients.forEach { it.close() }
        server.stop()
    }

    private fun client(name: String = "Phone", listener: ControllerListener = RecordingController(), ping: Long = 5_000) =
        RemoteControllerClient(name, listener, pingIntervalMs = ping).also { clients += it }

    private fun <T> LinkedBlockingQueue<T>.next(): T = poll(5, TimeUnit.SECONDS) ?: error("timeout")

    @Test fun `commands reach the receiver in order and events reach the controller`() {
        val events = RecordingController()
        val c = client(listener = events)
        val welcome = c.connect("127.0.0.1", port, "123456")
        assertThat(welcome).isEqualTo(Welcome(accepted = true, version = 1, deviceName = "Salon"))
        assertThat(receiverListener.connected.next()).isEqualTo("Phone")

        val open = Open("http://h/stream/t", mediaFileId = 3, startPositionMs = 1000)
        c.send(open); c.send(Pause); c.send(SeekTo(5000)); c.send(SetVolume(0.5f))
        assertThat(receiverListener.commands.next()).isEqualTo(open)
        assertThat(receiverListener.commands.next()).isEqualTo(Pause)
        assertThat(receiverListener.commands.next()).isEqualTo(SeekTo(5000))
        assertThat(receiverListener.commands.next()).isEqualTo(SetVolume(0.5f))

        assertThat(server.send(RemoteState(status = RemoteStatus.READY, isPlaying = true))).isTrue()
        assertThat(server.send(RemoteProgress(3, 1200, 9000))).isTrue()
        assertThat(events.events.next()).isEqualTo(RemoteState(status = RemoteStatus.READY, isPlaying = true))
        assertThat(events.events.next()).isEqualTo(RemoteProgress(3, 1200, 9000))
    }

    @Test fun `wrong pairing code is refused and nothing is delivered`() {
        val c = client()
        val welcome = c.connect("127.0.0.1", port, "999999")
        assertThat(welcome.accepted).isFalse()
        assertThat(welcome.refusal).isEqualTo(HelloRefusal.BAD_PAIRING_CODE)
        assertThat(receiverListener.connected.poll(300, TimeUnit.MILLISECONDS)).isNull()
        assertThat(server.connectedController).isNull()
        assertThat(server.send(Play)).isFalse()
    }

    @Test fun `events from a controller are not treated as commands`() {
        val c = client()
        c.connect("127.0.0.1", port, "123456")
        receiverListener.connected.next()
        c.send(RemoteState()) // a controller has no business sending state
        c.send(Play)
        assertThat(receiverListener.commands.next()).isEqualTo(Play)
        assertThat(receiverListener.commands.poll(200, TimeUnit.MILLISECONDS)).isNull()
    }

    @Test fun `pings are answered and do not surface as events`() {
        val events = RecordingController()
        val c = client(listener = events, ping = 20)
        c.connect("127.0.0.1", port, "123456")
        Thread.sleep(200)
        c.send(Stop)
        assertThat(receiverListener.commands.next()).isEqualTo(Stop)
        assertThat(events.events.poll(100, TimeUnit.MILLISECONDS)).isNull()
        assertThat(events.disconnected.isEmpty()).isTrue()
    }

    @Test fun `a second controller replaces the first`() {
        val first = RecordingController()
        val a = client("A", first)
        a.connect("127.0.0.1", port, "123456")
        assertThat(receiverListener.connected.next()).isEqualTo("A")
        val b = client("B")
        b.connect("127.0.0.1", port, "123456")
        assertThat(receiverListener.connected.next()).isEqualTo("B")
        assertThat(first.disconnected.next()).isNotNull() // closed by the receiver
        b.send(Play)
        assertThat(receiverListener.commands.next()).isEqualTo(Play)
        assertThat(server.connectedController).isEqualTo("B")
    }

    @Test fun `controller learns when the receiver goes away and receiver learns when the controller does`() {
        val events = RecordingController()
        val c = client(listener = events)
        c.connect("127.0.0.1", port, "123456")
        receiverListener.connected.next()
        c.close()
        assertThat(receiverListener.disconnected.await(5, TimeUnit.SECONDS)).isTrue()

        val events2 = RecordingController()
        val c2 = client(listener = events2)
        c2.connect("127.0.0.1", port, "123456")
        server.stop()
        events2.disconnected.next()
        assertThat(c2.send(Play)).isFalse()
    }

    @Test fun `receiver drops a controller that goes silent`() {
        val quiet = RemoteReceiverServer("TV2", { null }, receiverListener, InetAddress.getLoopbackAddress(), readTimeoutMs = 150)
        val p = quiet.start()
        try {
            val c = client(ping = 60_000)
            c.connect("127.0.0.1", p, null)
            assertThat(receiverListener.disconnected.await(5, TimeUnit.SECONDS)).isTrue()
        } finally {
            quiet.stop()
        }
    }

    @Test fun `unknown message from a newer controller does not break the session`() {
        val raw = java.net.Socket("127.0.0.1", port)
        raw.use {
            val ch = MessageChannel(it.getInputStream(), it.getOutputStream())
            ch.send(Hello(role = PeerRole.CONTROLLER, deviceName = "Raw", pairingCode = "123456"))
            assertThat((ch.receive() as Decoded.Message).message).isInstanceOf(Welcome::class.java)
            it.getOutputStream().write("""{"type":"from_the_future","x":1}""".toByteArray() + "\n".toByteArray())
            ch.send(RequestState)
            assertThat(receiverListener.commands.next()).isEqualTo(RequestState)
        }
    }
}
