package com.lecteur.core.common.file

import java.security.MessageDigest

/**
 * Identity of a media file that survives renames and moves: size + the first and last 64 KiB.
 * Cheap enough to compute on removable storage, and unique enough in practice for video files.
 */
object FileFingerprint {
    const val SAMPLE_BYTES = 64 * 1024
    private const val VERSION_PREFIX = "v1-"

    /**
     * @param readAt reads up to `length` bytes starting at `offset`; may return fewer at the end of the file.
     */
    fun compute(size: Long, readAt: (offset: Long, length: Int) -> ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(longToBytes(size))
        if (size > 0) {
            val headLength = minOf(size, SAMPLE_BYTES.toLong()).toInt()
            digest.update(readAt(0, headLength))
            if (size > SAMPLE_BYTES) {
                // Do not read the same bytes twice when the file is shorter than two samples
                val tailOffset = maxOf(size - SAMPLE_BYTES, SAMPLE_BYTES.toLong())
                digest.update(readAt(tailOffset, (size - tailOffset).toInt()))
            }
        }
        return VERSION_PREFIX + digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Last resort for sources that cannot be read randomly: stable per location, but not per content. */
    fun fromLocation(location: String, size: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(location.toByteArray(Charsets.UTF_8))
        digest.update(longToBytes(size))
        return "loc-" + digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun longToBytes(value: Long): ByteArray = ByteArray(8) { i -> (value shr (56 - 8 * i)).toByte() }
}
