package com.lecteur.core.common.library

import kotlinx.serialization.KSerializer
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** Why a call to a PC failed, in terms the screens can explain. */
class LibraryException(val kind: Kind, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Kind {
        /** No answer: PC off, app closed, sharing disabled, another network, firewall. */
        UNREACHABLE,

        /** The PC no longer knows this device (revoked) or refused its signature. */
        UNAUTHORIZED,
        BAD_CODE,
        PAIRING_CLOSED,
        TOO_MANY_ATTEMPTS,

        /** Something that is not our server answered, or a newer one this build cannot read. */
        PROTOCOL
    }
}

/**
 * Talks to one PC: pairing, catalog, watch state. Plain [HttpURLConnection] (on Android and on the JVM alike), blocking:
 * call it from a background dispatcher. [credentials] null allows only [info] and [pair].
 */
class LibraryClient(
    baseUrl: String,
    private val credentials: DeviceCredentials? = null,
    private val connectTimeoutMs: Int = 4_000,
    private val readTimeoutMs: Int = 15_000,
    private val clock: () -> Long = System::currentTimeMillis
) {
    val baseUrl: String = baseUrl.trimEnd('/')

    /** Server time minus local time, learnt from [info]: signatures stay valid on a phone whose clock is off. */
    @Volatile
    private var clockOffsetMs: Long? = null

    fun info(): ServerInfo {
        val sentAt = clock()
        val info = call("GET", "/api/v1/info", null, ServerInfo.serializer(), signed = false)
        // Half the round trip is a good enough guess of when the server read its clock
        clockOffsetMs = info.serverTimeMs - (sentAt + (clock() - sentAt) / 2)
        if (info.apiVersion < 1) throw LibraryException(LibraryException.Kind.PROTOCOL, "Version du serveur non prise en charge.")
        return info
    }

    fun pair(code: String, deviceName: String, platform: String): PairResponse {
        val body = LibraryServer.wire.encodeToString(PairRequest.serializer(), PairRequest(code.filter(Char::isDigit), deviceName, platform))
        return call("POST", "/api/v1/pair", body.toByteArray(), PairResponse.serializer(), signed = false)
    }

    fun catalog(): Catalog = call("GET", "/api/v1/catalog", null, Catalog.serializer(), signed = true)

    fun watch(fileId: Long): WatchDto = call("GET", "/api/v1/watch/$fileId", null, WatchDto.serializer(), signed = true)

    fun report(report: WatchReport) {
        val body = LibraryServer.wire.encodeToString(WatchReport.serializer(), report).toByteArray()
        call<Unit>("POST", "/api/v1/watch", body, null, signed = true)
    }

    fun streamUrl(file: FileDto): String = LibraryUrls.stream(baseUrl, requireCredentials(), file.id, file.fileName)

    /** The file brought down to [height] lines by the PC as it plays; for a smaller file, not for a format the device lacks. */
    fun hlsUrl(file: FileDto, height: Int): String = LibraryUrls.hls(baseUrl, requireCredentials(), file.id, height)

    /** A copy of the file at [height] lines: 202 + [TranscodeProgressDto] until the PC has made it, then the file. */
    fun downloadUrl(file: FileDto, height: Int, fileName: String): String = LibraryUrls.download(baseUrl, requireCredentials(), file.id, height, fileName)

    fun subtitleUrl(file: FileDto, subtitle: SubtitleDto): String =
        LibraryUrls.subtitle(baseUrl, requireCredentials(), file.id, subtitle.index, "sub." + extensionOf(subtitle.mimeType))

    fun artUrl(key: String?): String? = key?.let { LibraryUrls.art(baseUrl, requireCredentials(), it) }

    private fun <T> call(method: String, path: String, body: ByteArray?, serializer: KSerializer<T>?, signed: Boolean): T {
        if (signed && clockOffsetMs == null) runCatching { info() }
        val connection = try {
            (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                useCaches = false
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Encoding", "gzip")
                if (signed) {
                    val creds = requireCredentials()
                    val time = clock() + (clockOffsetMs ?: 0)
                    setRequestProperty(LibrarySignature.HEADER_DEVICE, creds.deviceId)
                    setRequestProperty(LibrarySignature.HEADER_TIME, time.toString())
                    setRequestProperty(LibrarySignature.HEADER_SIGNATURE, LibrarySignature.request(creds.secret, method, path, time, body ?: ByteArray(0)))
                }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setFixedLengthStreamingMode(body.size)
                }
            }
        } catch (e: IOException) {
            throw unreachable(e)
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code !in 200..299) throw failure(code, connection)
            @Suppress("UNCHECKED_CAST")
            if (serializer == null || code == 204) return Unit as T
            val text = decoded(connection, connection.inputStream).use { it.readBytes() }.toString(Charsets.UTF_8)
            return try {
                LibraryServer.wire.decodeFromString(serializer, text)
            } catch (e: Exception) {
                throw LibraryException(LibraryException.Kind.PROTOCOL, "Réponse inattendue du PC.", e)
            }
        } catch (e: LibraryException) {
            throw e
        } catch (e: IOException) {
            throw unreachable(e)
        } finally {
            connection.disconnect()
        }
    }

    private fun failure(code: Int, connection: HttpURLConnection): LibraryException {
        val error = runCatching {
            connection.errorStream?.let { decoded(connection, it) }?.use { it.readBytes() }?.toString(Charsets.UTF_8)
                ?.let { LibraryServer.wire.decodeFromString(ErrorDto.serializer(), it) }
        }.getOrNull()
        val kind = when {
            code == 429 -> LibraryException.Kind.TOO_MANY_ATTEMPTS
            error?.error == "bad_code" -> LibraryException.Kind.BAD_CODE
            error?.error == "pairing_closed" -> LibraryException.Kind.PAIRING_CLOSED
            code == 401 || code == 403 -> LibraryException.Kind.UNAUTHORIZED
            else -> LibraryException.Kind.PROTOCOL
        }
        // A clock refusal is cured by asking the server's time again
        if (error?.error == "clock") clockOffsetMs = null
        return LibraryException(kind, error?.message ?: "Le PC a répondu $code.")
    }

    private fun decoded(connection: HttpURLConnection, stream: InputStream): InputStream =
        if (connection.contentEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(stream) else stream

    private fun unreachable(e: IOException) =
        LibraryException(LibraryException.Kind.UNREACHABLE, "Le PC ne répond pas. Vérifiez qu'il est allumé, que Lecteur Média y est ouvert avec le partage activé, et que vous êtes sur le même réseau.", e)

    private fun requireCredentials(): DeviceCredentials =
        credentials ?: throw LibraryException(LibraryException.Kind.UNAUTHORIZED, "Cet appareil n'est pas associé à ce PC.")

    companion object {
        /** The body of a 202 from a [downloadUrl] (the PC is still converting); null when it is not one. */
        fun progressOf(body: String): TranscodeProgressDto? =
            runCatching { LibraryServer.wire.decodeFromString(TranscodeProgressDto.serializer(), body) }.getOrNull()

        /** The message of an error answer, to show as is. */
        fun errorMessageOf(body: String): String? = runCatching { LibraryServer.wire.decodeFromString(ErrorDto.serializer(), body).message }.getOrNull()
    }

    private fun extensionOf(mime: String): String = when (mime) {
        "application/x-subrip" -> "srt"
        "text/vtt" -> "vtt"
        "text/x-ssa" -> "ass"
        else -> "srt"
    }
}
