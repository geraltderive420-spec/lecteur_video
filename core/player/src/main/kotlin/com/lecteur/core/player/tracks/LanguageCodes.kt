package com.lecteur.core.player.tracks

import java.util.Locale

/** Normalizes ISO 639-1/639-2 (B and T) and BCP-47 tags so "fre", "fra", "fr-FR" and "FR" compare equal. */
object LanguageCodes {

    private val iso3To1 = mapOf(
        "fra" to "fr", "fre" to "fr", "eng" to "en", "deu" to "de", "ger" to "de", "spa" to "es",
        "ita" to "it", "por" to "pt", "jpn" to "ja", "rus" to "ru", "kor" to "ko", "zho" to "zh",
        "chi" to "zh", "ara" to "ar", "nld" to "nl", "dut" to "nl", "pol" to "pl", "tur" to "tr",
        "swe" to "sv", "dan" to "da", "nor" to "no", "fin" to "fi", "ces" to "cs", "cze" to "cs",
        "ell" to "el", "gre" to "el", "heb" to "he", "hin" to "hi", "hun" to "hu", "ron" to "ro",
        "rum" to "ro", "tha" to "th", "ukr" to "uk", "vie" to "vi"
    )

    /** Lowercase 2-letter code when known, the lowercase primary subtag otherwise, null for unknown/undetermined. */
    fun normalize(raw: String?): String? {
        val primary = raw?.trim()?.lowercase(Locale.ROOT)?.split('-', '_')?.firstOrNull().orEmpty()
        if (primary.isEmpty() || primary == "und" || primary == "zxx" || primary == "mis" || primary == "mul") return null
        return iso3To1[primary] ?: primary
    }

    /** True for codes this table knows, so release tags such as "hd" or "sub" are not mistaken for languages. */
    fun isKnownCode(raw: String): Boolean = raw in iso3To1.keys || raw in iso3To1.values

    fun matches(a: String?, b: String?): Boolean {
        val na = normalize(a) ?: return false
        return na == normalize(b)
    }

    /** Human readable name in the user's locale ("français"), falling back to the raw code. */
    fun displayName(raw: String?, locale: Locale = Locale.getDefault()): String? {
        val code = normalize(raw) ?: return null
        val name = Locale.forLanguageTag(code).getDisplayLanguage(locale)
        return name.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) } ?: code
    }
}
