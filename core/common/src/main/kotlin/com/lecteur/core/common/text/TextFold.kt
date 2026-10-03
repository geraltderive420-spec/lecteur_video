package com.lecteur.core.common.text

import java.text.Normalizer

/**
 * Accent- and case-insensitive text matching for the search. SQLite's LIKE and lower() only fold ASCII, so "amelie" would
 * never find "Amélie": titles are folded here instead, on both sides.
 */
object TextFold {

    private val diacritics = Regex("\\p{M}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    /** Lower case, no accents, letters and digits separated by single spaces ("Léon: The Professional" -> "leon the professional"). */
    fun fold(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        return diacritics.replace(decomposed, "")
            .lowercase()
            .replace("œ", "oe").replace("æ", "ae").replace("ß", "ss").replace("ø", "o").replace("ł", "l").replace("đ", "d")
            .let { separators.replace(it, " ") }
            .trim()
    }

    fun tokens(query: String): List<String> = fold(query).split(' ').filter { it.isNotEmpty() }

    /**
     * Rank of [folded] for the query [tokens] (lower is better), or null when a token is missing:
     * 0 starts with the whole query, 1 starts with a word of the query, 2 contains every token.
     */
    fun score(folded: String, tokens: List<String>): Int? {
        if (tokens.isEmpty()) return null
        if (!tokens.all { folded.contains(it) }) return null
        val words = folded.split(' ')
        return when {
            folded.startsWith(tokens.joinToString(" ")) -> 0
            tokens.all { token -> words.any { it.startsWith(token) } } -> 1
            else -> 2
        }
    }
}
