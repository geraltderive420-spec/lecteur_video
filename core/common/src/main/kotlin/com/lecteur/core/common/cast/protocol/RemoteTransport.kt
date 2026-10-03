package com.lecteur.core.common.cast.protocol

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Messages a controller may send once connected; anything else from a controller is ignored. */
private fun RemoteMessage.isCommand(): Boolean = when (this) {
    is Open, Play, Pause, Stop, is SeekTo, is SeekBy, is SelectAudio, is SelectSubtitle, is SetSpeed, is SetVolume,
    is SetMuted, RequestState -> true

    else -> false
}

interface ReceiverListener {
    fun onControllerConnected(deviceName: String)
    fun onControllerDisconnected()

    /** Called on the connection thread, in order. */
    fun onCommand(command: RemoteMessage)
}

/**
 * The TV side of companion mode: accepts one controller at a time (a new one replaces the old: the user picked this
 * TV from another phone), checks the handshake, forwards commands, answers pings.
 */
class RemoteReceiverServer(
    private val deviceName: String,
    private val pairingCode: () -> String?,
    private val listener: ReceiverListener,
    private val bindAddress: InetAddress? = null,
    private val requestedPort: Int = 0,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var current: Connection? = null
    private val lock = Any()

    private inner class Connection(val socket: Socket, val channel: MessageChannel, val controllerName: String)

    val port: Int get() = serverSocket?.localPort ?: -1
    val isRunning: Boolean get() = running.get()

    @Synchronized
    fun start(): Int {
        check(!running.get()) { "already running" }
        val server = ServerSocket(requestedPort, 4, bindAddress)
        serverSocket = server
        running.set(true)
        Thread({ acceptLoop(server) }, "remote-receiver-accept").apply { isDaemon = true }.start()
        return server.localPort
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        synchronized(lock) { current?.socket?.let { runCatching { it.close() } } }
    }

    /** Sends an event to the connected controller; false when nobody is connected or the write failed. */
    fun send(message: RemoteMessage): Boolean {
        val connection = synchronized(lock) { current } ?: return false
        return try {
            connection.channel.send(message)
            true
        } catch (_: IOException) {
            runCatching { connection.socket.close() }
            false
        }
    }

    val connectedController: String? get() = synchronized(lock) { current?.controllerName }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get()) {
            val socket = try {
                server.accept()
            } catch (_: SocketException) {
                return
            } catch (_: IOException) {
                continue
            }
            Thread({ serve(socket) }, "remote-receiver-conn").apply { isDaemon = true }.start()
        }
    }

    private fun serve(socket: Socket) {
        var connection: Connection? = null
        try {
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            socket.tcpNoDelay = true
            val channel = MessageChannel(socket.getInputStream(), socket.getOutputStream())
            val hello = (channel.receive() as? Decoded.Message)?.message as? Hello ?: return
            val welcome = Handshake.answer(hello, deviceName, pairingCode())
            if (!welcome.accepted) {
                channel.send(welcome)
                return
            }

            // Registered before the controller can see the Welcome, so a stop() or send() right after connect sees it.
            connection = Connection(socket, channel, hello.deviceName)
            val previous = synchronized(lock) { current.also { current = connection } }
            previous?.let { runCatching { it.socket.close() } }
            channel.send(welcome)
            socket.soTimeout = readTimeoutMs
            listener.onControllerConnected(hello.deviceName)
            pump(connection)
        } catch (_: IOException) {
            // Peer gone or silent: handled in finally.
        } finally {
            runCatching { socket.close() }
            val wasCurrent = synchronized(lock) { (current === connection && connection != null).also { if (it) current = null } }
            if (wasCurrent) listener.onControllerDisconnected()
        }
    }

    private fun pump(connection: Connection) {
        while (running.get()) {
            val decoded = try {
                connection.channel.receive()
            } catch (_: SocketTimeoutException) {
                return
            } ?: return
            val message = (decoded as? Decoded.Message)?.message ?: continue
            when {
                message is Ping -> connection.channel.send(Pong(message.nonce))
                message.isCommand() -> listener.onCommand(message)
            }
        }
    }

    companion object {
        const val DEFAULT_READ_TIMEOUT_MS = 20_000
        private const val HANDSHAKE_TIMEOUT_MS = 5_000
    }
}

interface ControllerListener {
    /** Events from the TV: [RemoteState], [RemoteProgress], [Ended]. Called on the reader thread, in order. */
    fun onEvent(event: RemoteMessage)

    /** The connection ended, whether closed by us, by the TV or by a network loss ([cause] null when we closed it). */
    fun onDisconnected(cause: IOException?)
}

/** The phone side: connects to a receiver found through NSD, performs the handshake, then sends commands. */
class RemoteControllerClient(
    private val deviceName: String,
    private val listener: ControllerListener,
    private val pingIntervalMs: Long = DEFAULT_PING_INTERVAL_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS
) {
    private var socket: Socket? = null
    private var channel: MessageChannel? = null
    private var heartbeat: ScheduledExecutorService? = null
    private val closed = AtomicBoolean(false)
    private val nonce = AtomicLong(0)

    /** Blocking connect + handshake. Returns the TV's answer; when it refuses the connection is already closed. */
    @Throws(IOException::class)
    @Synchronized
    fun connect(host: String, port: Int, pairingCode: String?, connectTimeoutMs: Int = 5_000): Welcome {
        check(socket == null) { "already connected" }
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            s.tcpNoDelay = true
            s.soTimeout = connectTimeoutMs
            val ch = MessageChannel(s.getInputStream(), s.getOutputStream())
            ch.send(Hello(role = PeerRole.CONTROLLER, deviceName = deviceName, pairingCode = pairingCode))
            val welcome = (ch.receive() as? Decoded.Message)?.message as? Welcome
                ?: throw IOException("the receiver did not answer the handshake")
            if (!welcome.accepted) {
                s.close()
                return welcome
            }
            s.soTimeout = readTimeoutMs
            socket = s
            channel = ch
            Thread({ readLoop(ch) }, "remote-controller-read").apply { isDaemon = true }.start()
            heartbeat = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "remote-controller-ping").apply { isDaemon = true } }
                .also { it.scheduleWithFixedDelay(::ping, pingIntervalMs, pingIntervalMs, TimeUnit.MILLISECONDS) }
            return welcome
        } catch (e: IOException) {
            runCatching { s.close() }
            throw e
        }
    }

    /** False when not connected or the write failed (the read loop will then report the disconnection). */
    fun send(command: RemoteMessage): Boolean = try {
        channel?.send(command) != null
    } catch (_: IOException) {
        runCatching { socket?.close() }
        false
    }

    @Synchronized
    fun close() {
        if (closed.getAndSet(true)) return
        heartbeat?.shutdownNow()
        runCatching { socket?.close() }
    }

    private fun ping() {
        send(Ping(nonce.incrementAndGet()))
    }

    private fun readLoop(ch: MessageChannel) {
        var cause: IOException? = null
        try {
            while (true) {
                val decoded = ch.receive() ?: break
                val message = (decoded as? Decoded.Message)?.message ?: continue
                if (message !is Pong) listener.onEvent(message)
            }
        } catch (e: IOException) {
            cause = e
        }
        heartbeat?.shutdownNow()
        runCatching { socket?.close() }
        listener.onDisconnected(if (closed.get()) null else cause)
    }

    companion object {
        const val DEFAULT_PING_INTERVAL_MS = 5_000L
        const val DEFAULT_READ_TIMEOUT_MS = 20_000
    }
}
