package com.lecteur.core.player.engine

import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Audio/video sync correction for PCM playback.
 *
 * A positive delay prepends silence (audio plays later), a negative delay drops the first frames
 * (audio plays earlier). Changing the delay while playing inserts or drops the difference once.
 * After a flush (seek, new stream) the whole delay is applied again from the first frame.
 *
 * Only PCM goes through the processor chain: bitstream passthrough (AC3/DTS/TrueHD sent to an amplifier)
 * cannot be delayed here, which the UI reports.
 */
@UnstableApi
class AudioDelayProcessor : BaseAudioProcessor() {

    private val requestedDelayMs = AtomicLong(0)

    private var frameSize = 0
    private var sampleRate = 0
    private var appliedFrames = 0L
    private var pendingSilenceFrames = 0L
    private var pendingSkipFrames = 0L

    /** Thread-safe; takes effect with the next buffer. Clamped to +/- [MAX_DELAY_MS]. */
    fun setDelayMs(delayMs: Long) {
        requestedDelayMs.set(delayMs.coerceIn(-MAX_DELAY_MS, MAX_DELAY_MS))
    }

    @Throws(UnhandledAudioFormatException::class)
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (!Util.isEncodingLinearPcm(inputAudioFormat.encoding)) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        frameSize = inputAudioFormat.bytesPerFrame
        sampleRate = inputAudioFormat.sampleRate
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val desiredFrames = requestedDelayMs.get() * sampleRate / 1000L
        val change = desiredFrames - appliedFrames
        appliedFrames = desiredFrames
        if (change > 0) {
            // A growing delay first cancels pending drops, then adds silence
            val cancelled = min(change, pendingSkipFrames)
            pendingSkipFrames -= cancelled
            pendingSilenceFrames += change - cancelled
        } else if (change < 0) {
            val cancelled = min(-change, pendingSilenceFrames)
            pendingSilenceFrames -= cancelled
            pendingSkipFrames += -change - cancelled
        }

        val skipBytes = min(pendingSkipFrames * frameSize, inputBuffer.remaining().toLong()).toInt()
        // Never cut a frame in half
        val alignedSkip = skipBytes - skipBytes % frameSize
        inputBuffer.position(inputBuffer.position() + alignedSkip)
        pendingSkipFrames -= alignedSkip / frameSize

        val silenceBytes = (pendingSilenceFrames * frameSize).toInt()
        pendingSilenceFrames = 0

        val total = silenceBytes + inputBuffer.remaining()
        if (total == 0) return
        val output = replaceOutputBuffer(total)
        if (silenceBytes > 0) output.put(ByteArray(silenceBytes))
        output.put(inputBuffer)
        output.flip()
    }

    override fun onFlush() {
        appliedFrames = 0
        pendingSilenceFrames = 0
        pendingSkipFrames = 0
    }

    override fun onReset() {
        onFlush()
        frameSize = 0
        sampleRate = 0
    }

    companion object {
        const val MAX_DELAY_MS = 5_000L
    }
}
