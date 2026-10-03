package com.lecteur.core.common

import com.google.common.truth.Truth.assertThat
import com.lecteur.core.common.lists.ListNameCheck
import com.lecteur.core.common.lists.ListNameProblem
import com.lecteur.core.common.lists.ListNames
import org.junit.Test

class ListNamesTest {
    private fun check(raw: String, existing: List<String> = emptyList()) = ListNames.check(raw, existing, reserved = "Favoris")

    private fun problem(raw: String, existing: List<String> = emptyList()) = (check(raw, existing) as ListNameCheck.Invalid).problem

    @Test fun `a normal name is kept`() = assertThat(check("Soirée Marvel")).isEqualTo(ListNameCheck.Valid("Soirée Marvel"))

    @Test fun `spaces are trimmed and folded`() = assertThat(check("  À   voir \n plus tard ")).isEqualTo(ListNameCheck.Valid("À voir plus tard"))

    @Test fun `blank is refused`() {
        assertThat(problem("")).isEqualTo(ListNameProblem.EMPTY)
        assertThat(problem("   \t")).isEqualTo(ListNameProblem.EMPTY)
    }

    @Test fun `length limit counts the cleaned name`() {
        assertThat(check("x".repeat(60))).isInstanceOf(ListNameCheck.Valid::class.java)
        assertThat(problem("x".repeat(61))).isEqualTo(ListNameProblem.TOO_LONG)
        assertThat(check("  " + "x".repeat(60) + "  ")).isInstanceOf(ListNameCheck.Valid::class.java)
    }

    @Test fun `favourites name is reserved whatever the case`() {
        assertThat(problem("Favoris")).isEqualTo(ListNameProblem.RESERVED)
        assertThat(problem(" favoris ")).isEqualTo(ListNameProblem.RESERVED)
        assertThat(problem("FAVORIS")).isEqualTo(ListNameProblem.RESERVED)
    }

    @Test fun `duplicates are detected without regard to case`() {
        assertThat(problem("à voir", listOf("À voir"))).isEqualTo(ListNameProblem.DUPLICATE)
        assertThat(check("À voir 2", listOf("À voir"))).isInstanceOf(ListNameCheck.Valid::class.java)
    }

    @Test fun `every problem has a message`() {
        ListNameProblem.entries.forEach { assertThat(it.message).isNotEmpty() }
    }
}
