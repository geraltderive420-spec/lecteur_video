package com.lecteur.core.common.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NaturalOrderTest {

    @Test
    fun numbersCompareByValue() {
        val sorted = listOf("Ep 10.mkv", "Ep 2.mkv", "Ep 1.mkv", "Ep 11.mkv").sortedWith(NaturalOrder)
        assertThat(sorted).containsExactly("Ep 1.mkv", "Ep 2.mkv", "Ep 10.mkv", "Ep 11.mkv").inOrder()
    }

    @Test
    fun leadingZerosDoNotChangeValue() {
        val sorted = listOf("S01E010", "S01E9", "S01E002").sortedWith(NaturalOrder)
        assertThat(sorted).containsExactly("S01E002", "S01E9", "S01E010").inOrder()
    }

    @Test
    fun caseAndAccentsAreIgnored() {
        val sorted = listOf("zèbre", "Zoo", "école", "Arbre").sortedWith(NaturalOrder)
        assertThat(sorted).containsExactly("Arbre", "école", "zèbre", "Zoo").inOrder()
    }

    @Test
    fun hugeNumbersDoNotOverflow() {
        val sorted = listOf("a99999999999999999999999", "a100000000000000000000000").sortedWith(NaturalOrder)
        assertThat(sorted.first()).isEqualTo("a99999999999999999999999")
    }

    @Test
    fun orderIsTotalForNamesThatFoldTheSame() {
        assertThat(NaturalOrder.compare("Été", "ete")).isNotEqualTo(0)
        assertThat(NaturalOrder.compare("same", "same")).isEqualTo(0)
    }

    @Test
    fun shorterNameComesFirstWhenOneIsPrefixOfTheOther() {
        assertThat(NaturalOrder.compare("Show", "Show 2")).isLessThan(0)
    }
}
