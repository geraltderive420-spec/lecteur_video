package com.lecteur.core.common.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TextFoldTest {

    @Test
    fun foldRemovesAccentsCaseAndPunctuation() {
        assertThat(TextFold.fold("Amélie")).isEqualTo("amelie")
        assertThat(TextFold.fold("Léon: The Professional")).isEqualTo("leon the professional")
        assertThat(TextFold.fold("  Œuvres  ÉCRITES—tout ")).isEqualTo("oeuvres ecrites tout")
        assertThat(TextFold.fold("Straße")).isEqualTo("strasse")
    }

    @Test
    fun foldKeepsNonLatinLettersAndDigits() {
        assertThat(TextFold.fold("千と千尋の神隠し 2001")).isEqualTo("千と千尋の神隠し 2001")
    }

    @Test
    fun tokensSplitOnAnySeparator() {
        assertThat(TextFold.tokens("Blade-Runner.2049")).containsExactly("blade", "runner", "2049").inOrder()
        assertThat(TextFold.tokens("   ")).isEmpty()
    }

    @Test
    fun scoreRanksPrefixThenWordPrefixThenContains() {
        val tokens = TextFold.tokens("star")
        assertThat(TextFold.score(TextFold.fold("Star Wars"), tokens)).isEqualTo(0)
        assertThat(TextFold.score(TextFold.fold("The Star"), tokens)).isEqualTo(1)
        assertThat(TextFold.score(TextFold.fold("Mastar"), tokens)).isEqualTo(2)
        assertThat(TextFold.score(TextFold.fold("Wars"), tokens)).isNull()
    }

    @Test
    fun scoreNeedsEveryToken() {
        val tokens = TextFold.tokens("blade 2049")
        assertThat(TextFold.score(TextFold.fold("Blade Runner 2049"), tokens)).isEqualTo(1)
        assertThat(TextFold.score(TextFold.fold("Blade Runner"), tokens)).isNull()
    }

    @Test
    fun emptyQueryMatchesNothing() {
        assertThat(TextFold.score("anything", emptyList())).isNull()
    }
}
