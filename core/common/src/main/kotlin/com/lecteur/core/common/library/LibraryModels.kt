package com.lecteur.core.common.library

import kotlinx.serialization.Serializable

/**
 * Wire format of library sharing: the PC serves its library to the phones and TVs of the house, Plex style.
 *
 * JSON over HTTP on the local network. Additive changes (new optional fields) keep [LIBRARY_API_VERSION]; clients ignore
 * unknown keys. Artwork is referenced by opaque keys ([MovieDto.poster]...), never by a path on the PC's disk.
 */
const val LIBRARY_API_VERSION = 1

/** `GET /api/v1/info`, the only call that needs no credentials: enough to show the server and to sync clocks. */
@Serializable
data class ServerInfo(
    val serverId: String,
    val name: String,
    val apiVersion: Int = LIBRARY_API_VERSION,
    val serverTimeMs: Long,
    /** True while the PC shows a pairing code: a new device can pair only then. */
    val pairingOpen: Boolean = false
)

@Serializable
data class PairRequest(
    /** The 6-digit code shown on the PC. */
    val code: String,
    val deviceName: String,
    /** "phone", "tv"... shown in the PC's list of devices. */
    val platform: String = "android"
)

/** What a device keeps after pairing. [secret] never travels again: requests are signed with it. */
@Serializable
data class PairResponse(val serverId: String, val serverName: String, val deviceId: String, val secret: String)

@Serializable
data class ErrorDto(val error: String, val message: String)

@Serializable
data class WatchDto(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val completed: Boolean = false,
    val lastWatchedAt: Long = 0,
    val audioIndex: Int? = null,
    /** -1 = subtitles switched off. */
    val subtitleIndex: Int? = null,
    val audioDelayMs: Long = 0,
    val subtitleDelayMs: Long = 0
)

@Serializable
data class SubtitleDto(
    val index: Int,
    val language: String? = null,
    val label: String? = null,
    val forced: Boolean = false,
    val mimeType: String
)

@Serializable
data class FileDto(
    val id: Long,
    val fileName: String,
    val sizeBytes: Long,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** HdrType name ("HDR10", "DOLBY_VISION"...), null for SDR. */
    val hdr: String? = null,
    val videoCodec: String? = null,
    /** False while the disk holding the file is unplugged. */
    val available: Boolean = true,
    val watch: WatchDto? = null,
    /** External subtitle files next to the video (embedded tracks come with the stream). */
    val subtitles: List<SubtitleDto> = emptyList(),
    val container: String? = null,
    /** The best audio track, as the detail pages show it: "TrueHD 7.1", "Dolby Digital 5.1"... */
    val audio: String? = null,
    val audioLanguages: List<String> = emptyList(),
    /** Embedded and external subtitle languages. */
    val subtitleLanguages: List<String> = emptyList()
)

/** Someone in a title's cast or crew. [profile] is an artwork key, like posters. */
@Serializable
data class CreditDto(
    val personId: Long,
    val name: String,
    val role: String? = null,
    val profile: String? = null,
    val director: Boolean = false
)

@Serializable
data class MovieDto(
    val id: Long,
    val title: String,
    val year: Int? = null,
    val overview: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val rating: Float? = null,
    val runtimeMinutes: Int? = null,
    val genres: List<String> = emptyList(),
    val addedAt: Long = 0,
    val files: List<FileDto> = emptyList(),
    val originalTitle: String? = null,
    val releaseDate: String? = null,
    val certification: String? = null,
    val tmdbId: Long? = null,
    val imdbId: String? = null,
    /** YouTube key of the trailer. */
    val trailerKey: String? = null,
    /** Name of the TMDB saga the film belongs to. */
    val collection: String? = null,
    val cast: List<CreditDto> = emptyList()
)

@Serializable
data class SeasonDto(val number: Int, val name: String? = null, val poster: String? = null)

@Serializable
data class EpisodeDto(
    val id: Long,
    val season: Int,
    val number: Int,
    val title: String? = null,
    val overview: String? = null,
    val still: String? = null,
    val files: List<FileDto> = emptyList(),
    val airDate: String? = null,
    val runtimeMinutes: Int? = null
)

@Serializable
data class SeriesDto(
    val id: Long,
    val title: String,
    val year: Int? = null,
    val overview: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val rating: Float? = null,
    val genres: List<String> = emptyList(),
    val addedAt: Long = 0,
    val seasons: List<SeasonDto> = emptyList(),
    /** Only the episodes the PC has a file for, in season/episode order. */
    val episodes: List<EpisodeDto> = emptyList(),
    val originalTitle: String? = null,
    val firstAirDate: String? = null,
    val status: String? = null,
    val certification: String? = null,
    val tmdbId: Long? = null,
    val imdbId: String? = null,
    val cast: List<CreditDto> = emptyList()
)

/** `GET /api/v1/catalog`: the whole shared library in one document (a few thousand titles stay well under a few MB). */
@Serializable
data class Catalog(
    val serverId: String,
    val serverName: String,
    val generatedAtMs: Long,
    val movies: List<MovieDto> = emptyList(),
    val series: List<SeriesDto> = emptyList(),
    /** Heights the PC can bring a file down to ([LibraryUrls.hls], [LibraryUrls.download]); empty: originals only. */
    val transcodeHeights: List<Int> = emptyList(),
    /** How the PC transcodes, for the device's settings: "NVIDIA NVENC", "Processeur"... */
    val transcoder: String? = null
)

/** A transcoded download not ready yet: the PC answers 202 with this, the device asks again a bit later. */
@Serializable
data class TranscodeProgressDto(
    /** 0..1, null while the PC has not started on it (another one first). */
    val progress: Float? = null,
    val queued: Boolean = false
)

/** `POST /api/v1/watch`: where a device stopped, so the PC (and every other device) resumes at the same place. */
@Serializable
data class WatchReport(
    val fileId: Long,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val audioIndex: Int? = null,
    val subtitleIndex: Int? = null,
    val audioDelayMs: Long = 0,
    val subtitleDelayMs: Long = 0
)

/** Bitrates of the smaller versions a PC makes, known to both sides: the PC encodes at them, devices estimate sizes from them. */
object TranscodeLevels {
    /** Kbit/s of the picture at that height (H.264). */
    fun videoKbps(height: Int): Int = when {
        height >= 1080 -> 6_000
        height >= 720 -> 3_500
        height >= 480 -> 1_600
        else -> 900
    }

    const val AUDIO_KBPS = 160

    /** About how big [durationMs] is at [height] (one audio track). */
    fun estimatedBytes(height: Int, durationMs: Long): Long = (videoKbps(height) + AUDIO_KBPS) * 1000L / 8 * (durationMs / 1000)
}

/** The file to play for an episode or a movie: available first, then the sharpest. */
fun List<FileDto>.best(): FileDto? =
    sortedWith(compareByDescending<FileDto> { it.available }.thenByDescending { it.height ?: 0 }.thenByDescending { it.sizeBytes }).firstOrNull()

val WatchDto.progress: Float get() = if (durationMs > 0 && !completed) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

/** A PC this device paired with, as the device stores it. [baseUrl] follows the PC when its address changes. */
@Serializable
data class PairedServer(
    val serverId: String,
    val name: String,
    val baseUrl: String,
    val deviceId: String,
    val secret: String,
    val pairedAt: Long = 0
) {
    val credentials: DeviceCredentials get() = DeviceCredentials(deviceId, secret)
}

object PairedServers {
    fun encode(servers: List<PairedServer>): String =
        LibraryServer.wire.encodeToString(kotlinx.serialization.builtins.ListSerializer(PairedServer.serializer()), servers)

    fun decode(text: String?): List<PairedServer> = if (text.isNullOrBlank()) emptyList() else runCatching {
        LibraryServer.wire.decodeFromString(kotlinx.serialization.builtins.ListSerializer(PairedServer.serializer()), text)
    }.getOrDefault(emptyList())
}
