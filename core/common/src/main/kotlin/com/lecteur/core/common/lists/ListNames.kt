package com.lecteur.core.common.lists

/** Why a list name was refused, in terms the user can act on. */
enum class ListNameProblem(val message: String) {
    EMPTY("Donnez un nom à la liste."),
    TOO_LONG("Le nom est trop long (60 caractères au maximum)."),
    RESERVED("Ce nom est réservé : les favoris ont leur propre liste."),
    DUPLICATE("Une liste porte déjà ce nom.")
}

sealed interface ListNameCheck {
    /** [name] is the cleaned name to store. */
    data class Valid(val name: String) : ListNameCheck

    data class Invalid(val problem: ListNameProblem) : ListNameCheck
}

object ListNames {
    const val MAX_LENGTH = 60

    /**
     * Cleans [raw] (trims, folds inner whitespace) and checks it against the names already in use. [existing] must not
     * contain the list being renamed, or renaming a list to its own name with other capitals would be refused.
     */
    fun check(raw: String, existing: Collection<String>, reserved: String): ListNameCheck {
        val name = raw.trim().replace(WHITESPACE, " ")
        return when {
            name.isEmpty() -> ListNameCheck.Invalid(ListNameProblem.EMPTY)
            name.length > MAX_LENGTH -> ListNameCheck.Invalid(ListNameProblem.TOO_LONG)
            name.equals(reserved, ignoreCase = true) -> ListNameCheck.Invalid(ListNameProblem.RESERVED)
            existing.any { it.equals(name, ignoreCase = true) } -> ListNameCheck.Invalid(ListNameProblem.DUPLICATE)
            else -> ListNameCheck.Valid(name)
        }
    }

    private val WHITESPACE = Regex("\\s+")
}
