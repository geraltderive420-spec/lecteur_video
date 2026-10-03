package com.lecteur.core.common.match

import java.text.Normalizer
import java.util.Locale

/** Title comparison tolerant to case, accents, punctuation and a leading article. */
object TitleSimilarity {

    private val DIACRITICS = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")
    private val ARTICLES = setOf("the", "a", "an", "le", "la", "les", "l", "un", "une", "des", "el", "los", "las", "der", "die", "das")

    /** Lowercase, accent-free, punctuation turned into single spaces ("Amélie" and "Amelie!" compare equal). */
    fun normalize(title: String): String {
        val folded = Normalizer.normalize(title, Normalizer.Form.NFD).replace(DIACRITICS, "")
        return folded.lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace(NON_ALPHANUMERIC, " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    /** [normalize] without a leading article, for comparisons ("The Matrix" ~ "Matrix"). */
    fun comparisonKey(title: String): String {
        val normalized = normalize(title)
        val words = normalized.split(' ')
        return if (words.size > 1 && words.first() in ARTICLES) words.drop(1).joinToString(" ") else normalized
    }

    /** 0.0 (nothing in common) to 1.0 (same title once normalized). */
    fun similarity(a: String, b: String): Double {
        val keyA = comparisonKey(a)
        val keyB = comparisonKey(b)
        if (keyA.isEmpty() || keyB.isEmpty()) return 0.0
        if (keyA == keyB) return 1.0
        return maxOf(diceBigrams(keyA, keyB), levenshteinRatio(keyA, keyB))
    }

    private fun diceBigrams(a: String, b: String): Double {
        if (a.length < 2 || b.length < 2) return 0.0
        val bigramsA = a.windowed(2).groupingBy { it }.eachCount()
        val bigramsB = b.windowed(2).groupingBy { it }.eachCount()
        val common = bigramsA.entries.sumOf { (gram, count) -> minOf(count, bigramsB[gram] ?: 0) }
        return 2.0 * common / (a.length - 1 + b.length - 1)
    }

    private fun levenshteinRatio(a: String, b: String): Double {
        val distance = levenshtein(a, b)
        return 1.0 - distance.toDouble() / maxOf(a.length, b.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            val swap = previous; previous = current; current = swap
        }
        return previous[b.length]
    }
}
