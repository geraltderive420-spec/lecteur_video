package com.lecteur.core.data.search

import com.lecteur.core.common.text.TextFold
import com.lecteur.core.database.dao.LibraryDao
import com.lecteur.core.database.relation.FileHitRow
import com.lecteur.core.database.relation.PersonRow
import com.lecteur.core.database.relation.TitleRow
import com.lecteur.core.model.MediaKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

data class TitleHit(
    val kind: MediaKind,
    val id: Long,
    val title: String,
    val year: Int?,
    val posterPath: String?,
    /** Set when the query matched the original title and not the displayed one ("Amelie" finds "Le Fabuleux Destin..."). */
    val matchedOriginalTitle: String? = null
)

data class PersonHit(val personId: Long, val name: String, val profilePath: String?, val titleCount: Int, val isDirector: Boolean)

/** A file found by its name; [kind] and [titleId] are null when the library could not identify it. */
data class FileHit(val mediaFileId: Long, val fileName: String, val kind: MediaKind?, val titleId: Long?, val isAvailable: Boolean)

data class SearchResults(
    val query: String,
    val movies: List<TitleHit> = emptyList(),
    val series: List<TitleHit> = emptyList(),
    val people: List<PersonHit> = emptyList(),
    val files: List<FileHit> = emptyList()
) {
    val isEmpty: Boolean get() = movies.isEmpty() && series.isEmpty() && people.isEmpty() && files.isEmpty()
}

/**
 * Titles and people held in memory with their accent-folded names, so a keystroke costs a scan of a few thousand short strings
 * and "amelie" finds "Amélie". Built again whenever the tables change.
 */
class SearchIndex(movies: List<TitleRow>, series: List<TitleRow>, people: List<PersonRow>) {

    private class Entry<T>(val row: T, val folded: String, val foldedOriginal: String?)

    private val movieEntries = movies.map { Entry(it, TextFold.fold(it.title), it.originalTitle?.let(TextFold::fold)) }
    private val seriesEntries = series.map { Entry(it, TextFold.fold(it.title), it.originalTitle?.let(TextFold::fold)) }
    private val personEntries = people.map { Entry(it, TextFold.fold(it.personName), null) }

    fun search(query: String, limit: Int = DEFAULT_LIMIT): SearchResults {
        val tokens = TextFold.tokens(query)
        if (tokens.isEmpty()) return SearchResults(query)
        return SearchResults(
            query = query,
            movies = titles(movieEntries, tokens, MediaKind.MOVIE, limit),
            series = titles(seriesEntries, tokens, MediaKind.SERIES, limit),
            people = personEntries.mapNotNull { entry ->
                TextFold.score(entry.folded, tokens)?.let { it to entry.row }
            }.sortedWith(compareBy({ it.first }, { -it.second.titleCount }, { it.second.personName })).take(limit).map { (_, row) ->
                PersonHit(row.personId, row.personName, row.profilePath, row.titleCount, row.isDirector)
            }
        )
    }

    private fun titles(entries: List<Entry<TitleRow>>, tokens: List<String>, kind: MediaKind, limit: Int): List<TitleHit> =
        entries.mapNotNull { entry ->
            val own = TextFold.score(entry.folded, tokens)
            val original = entry.foldedOriginal?.let { TextFold.score(it, tokens) }
            val best = listOfNotNull(own, original).minOrNull() ?: return@mapNotNull null
            Triple(best, entry, own == null)
        }.sortedWith(compareBy({ it.first }, { it.second.row.title.lowercase() })).take(limit).map { (_, entry, viaOriginal) ->
            val row = entry.row
            TitleHit(kind, row.id, row.title, row.year?.takeIf { it > 0 }, row.posterPath, if (viaOriginal) row.originalTitle else null)
        }

    companion object {
        const val DEFAULT_LIMIT = 20
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SearchRepository @Inject constructor(
    private val dao: LibraryDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Kept warm for a minute after the search screen closes: reopening it does not rebuild the index. */
    private val index: StateFlow<SearchIndex?> = combine(
        dao.observeMovieTitles(), dao.observeSeriesTitles(), dao.observePeople(), ::SearchIndex
    ).conflate().flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(INDEX_KEEP_ALIVE_MS), null)

    fun search(query: String): Flow<SearchResults> = index.filterNotNull().mapLatest { idx ->
        val titles = idx.search(query)
        if (titles.query.isBlank() || TextFold.tokens(query).isEmpty()) return@mapLatest titles
        val shown = (titles.movies.map { MediaKind.MOVIE to it.id } + titles.series.map { MediaKind.SERIES to it.id }).toSet()
        val files = dao.findFiles(likePattern(query), FILE_LIMIT).map(FileHitRow::toHit)
            // A file whose film or series is already listed above would only repeat it
            .filter { it.kind == null || (it.kind to it.titleId) !in shown }
        titles.copy(files = files)
    }

    companion object {
        private const val FILE_LIMIT = 20
        private const val INDEX_KEEP_ALIVE_MS = 60_000L

        /** `%blade%runner%`: every word in order, wherever it sits in the name; LIKE wildcards in the query are escaped. */
        fun likePattern(query: String): String =
            query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString("%", prefix = "%", postfix = "%") { word ->
                word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            }
    }
}

private fun FileHitRow.toHit(): FileHit = when {
    movieId != null -> FileHit(mediaFileId, fileName, MediaKind.MOVIE, movieId, isAvailable)
    seriesId != null -> FileHit(mediaFileId, fileName, MediaKind.SERIES, seriesId, isAvailable)
    else -> FileHit(mediaFileId, fileName, null, null, isAvailable)
}
