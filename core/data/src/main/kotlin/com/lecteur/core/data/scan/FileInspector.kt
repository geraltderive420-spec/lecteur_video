package com.lecteur.core.data.scan

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.lecteur.core.data.playback.UriFileInfoReader
import com.lecteur.core.model.HdrType
import com.lecteur.core.player.probe.MediaProbe
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Reads what the scanner needs from a file's content. Split from the scanner so the scan logic is testable without media files. */
interface FileInspector {
    suspend fun fingerprint(uri: String, size: Long): String

    /** Null when the file cannot be read at all (it is still recorded, with unknown technical details). */
    suspend fun inspect(uri: String, nameHdr: HdrType, extension: String?): TechnicalInfo?
}

class AndroidFileInspector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val probe: MediaProbe,
    private val uriReader: UriFileInfoReader
) : FileInspector {

    override suspend fun fingerprint(uri: String, size: Long): String = uriReader.fingerprintOf(uri, size)

    override suspend fun inspect(uri: String, nameHdr: HdrType, extension: String?): TechnicalInfo? {
        val parsedUri = Uri.parse(uri)
        val ffmpeg = probe.probe(parsedUri)
        val platform = extract(parsedUri)
        if (ffmpeg == null && !platform.ran) return null
        return TechnicalInfoBuilder.build(ffmpeg, platform, nameHdr, extension)
    }

    /** Platform extractor: reliable duration in microseconds and the colour transfer / Dolby Vision MIME that FFmpeg's wrapper does not give. */
    private suspend fun extract(uri: Uri): ExtractorFacts = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var duration = 0L
            var mime: String? = null
            var transfer: Int? = null
            var width: Int? = null
            var height: Int? = null

            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (format.containsKey(MediaFormat.KEY_DURATION)) duration = maxOf(duration, format.getLong(MediaFormat.KEY_DURATION))
                val trackMime = format.getString(MediaFormat.KEY_MIME)
                if (mime == null && trackMime?.startsWith("video/") == true) {
                    mime = trackMime
                    if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) transfer = format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                    if (format.containsKey(MediaFormat.KEY_WIDTH)) width = format.getInteger(MediaFormat.KEY_WIDTH)
                    if (format.containsKey(MediaFormat.KEY_HEIGHT)) height = format.getInteger(MediaFormat.KEY_HEIGHT)
                }
            }
            ExtractorFacts(true, duration.takeIf { it > 0 }?.div(1000), mime, transfer, width, height)
        } catch (e: Exception) {
            ExtractorFacts(ran = false)
        } finally {
            runCatching { extractor.release() }
        }
    }
}
