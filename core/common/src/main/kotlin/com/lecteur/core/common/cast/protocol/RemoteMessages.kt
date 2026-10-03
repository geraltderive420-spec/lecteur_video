package com.lecteur.core.common.cast.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire protocol between the phone (controller) and the Android TV app (receiver) of companion mode.
 *
 * Messages are single-line JSON objects with a `type` discriminator (see [RemoteCodec]). The protocol is versioned:
 * additive changes (new optional fields, new message types) keep [PROTOCOL_VERSION]; anything an old peer cannot
 * ignore bumps it, and [Handshake] negotiates the highest version both sides speak.
 */
const val PROTOCOL_VERSION = 1

/** Oldest protocol version this build still understands. */
const val MIN_PROTOCOL_VERSION = 1

@Serializable
sealed interface RemoteMessage

enum class PeerRole { CONTROLLER, RECEIVER }

enum class HelloRefusal {
    /** No protocol version in common. */
    INCOMPATIBLE_VERSION,

    /** The pairing code shown on the TV was missing or wrong. */
    BAD_PAIRING_CODE,

    /** The peer did not announce the role the connection expects. */
    WRONG_ROLE
}

// ---- Handshake -----------------------------------------------------------------------------------------------

@Serializable
@SerialName("hello")
data class Hello(
    val version: Int = PROTOCOL_VERSION,
    val minVersion: Int = MIN_PROTOCOL_VERSION,
    val role: PeerRole,
    val deviceName: String,
    /** The code displayed on the TV, typed on the phone. Only the controller sends it. */
    val pairingCode: String? = null
) : RemoteMessage

@Serializable
@SerialName("welcome")
data class Welcome(
    val accepted: Boolean,
    /** The version both sides will use from now on; 0 when refused. */
    val version: Int = 0,
    val deviceName: String,
    val refusal: HelloRefusal? = null
) : RemoteMessage

// ---- Commands: controller -> receiver ------------------------------------------------------------------------

@Serializable
data class RemoteSubtitle(
    val url: String,
    val mimeType: String,
    val language: String? = null,
    val label: String? = null,
    val isForced: Boolean = false
)

@Serializable
@SerialName("open")
data class Open(
    /** HTTP URL on the phone's local server (token included) the TV streams the file from. */
    val streamUrl: String,
    val title: String? = null,
    /** Row of media_files on the phone; echoed back in state and progress so the phone can save the position. */
    val mediaFileId: Long,
    val startPositionMs: Long = 0,
    val playWhenReady: Boolean = true,
    val audioIndex: Int? = null,
    /** -1 switches subtitles off, null lets the receiver's language policy decide. */
    val subtitleIndex: Int? = null,
    val audioDelayMs: Long = 0,
    val subtitleDelayMs: Long = 0,
    val subtitles: List<RemoteSubtitle> = emptyList()
) : RemoteMessage

@Serializable @SerialName("play") data object Play : RemoteMessage
@Serializable @SerialName("pause") data object Pause : RemoteMessage
@Serializable @SerialName("stop") data object Stop : RemoteMessage

@Serializable @SerialName("seek_to") data class SeekTo(val positionMs: Long) : RemoteMessage
@Serializable @SerialName("seek_by") data class SeekBy(val deltaMs: Long) : RemoteMessage
@Serializable @SerialName("select_audio") data class SelectAudio(val index: Int) : RemoteMessage

/** [index] -1 switches subtitles off. */
@Serializable @SerialName("select_subtitle") data class SelectSubtitle(val index: Int) : RemoteMessage
@Serializable @SerialName("set_speed") data class SetSpeed(val speed: Float) : RemoteMessage

/** [level] is 0..1 of the TV's media volume. */
@Serializable @SerialName("set_volume") data class SetVolume(val level: Float) : RemoteMessage
@Serializable @SerialName("set_muted") data class SetMuted(val muted: Boolean) : RemoteMessage

/** Asks for a fresh [RemoteState] (a controller that just reconnected). */
@Serializable @SerialName("request_state") data object RequestState : RemoteMessage

// ---- Events: receiver -> controller --------------------------------------------------------------------------

enum class RemoteStatus { IDLE, BUFFERING, READY, ENDED, ERROR }

@Serializable
data class RemoteTrack(
    val index: Int,
    val language: String? = null,
    val label: String? = null,
    val codec: String? = null,
    val isSelected: Boolean = false,
    val isSupported: Boolean = true
)

/** Everything the remote screen shows; sent on every structural change and on [RequestState]. */
@Serializable
@SerialName("state")
data class RemoteState(
    val status: RemoteStatus = RemoteStatus.IDLE,
    val mediaFileId: Long? = null,
    val title: String? = null,
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val audio: List<RemoteTrack> = emptyList(),
    val subtitles: List<RemoteTrack> = emptyList(),
    val errorMessage: String? = null,
    val errorSuggestion: String? = null
) : RemoteMessage

/** Lightweight tick (about once a second while playing) so the seek bar moves without resending the track lists. */
@Serializable
@SerialName("progress")
data class RemoteProgress(
    val mediaFileId: Long?,
    val positionMs: Long,
    val durationMs: Long,
    val bufferedMs: Long = 0
) : RemoteMessage

/** The media finished on the TV. [positionMs] is where it stopped, for the watched/completed decision on the phone. */
@Serializable
@SerialName("ended")
data class Ended(val mediaFileId: Long?, val positionMs: Long, val durationMs: Long) : RemoteMessage

// ---- Keep-alive ----------------------------------------------------------------------------------------------

@Serializable @SerialName("ping") data class Ping(val nonce: Long) : RemoteMessage
@Serializable @SerialName("pong") data class Pong(val nonce: Long) : RemoteMessage
