package com.lecteur.feature.library

import com.lecteur.core.model.FilterOptions
import com.lecteur.core.model.Formatters
import com.lecteur.core.model.HdrFilter
import com.lecteur.core.model.LibraryFilters
import com.lecteur.core.model.LibrarySection
import com.lecteur.core.model.SortField
import com.lecteur.core.model.SortOrder
import com.lecteur.core.model.WatchFilter

/** What the sort menu, the filter sheet and the chips say, in one place so they stay consistent. */
object LibraryLabels {

    fun sort(field: SortField): String = when (field) {
        SortField.TITLE -> "Titre"
        SortField.ADDED -> "Date d'ajout"
        SortField.RELEASE -> "Date de sortie"
        SortField.RATING -> "Note"
        SortField.DURATION -> "Durée"
        SortField.LAST_PLAYED -> "Dernière lecture"
        SortField.FILE_SIZE -> "Taille du fichier"
    }

    fun sortOrder(field: SortField, order: SortOrder): String = when (field) {
        SortField.TITLE -> if (order == SortOrder.ASC) "A à Z" else "Z à A"
        SortField.ADDED, SortField.RELEASE, SortField.LAST_PLAYED -> if (order == SortOrder.DESC) "Plus récent d'abord" else "Plus ancien d'abord"
        SortField.RATING -> if (order == SortOrder.DESC) "Mieux noté d'abord" else "Moins bien noté d'abord"
        SortField.DURATION -> if (order == SortOrder.DESC) "Plus long d'abord" else "Plus court d'abord"
        SortField.FILE_SIZE -> if (order == SortOrder.DESC) "Plus gros d'abord" else "Plus petit d'abord"
    }

    fun watch(filter: WatchFilter): String = when (filter) {
        WatchFilter.ALL -> "Tous"
        WatchFilter.UNWATCHED -> "Non vus"
        WatchFilter.IN_PROGRESS -> "En cours"
        WatchFilter.WATCHED -> "Vus"
    }

    fun hdr(filter: HdrFilter): String = when (filter) {
        HdrFilter.ANY -> "Tous"
        HdrFilter.ANY_HDR -> "HDR"
        HdrFilter.DOLBY_VISION -> "Dolby Vision"
    }

    fun section(section: LibrarySection): String = if (section == LibrarySection.MOVIES) "Films" else "Séries"

    fun emptyShelf(section: LibrarySection): String =
        if (section == LibrarySection.MOVIES) "Aucun film dans la bibliothèque" else "Aucune série dans la bibliothèque"

    /** One removable chip per active filter: what it says, and the filters once it is removed. */
    data class FilterChipSpec(val label: String, val remove: (LibraryFilters) -> LibraryFilters)

    fun activeChips(filters: LibraryFilters, options: FilterOptions?): List<FilterChipSpec> = buildList {
        filters.genreId?.let { id ->
            add(FilterChipSpec(options?.genres?.firstOrNull { it.id == id }?.name ?: "Genre") { it.copy(genreId = null) })
        }
        if (filters.yearFrom != null || filters.yearTo != null) {
            val decade = filters.yearFrom?.takeIf { filters.yearTo == it + 9 }
            add(FilterChipSpec(if (decade != null) Formatters.decade(decade) else "Année") { it.copy(yearFrom = null, yearTo = null) })
        }
        if (filters.watch != WatchFilter.ALL) add(FilterChipSpec(watch(filters.watch)) { it.copy(watch = WatchFilter.ALL) })
        if (filters.resolution.minWidth > 0) add(FilterChipSpec(filters.resolution.label) { it.copy(resolution = com.lecteur.core.model.ResolutionTier.ANY) })
        if (filters.hdr != HdrFilter.ANY) add(FilterChipSpec(hdr(filters.hdr)) { it.copy(hdr = HdrFilter.ANY) })
        filters.certification?.let { c -> add(FilterChipSpec("Âge $c") { it.copy(certification = null) }) }
        filters.folderId?.let { id ->
            add(FilterChipSpec(options?.folders?.firstOrNull { it.id == id }?.displayPath?.substringAfterLast('/') ?: "Dossier") { it.copy(folderId = null) })
        }
        filters.audioLanguage?.let { code ->
            add(FilterChipSpec("Audio : " + (options?.audioLanguages?.firstOrNull { it.code == code }?.name ?: code)) { it.copy(audioLanguage = null) })
        }
    }
}
