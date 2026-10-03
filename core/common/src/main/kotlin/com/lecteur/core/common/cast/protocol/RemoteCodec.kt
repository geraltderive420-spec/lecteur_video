package com.lecteur.core.common.cast.protocol

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What reading one line produced. A peer on a newer protocol may send things this build does not know. */
sealed interface Decoded {
    data class Message(val message: RemoteMessage) : Decoded

    /** Well-formed JSON with a `type` this build does not know: skip it, keep the connection. */
    data class UnknownType(val type: String) : Decoded

    /** Not a protocol message at all. */
    data class Malformed(val reason: String) : Decoded
}

object RemoteCodec {
    private val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = false
    }

    /** One message as a single line without the terminator. */
    fun encode(message: RemoteMessage): String = json.encodeToString(RemoteMessage.serializer(), message)

    fun decode(line: String): Decoded {
        val tree = try {
            json.parseToJsonElement(line).jsonObject
        } catch (e: SerializationException) {
            return Decoded.Malformed(e.message ?: "invalid JSON")
        } catch (e: IllegalArgumentException) {
            return Decoded.Malformed("not a JSON object")
        }
        val type = typeOf(tree) ?: return Decoded.Malformed("missing type")
        return try {
            Decoded.Message(json.decodeFromJsonElement(RemoteMessage.serializer(), tree))
        } catch (e: SerializationException) {
            if (type in KNOWN_TYPES) Decoded.Malformed("bad $type: ${e.message}") else Decoded.UnknownType(type)
        } catch (e: IllegalArgumentException) {
            Decoded.Malformed("bad $type: ${e.message}")
        }
    }

    private fun typeOf(tree: JsonObject): String? = tree["type"]?.jsonPrimitive?.contentOrNull

    private val KNOWN_TYPES = setOf(
        "hello", "welcome", "open", "play", "pause", "stop", "seek_to", "seek_by", "select_audio", "select_subtitle",
        "set_speed", "set_volume", "set_muted", "request_state", "state", "progress", "ended", "ping", "pong"
    )
}

/** Newline-delimited framing over a byte stream. Writes are serialized; reads belong to one thread. */
class MessageChannel(input: InputStream, private val output: OutputStream) {
    private val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))

    @Throws(IOException::class)
    fun send(message: RemoteMessage) {
        val bytes = (RemoteCodec.encode(message) + "\n").toByteArray(Charsets.UTF_8)
        synchronized(output) {
            output.write(bytes)
            output.flush()
        }
    }

    /** Blocks for the next line; null when the peer closed the stream. Oversized lines are reported as malformed. */
    @Throws(IOException::class)
    fun receive(): Decoded? {
        val line = readBoundedLine() ?: return null
        return if (line.length > MAX_LINE_CHARS) Decoded.Malformed("line too long") else RemoteCodec.decode(line)
    }

    private fun readBoundedLine(): String? {
        val sb = StringBuilder()
        var tooLong = false
        while (true) {
            val c = reader.read()
            if (c < 0) return if (sb.isEmpty() && !tooLong) null else sb.toString()
            if (c == '\n'.code) return if (tooLong) "x".repeat(MAX_LINE_CHARS + 1) else sb.toString()
            if (sb.length >= MAX_LINE_CHARS) tooLong = true else sb.append(c.toChar())
        }
    }

    companion object {
        const val MAX_LINE_CHARS = 256 * 1024
    }
}
