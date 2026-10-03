package com.lecteur.core.data.library

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.lecteur.core.database.dao.LibraryDao
import com.lecteur.core.database.dao.LibraryFolderDao
import com.lecteur.core.database.query.LibraryQueryBuilder
import com.lecteur.core.database.relation.LibraryRow
import com.lecteur.core.model.FilterOptions
import com.lecteur.core.model.Genre
import com.lecteur.core.model.LanguageOption
import com.lecteur.core.model.LibraryFolder
import com.lecteur.core.model.LibraryItem
import com.lecteur.core.model.LibraryQuery
import com.lecteur.core.model.LibrarySection
import com.lecteur.core.model.MatchState
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.WatchStatus
import com.lecteur.core.player.tracks.LanguageCodes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class LibraryRepository @Inject constructor(
    private val dao: LibraryDao,
    private val folderDao: LibraryFolderDao
) {

    /** The library grid: loaded page by page, refreshed by Room whenever a scan or the player changes what it shows. */
    fun paged(query: LibraryQuery): Flow<PagingData<LibraryItem>> {
        val kind = kindOf(query.section)
        return Pager(PagingConfig(pageSize = PAGE_SIZE, prefetchDistance = PAGE_SIZE, enablePlaceholders = false)) {
            dao.pagedRows(LibraryQueryBuilder.build(query))
        }.flow.map { data -> data.map { it.toItem(kind) } }
    }

    /** A short list for a home row. Query must carry a limit. */
    fun observe(query: LibraryQuery): Flow<List<LibraryItem>> {
        val kind = kindOf(query.section)
        return dao.observeRows(LibraryQueryBuilder.build(query)).map { rows -> rows.map { it.toItem(kind) } }
    }

    /** What the filter sheet can offer for this shelf: only values the library really holds. */
    suspend fun filterOptions(section: LibrarySection): FilterOptions {
        val movies = section == LibrarySection.MOVIES
        val genres = if (movies) dao.movieGenres() else dao.seriesGenres()
        val years = if (movies) dao.movieYears() else dao.seriesYears()
        val certifications = if (movies) dao.movieCertifications() else dao.seriesCertifications()
        return FilterOptions(
            genres = genres.map { Genre(it.id, it.tmdbId, it.name) },
            decades = years.filter { it > 0 }.map { it / 10 * 10 }.distinct().sortedDescending(),
            certifications = certifications,
            folders = folderDao.getUserFolders().distinctBy { it.id }.map { LibraryFolder(it.id, it.uri, it.displayPath, it.category, it.enabled, it.lastScannedAt) },
            audioLanguages = dao.audioLanguages().mapNotNull(LanguageCodes::normalize).distinct()
                .map { LanguageOption(it, LanguageCodes.displayName(it) ?: it) }.sortedBy { it.name.lowercase() }
        )
    }

    private fun kindOf(section: LibrarySection) = if (section == LibrarySection.MOVIES) MediaKind.MOVIE else MediaKind.SERIES

    companion object {
        const val PAGE_SIZE = 60
    }
}

/** Same rule as the SQL filters: a title is watched when all its units are, in progress when something was started. */
fun LibraryRow.toItem(kind: MediaKind): LibraryItem {
    val watch = when {
        unitCount > 0 && watchedCount >= unitCount -> WatchStatus.WATCHED
        watchedCount > 0 || startedCount > 0 -> WatchStatus.IN_PROGRESS
        else -> WatchStatus.UNWATCHED
    }
    return LibraryItem(
        kind = kind,
        id = id,
        title = title,
        year = year?.takeIf { it > 0 },
        posterPath = posterPath,
        rating = rating?.takeIf { it > 0f },
        watch = watch,
        progress = progress,
        unitCount = unitCount,
        watchedCount = watchedCount,
        isAvailable = availableCount > 0,
        addedAt = addedAt,
        matchState = runCatching { MatchState.valueOf(matchState) }.getOrDefault(MatchState.UNIDENTIFIED)
    )
}
