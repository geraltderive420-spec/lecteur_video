package com.lecteur.core.model

/** What a file is, in the short badges of the detail page ("4K", "Dolby Vision", "TrueHD 7.1"...). */
data class MediaBadges(
    val resolution: String?,
    val hdr: HdrType,
    val videoCodec: VideoCodec,
    /** The best audio track: "TrueHD 7.1", "DTS-HD MA 5.1"... */
    val audio: String?,
    val audioLanguages: List<String>,
    val subtitleLanguages: List<String>
)

/** One file behind a film or an episode: several when the library holds a 1080p and a 4K copy. */
data class FileVersion(
    val mediaFileId: Long,
    val fileName: String,
    val displayPath: String,
    val sizeBytes: Long,
    val durationMs: Long?,
    val isAvailable: Boolean,
    val badges: MediaBadges,
    val watch: WatchState?
) {
    /** "1080p · HDR10 · 4,2 Go": what tells two versions apart in the picker. */
    val shortLabel: String
        get() = listOfNotNull(
            badges.resolution,
            badges.hdr.label(),
            badges.videoCodec.label(),
            badges.audio
        ).joinToString(" · ").ifEmpty { fileName }
}

data class CastCredit(
    val personId: Long,
    val name: String,
    val role: String?,
    val profilePath: String?,
    val isDirector: Boolean
)

data class CollectionInfo(
    val id: Long,
    val name: String,
    /** The other films of the saga that the library holds. */
    val others: List<LibraryItem>
)

data class MovieDetail(
    val movie: Movie,
    val genres: List<Genre>,
    val directors: List<CastCredit>,
    val cast: List<CastCredit>,
    val versions: List<FileVersion>,
    val collection: CollectionInfo?,
    val isFavorite: Boolean,
    val watch: WatchStatus,
    /** Furthest progress among the versions, 0 when not started. */
    val progress: Float
) {
    /** The version "Lire" starts: the one being watched, else the first available (see [FileVersion] ordering). */
    val defaultVersion: FileVersion?
        get() = versions.firstOrNull { it.isAvailable && (it.watch?.progressPercentage ?: 0f) > 0f && it.watch?.isCompleted != true }
            ?: versions.firstOrNull { it.isAvailable }
}

data class EpisodeItem(
    val episodeId: Long,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String?,
    val overview: String?,
    val stillPath: String?,
    val airDate: String?,
    val runtimeMinutes: Int?,
    val versions: List<FileVersion>,
    val watch: WatchStatus,
    val progress: Float
) {
    /** False for an episode TMDB lists but the library does not hold (shown dimmed, nothing to play). */
    val isInLibrary: Boolean get() = versions.isNotEmpty()
    val isAvailable: Boolean get() = versions.any { it.isAvailable }
    val label: String get() = "S%02dE%02d".format(seasonNumber, episodeNumber)
}

data class SeasonDetail(
    val seasonNumber: Int,
    val name: String?,
    val posterPath: String?,
    val episodes: List<EpisodeItem>
) {
    val inLibraryCount: Int get() = episodes.count { it.isInLibrary }
    val watchedCount: Int get() = episodes.count { it.watch == WatchStatus.WATCHED }
    val title: String get() = name?.takeIf { it.isNotBlank() } ?: if (seasonNumber == 0) "Épisodes spéciaux" else "Saison $seasonNumber"
}

/** Why the "Lire" button of a series says what it says. */
enum class SeriesPlayKind { START, RESUME, NEXT, REWATCH }

data class SeriesPlayTarget(val episode: EpisodeItem, val kind: SeriesPlayKind)

data class SeriesDetail(
    val series: Series,
    val genres: List<Genre>,
    val cast: List<CastCredit>,
    val seasons: List<SeasonDetail>,
    val isFavorite: Boolean,
    val preference: SeriesPreference?,
    val playTarget: SeriesPlayTarget?,
    val watch: WatchStatus
)

object ExternalLinks {
    fun imdb(imdbId: String?): String? = imdbId?.takeIf { it.isNotBlank() }?.let { "https://www.imdb.com/title/$it/" }
    fun trailer(youtubeKey: String?): String? = youtubeKey?.takeIf { it.isNotBlank() }?.let { "https://www.youtube.com/watch?v=$it" }
    fun tmdb(kind: MediaKind, tmdbId: Long?): String? =
        tmdbId?.let { "https://www.themoviedb.org/${if (kind == MediaKind.MOVIE) "movie" else "tv"}/$it" }
}

fun HdrType.label(): String? = when (this) {
    HdrType.NONE -> null
    HdrType.HDR10 -> "HDR10"
    HdrType.HDR10_PLUS -> "HDR10+"
    HdrType.HLG -> "HLG"
    HdrType.DOLBY_VISION -> "Dolby Vision"
}

fun VideoCodec.label(): String? = when (this) {
    VideoCodec.H264 -> "H.264"
    VideoCodec.HEVC -> "HEVC"
    VideoCodec.VP9 -> "VP9"
    VideoCodec.AV1 -> "AV1"
    VideoCodec.MPEG4 -> "MPEG-4"
    VideoCodec.MPEG2 -> "MPEG-2"
    VideoCodec.VC1 -> "VC-1"
    VideoCodec.UNKNOWN -> null
}

fun AudioCodec.label(): String? = when (this) {
    AudioCodec.AAC -> "AAC"
    AudioCodec.MP3 -> "MP3"
    AudioCodec.AC3 -> "Dolby Digital"
    AudioCodec.E_AC3 -> "Dolby Digital Plus"
    AudioCodec.E_AC3_JOC -> "Dolby Atmos"
    AudioCodec.TRUEHD -> "TrueHD"
    AudioCodec.DTS -> "DTS"
    AudioCodec.DTS_HD_MA -> "DTS-HD MA"
    AudioCodec.DTS_X -> "DTS:X"
    AudioCodec.FLAC -> "FLAC"
    AudioCodec.OPUS -> "Opus"
    AudioCodec.VORBIS -> "Vorbis"
    AudioCodec.PCM -> "PCM"
    AudioCodec.UNKNOWN -> null
}

/** 2 -> "2.0", 6 -> "5.1", 8 -> "7.1". */
fun channelLabel(channels: Int): String? = when {
    channels <= 0 -> null
    channels == 1 -> "1.0"
    channels == 2 -> "2.0"
    channels == 3 -> "2.1"
    channels == 4 -> "4.0"
    channels == 5 -> "5.0"
    channels == 6 -> "5.1"
    channels == 7 -> "6.1"
    channels == 8 -> "7.1"
    else -> "$channels ch"
}
