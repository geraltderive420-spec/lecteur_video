package com.lecteur.core.data.shared

import com.lecteur.core.common.library.Catalog
import com.lecteur.core.common.library.FileDto
import com.lecteur.core.common.text.NaturalOrder
import com.lecteur.core.common.text.TextFold
import com.lecteur.core.data.search.FileHit
import com.lecteur.core.data.search.PersonHit
import com.lecteur.core.data.search.SearchResults
import com.lecteur.core.data.search.TitleHit
import com.lecteur.core.model.FilterOptions
import com.lecteur.core.model.Genre
import com.lecteur.core.model.HdrFilter
import com.lecteur.core.model.LanguageOption
import com.lecteur.core.model.LanguageVariants
import com.lecteur.core.model.LibraryFilters
import com.lecteur.core.model.LibraryItem
import com.lecteur.core.model.LibraryQuery
import com.lecteur.core.model.LibrarySection
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.ResolutionTier
import com.lecteur.core.model.SortField
import com.lecteur.core.model.SortOrder
import com.lecteur.core.model.WatchFilter
import com.lecteur.core.model.WatchStatus
import com.lecteur.core.player.tracks.LanguageCodes

/**
 * The library grid, filters and sorts of the phone, applied to a PC's catalog in memory (a catalog is a few thousand
 * titles at most). Same rules as the SQL of the local library; genres are matched by name ([Genre.id] is its hash, see
 * [SharedMapping]) and people by the PC's person id.
 */
object SharedQueries {

    private class Row(val item: LibraryItem, val genres: List<String>, val people: List<Long>, val certification: String?, val releaseDate: String?, val files: List<FileDto>)

    fun items(catalog: Catalog, mapping: SharedMapping, query: LibraryQuery): List<LibraryItem> {
        val rows = rows(catalog, mapping, query.section).filter { matches(it, query.filters) }
        val comparator: Comparator<Row> = when (query.sort.field) {
            SortField.TITLE -> Comparator { a, b -> NaturalOrder.compare(a.item.title, b.item.title) }
            SortField.ADDED -> compareBy { it.item.addedAt }
            SortField.RELEASE -> compareBy<Row> { it.releaseDate ?: it.item.year?.toString() }
            SortField.RATING -> compareBy { it.item.rating ?: 0f }
            SortField.DURATION -> compareBy { r -> r.files.sumOf { it.durationMs ?: 0L } }
            SortField.LAST_PLAYED -> compareBy { r -> r.files.maxOfOrNull { it.watch?.lastWatchedAt ?: 0L } ?: 0L }
            SortField.FILE_SIZE -> compareBy { r -> r.files.sumOf { it.sizeBytes } }
        }
        val sorted = rows.sortedWith(if (query.sort.order == SortOrder.ASC) comparator else comparator.reversed())
        return sorted.map { it.item }.let { list -> query.limit?.let(list::take) ?: list }
    }

    /**
     * The search screen's results in a PC's catalog: titles (displayed or original name), people of the casts and file
     * names holding every word typed, accents and case ignored; best match first. [art] turns artwork keys into URLs.
     */
    fun search(catalog: Catalog, art: (String?) -> String?, text: String): SearchResults {
        val tokens = TextFold.tokens(text)
        if (tokens.isEmpty()) return SearchResults(text)
        fun score(title: String, original: String?): Pair<Int, String?>? {
            TextFold.score(TextFold.fold(title), tokens)?.let { return it to null }
            return original?.let { o -> TextFold.score(TextFold.fold(o), tokens)?.let { it to o } }
        }
        val movies = catalog.movies.mapNotNull { m ->
            score(m.title, m.originalTitle)?.let { (s, o) -> s to TitleHit(MediaKind.MOVIE, m.id, m.title, m.year, art(m.poster), o) }
        }.sortedByDescending { it.first }.map { it.second }
        val series = catalog.series.mapNotNull { t ->
            score(t.title, t.originalTitle)?.let { (s, o) -> s to TitleHit(MediaKind.SERIES, t.id, t.title, t.year, art(t.poster), o) }
        }.sortedByDescending { it.first }.map { it.second }
        val credits = catalog.movies.flatMap { it.cast } + catalog.series.flatMap { it.cast }
        val people = credits.groupBy { it.personId }.mapNotNull { (id, list) ->
            val first = list.first()
            TextFold.score(TextFold.fold(first.name), tokens)?.let { s -> s to PersonHit(id, first.name, art(first.profile), list.size, list.any { it.director }) }
        }.sortedWith(compareByDescending<Pair<Int, PersonHit>> { it.first }.thenByDescending { it.second.titleCount }).map { it.second }
        val files = catalog.movies.flatMap { m -> m.files.map { Triple(it, MediaKind.MOVIE, m.id) } } +
            catalog.series.flatMap { s -> s.episodes.flatMap { e -> e.files.map { Triple(it, MediaKind.SERIES, s.id) } } }
        val fileHits = files.filter { (f) -> TextFold.score(TextFold.fold(f.fileName), tokens) != null }
            .take(MAX_FILES).map { (f, kind, id) -> FileHit(f.id, f.fileName, kind, id, f.available) }
        return SearchResults(text, movies, series, people.take(MAX_PEOPLE), fileHits)
    }

    fun filterOptions(catalog: Catalog, section: LibrarySection): FilterOptions {
        val movies = section == LibrarySection.MOVIES
        val genres = if (movies) catalog.movies.flatMap { it.genres } else catalog.series.flatMap { it.genres }
        val years = if (movies) catalog.movies.mapNotNull { it.year } else catalog.series.mapNotNull { it.year }
        val certifications = if (movies) catalog.movies.mapNotNull { it.certification } else catalog.series.mapNotNull { it.certification }
        val files = if (movies) catalog.movies.flatMap { it.files } else catalog.series.flatMap { s -> s.episodes.flatMap { it.files } }
        return FilterOptions(
            genres = genres.distinct().sortedWith(NaturalOrder).map { Genre(it.hashCode().toLong(), null, it) },
            decades = years.filter { it > 0 }.map { it / 10 * 10 }.distinct().sortedDescending(),
            certifications = certifications.filter { it.isNotBlank() }.distinct().sorted(),
            audioLanguages = files.flatMap { it.audioLanguages }.mapNotNull(LanguageCodes::normalize).distinct()
                .map { LanguageOption(it, LanguageCodes.displayName(it) ?: it) }.sortedBy { it.name.lowercase() }
        )
    }

    /** The name of a genre of the catalog, from the id the filters carry. */
    fun genreName(catalog: Catalog, id: Long): String? =
        (catalog.movies.flatMap { it.genres } + catalog.series.flatMap { it.genres }).firstOrNull { it.hashCode().toLong() == id }

    fun personName(catalog: Catalog, id: Long): String? =
        (catalog.movies.flatMap { it.cast } + catalog.series.flatMap { it.cast }).firstOrNull { it.personId == id }?.name

    private const val MAX_PEOPLE = 30
    private const val MAX_FILES = 50

    private fun rows(catalog: Catalog, mapping: SharedMapping, section: LibrarySection): List<Row> =
        if (section == LibrarySection.MOVIES) catalog.movies.map { m ->
            Row(mapping.movieItem(m), m.genres, m.cast.map { it.personId }, m.certification, m.releaseDate, m.files)
        } else catalog.series.map { s ->
            Row(mapping.seriesItem(s), s.genres, s.cast.map { it.personId }, s.certification, s.firstAirDate, s.episodes.flatMap { it.files })
        }

    private fun matches(row: Row, f: LibraryFilters): Boolean {
        // Collections, lists and folders belong to this phone's library: nothing of a PC's is in them
        if (f.collectionId != null || f.userListId != null || f.folderId != null) return false
        if (f.genreId != null && row.genres.none { it.hashCode().toLong() == f.genreId }) return false
        if (f.personId != null && f.personId !in row.people) return false
        val year = row.item.year
        if (f.yearFrom != null && (year == null || year < f.yearFrom!!)) return false
        if (f.yearTo != null && (year == null || year > f.yearTo!!)) return false
        if (f.certification != null && row.certification != f.certification) return false
        when (f.watch) {
            WatchFilter.ALL -> Unit
            WatchFilter.UNWATCHED -> if (row.item.watch != WatchStatus.UNWATCHED) return false
            WatchFilter.IN_PROGRESS -> if (row.item.watch != WatchStatus.IN_PROGRESS) return false
            WatchFilter.WATCHED -> if (row.item.watch != WatchStatus.WATCHED) return false
        }
        if (f.resolution != ResolutionTier.ANY && row.files.none { ResolutionTier.of(it.width, it.height) >= f.resolution }) return false
        when (f.hdr) {
            HdrFilter.ANY -> Unit
            HdrFilter.ANY_HDR -> if (row.files.none { it.hdr != null && it.hdr != "NONE" }) return false
            HdrFilter.DOLBY_VISION -> if (row.files.none { it.hdr == "DOLBY_VISION" }) return false
        }
        f.audioLanguage?.let { code ->
            val spellings = LanguageVariants.of(code)
            if (row.files.none { file -> file.audioLanguages.any { it.lowercase() in spellings } }) return false
        }
        return true
    }
}
