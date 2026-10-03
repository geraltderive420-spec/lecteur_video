package com.lecteur.feature.player

import kotlin.math.abs

object TimeFormat {

    /** 3723000 -> "1:02:03", 65000 -> "01:05". Negative values are clamped to zero. */
    fun clock(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0) / 1000)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    /** "+00:30" / "-01:10"; zero shows as "+00:00". */
    fun signed(ms: Long): String = (if (ms < 0) "-" else "+") + clock(abs(ms))

    /** Delay values: 150 -> "+150 ms", -1250 -> "-1,25 s", 0 -> "0 ms". */
    fun delay(ms: Long): String = when {
        ms == 0L -> "0 ms"
        abs(ms) < 1000 -> (if (ms > 0) "+" else "-") + abs(ms) + " ms"
        else -> (if (ms > 0) "+" else "-") + "%.2f s".format(java.util.Locale.FRANCE, abs(ms) / 1000.0)
    }

    /** Skip feedback: 20000 -> "+20 s", -10000 -> "-10 s". */
    fun skip(ms: Long): String = (if (ms < 0) "-" else "+") + abs(ms) / 1000 + " s"

    fun speed(value: Float): String {
        val text = if (value % 1f == 0f) value.toInt().toString() else "%.2f".format(java.util.Locale.FRANCE, value).trimEnd('0').trimEnd(',')
        return text + "x"
    }
}
