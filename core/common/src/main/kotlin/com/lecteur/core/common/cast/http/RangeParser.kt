package com.lecteur.core.common.cast.http

/** Outcome of reading a `Range` header against a resource of known size. */
sealed interface RangeResult {
    /** No usable range: answer 200 with the whole body. */
    data object Full : RangeResult

    /** Answer 206 with bytes [start]..[endInclusive]. */
    data class Partial(val start: Long, val endInclusive: Long) : RangeResult {
        val length: Long get() = endInclusive - start + 1
    }

    /** Answer 416: the range starts beyond the end of the resource. */
    data object NotSatisfiable : RangeResult
}

/**
 * RFC 9110 single byte-range parsing. Anything the server may legitimately ignore (other units, several ranges,
 * malformed syntax) yields [RangeResult.Full], as the RFC allows; players only ever send one range.
 */
object RangeParser {

    fun parse(header: String?, size: Long): RangeResult {
        if (header == null || size <= 0) return RangeResult.Full
        val spec = header.trim()
        if (!spec.startsWith(UNIT_PREFIX, ignoreCase = true)) return RangeResult.Full
        val value = spec.substring(UNIT_PREFIX.length).trim()
        if (value.isEmpty() || value.contains(',')) return RangeResult.Full

        val dash = value.indexOf('-')
        if (dash < 0) return RangeResult.Full
        val first = value.substring(0, dash).trim()
        val last = value.substring(dash + 1).trim()

        return if (first.isEmpty()) suffix(last, size) else bounded(first, last, size)
    }

    /** `bytes=-N`: the last N bytes. */
    private fun suffix(last: String, size: Long): RangeResult {
        val count = last.toLongOrNull()?.takeIf { it >= 0 && last.all(Char::isDigit) } ?: return RangeResult.Full
        if (count == 0L) return RangeResult.NotSatisfiable
        return RangeResult.Partial((size - count).coerceAtLeast(0), size - 1)
    }

    /** `bytes=A-` and `bytes=A-B`. */
    private fun bounded(first: String, last: String, size: Long): RangeResult {
        if (!first.all(Char::isDigit)) return RangeResult.Full
        val start = first.toLongOrNull() ?: return RangeResult.Full
        val end = if (last.isEmpty()) {
            Long.MAX_VALUE
        } else {
            if (!last.all(Char::isDigit)) return RangeResult.Full
            last.toLongOrNull() ?: return RangeResult.Full
        }
        if (end < start) return RangeResult.Full
        if (start >= size) return RangeResult.NotSatisfiable
        return RangeResult.Partial(start, minOf(end, size - 1))
    }

    private const val UNIT_PREFIX = "bytes="
}
