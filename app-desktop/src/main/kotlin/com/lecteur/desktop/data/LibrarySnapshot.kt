package com.lecteur.desktop.data

import com.lecteur.core.common.playback.EpisodeFileState
import com.lecteur.core.common.playback.NextUpResolver
import com.lecteur.core.common.text.NaturalOrder
import com.lecteur.core.common.text.TextFold
import com.lecteur.core.model.Episode
import com.lecteur.core.model.FilterOptions
import com.lecteur.core.model.Genre
import com.lecteur.core.model.HdrFilter
import com.lecteur.core.model.HdrType
import com.lecteur.core.model.LanguageOption
import com.lecteur.core.model.LanguageVariants
import com.lecteur.core.model.LibraryFolder
import com.lecteur.core.model.LibraryItem
import com.lecteur.core.model.LibraryQuery
import com.lecteur.core.model.LibrarySection
import com.lecteur.core.model.MediaFile
import com.lecteur.core.model.MediaKind
import com.lecteur.core.model.Movie
import com.lecteur.core.model.ResolutionTier
import com.lecteur.core.model.Season
import com.lecteur.core.model.Series
import com.lecteur.core.model.SortField
import com.lecteur.core.model.SortOrder
import com.lecteur.core.model.UserListSummary
import com.lecteur.core.model.WatchFilter
import com.lecteur.core.model.WatchState
import com.lecteur.core.model.WatchStatus
import java.util.Locale

/** A file with what the user did with it. */
data class FileEntry(val file: MediaFile, val watch: WatchState?, val audioLanguages: Set<String>) {
    val progress: Float get() = watch?.takeIf { !it.isCompleted }?.progressPercentage ?: 0f
    val completed: Boolean get() = watch?.isCompleted == true
    val inProgress: Boolean get() = !completed && progress > 0.02f && progress < 0.98f
    val lastWatched: Long get() = watch?.lastWatchedAt ?: 0L
}

data class MovieEntry(val movie: Movie, val files: List<FileEntry>, val genreIds: Set<Long>) {
    val watched: Boolean get() = files.any { it.completed }
    val progress: Float get() = files.maxOfOrNull { it.progress } ?: 0f
    val inProgress: Boolean get() = !watched && files.any { it.inProgress }
    val available: Boolean get() = files.any { it.file.isAvailable }
    val lastPlayed: Long get() = files.maxOfOrNull { it.lastWatched } ?: 0L
    val status: WatchStatus get() = when { watched -> WatchStatus.WATCHED; inProgress -> WatchStatus.IN_PROGRESS; else -> WatchStatus.UNWATCHED }
}

data class EpisodeEntry(val episode: Episode, val files: List<FileEntry>) {
    val inLibrary: Boolean get() = files.isNotEmpty()
    val watched: Boolean get() = files.any { it.completed }
    val inProgress: Boolean get() = !watched && files.any { it.inProgress }
    val progress: Float get() = files.maxOfOrNull { it.progress } ?: 0f
    val available: Boolean get() = files.any { it.file.isAvailable }
    val status: WatchStatus get() = when { watched -> WatchStatus.WATCHED; inProgress -> WatchStatus.IN_PROGRESS; else -> WatchStatus.UNWATCHED }

    /** The version to play: available, already started, sharpest, biggest. */
    val bestFile: FileEntry?
        get() = files.sortedWith(
            compareByDescending<FileEntry> { it.file.isAvailable }.thenByDescending { it.inProgress }
                .thenByDescending { it.file.height ?: 0 }.thenByDescending { it.file.sizeBytes }
        ).firstOrNull()
}

data class SeriesEntry(val series: Series, val seasons: List<Season>, val episodes: List<EpisodeEntry>, val genreIds: Set<Long>) {
    val owned: List<EpisodeEntry> get() = episodes.filter { it.inLibrary }
    val files: List<FileEntry> get() = episodes.flatMap { it.files }
    val watchedCount: Int get() = owned.count { it.watched }
    val available: Boolean get() = files.any { it.file.isAvailable }
    val lastPlayed: Long get() = files.maxOfOrNull { it.lastWatched } ?: 0L
    val status: WatchStatus
        get() = when {
            owned.isNotEmpty() && owned.all { it.watched } -> WatchStatus.WATCHED
            owned.any { it.watched || it.inProgress } -> WatchStatus.IN_PROGRESS
            else -> WatchStatus.UNWATCHED
        }
    val year: Int? get() = series.firstAirDate?.take(4)?.toIntOrNull()
}

/** One card of a home row: a poster plus, for an episode, what to show under it and which file to start. */
data class HomeCard(
    val item: LibraryItem,
    val subtitle: String? = null,
    val mediaFileId: Long? = null,
    val episodeId: Long? = null,
    val progress: Float = item.progress
)

/** Everything the screens read, built in one pass from the database. Immutable: a change builds a new one. */
class LibrarySnapshot(
    val movies: Map<Long, MovieEntry>,
    val series: Map<Long, SeriesEntry>,
    val folders: List<LibraryFolder>,
    val genres: Map<Long, String>,
    val collections: Map<Long, String>,
    val lists: List<LibraryStore.ListRow>,
    val members: List<LibraryStore.ListMember>,
    val people: List<LibraryStore.PersonCredits>,
    val fileIndex: Map<Long, FileEntry>,
    val episodeToSeries: Map<Long, Long>
) {
    val isEmpty: Boolean get() = movies.isEmpty() && series.isEmpty()
    val favoritesId: Long? get() = lists.firstOrNull { it.isFavorites }?.id

    fun isFavorite(kind: MediaKind, id: Long): Boolean {
        val fav = favoritesId ?: return false
        return members.any { it.listId == fav && (if (kind == MediaKind.MOVIE) it.movieId == id else it.seriesId == id) }
    }

    fun listsOf(kind: MediaKind, id: Long): Set<Long> =
        members.filter { if (kind == MediaKind.MOVIE) it.movieId == id else it.seriesId == id }.mapTo(HashSet()) { it.listId }

    fun listSummaries(): List<UserListSummary> = lists.map { list ->
        UserListSummary(list.id, list.name, members.count { it.listId == list.id && (it.movieId in movies || it.seriesId in series) }, list.isFavorites)
    }

    // region items

    fun item(entry: MovieEntry) = LibraryItem(
        kind = MediaKind.MOVIE, id = entry.movie.id, title = entry.movie.title, year = entry.movie.year,
        posterPath = entry.movie.posterPath, rating = entry.movie.rating, watch = entry.status, progress = entry.progress,
        unitCount = 1, watchedCount = if (entry.watched) 1 else 0, isAvailable = entry.available, addedAt = entry.movie.addedAt,
        matchState = entry.movie.matchState
    )

    fun item(entry: SeriesEntry) = LibraryItem(
        kind = MediaKind.SERIES, id = entry.series.id, title = entry.series.title, year = entry.year,
        posterPath = entry.series.posterPath, rating = entry.series.rating, watch = entry.status, progress = 0f,
        unitCount = entry.owned.size, watchedCount = entry.watchedCount, isAvailable = entry.available,
        addedAt = entry.series.addedAt, matchState = entry.series.matchState
    )

    fun itemOf(kind: MediaKind, id: Long): LibraryItem? =
        if (kind == MediaKind.MOVIE) movies[id]?.let(::item) else series[id]?.let(::item)

    // endregion

    // region library grid

    fun query(q: LibraryQuery): List<LibraryItem> {
        val f = q.filters
        val listMembers = f.userListId?.let { id -> members.filter { it.listId == id } }
        val personWorks = f.personId?.let { id -> people.firstOrNull { it.personId == id } }

        fun matchesFiles(files: List<FileEntry>): Boolean {
            if (f.folderId != null && files.none { it.file.folderId == f.folderId }) return false
            if (f.resolution != ResolutionTier.ANY && files.none { ResolutionTier.of(it.file.width, it.file.height).ordinal >= f.resolution.ordinal }) return false
            when (f.hdr) {
                HdrFilter.ANY -> Unit
                HdrFilter.ANY_HDR -> if (files.none { it.file.hdrType != HdrType.NONE }) return false
                HdrFilter.DOLBY_VISION -> if (files.none { it.file.hdrType == HdrType.DOLBY_VISION }) return false
            }
            f.audioLanguage?.let { code ->
                val variants = LanguageVariants.of(code)
                if (files.none { file -> file.audioLanguages.any { it in variants } }) return false
            }
            return true
        }

        fun matchesWatch(status: WatchStatus) = when (f.watch) {
            WatchFilter.ALL -> true
            WatchFilter.UNWATCHED -> status == WatchStatus.UNWATCHED
            WatchFilter.IN_PROGRESS -> status == WatchStatus.IN_PROGRESS
            WatchFilter.WATCHED -> status == WatchStatus.WATCHED
        }

        fun matchesYear(year: Int?) = (f.yearFrom == null || (year != null && year >= f.yearFrom!!)) && (f.yearTo == null || (year != null && year <= f.yearTo!!))

        data class Sortable(val item: LibraryItem, val sortTitle: String, val release: String?, val duration: Long, val lastPlayed: Long, val size: Long)

        val rows: List<Sortable> = when (q.section) {
            LibrarySection.MOVIES -> movies.values.filter { e ->
                (f.genreId == null || f.genreId in e.genreIds) && matchesYear(e.movie.year) && matchesWatch(e.status) &&
                    (f.certification == null || e.movie.certification == f.certification) && matchesFiles(e.files) &&
                    (f.collectionId == null || e.movie.collectionId == f.collectionId) &&
                    (listMembers == null || listMembers.any { it.movieId == e.movie.id }) &&
                    (personWorks == null || e.movie.id in personWorks.movieIds)
            }.map { e ->
                Sortable(item(e), e.movie.sortTitle, e.movie.releaseDate ?: e.movie.year?.toString(), e.files.maxOfOrNull { it.file.durationMs ?: 0 } ?: 0,
                    e.lastPlayed, e.files.sumOf { it.file.sizeBytes })
            }

            LibrarySection.SERIES -> series.values.filter { e ->
                f.collectionId == null && (f.genreId == null || f.genreId in e.genreIds) && matchesYear(e.year) && matchesWatch(e.status) &&
                    (f.certification == null || e.series.certification == f.certification) && matchesFiles(e.files) &&
                    (listMembers == null || listMembers.any { it.seriesId == e.series.id }) &&
                    (personWorks == null || e.series.id in personWorks.seriesIds)
            }.map { e ->
                Sortable(item(e), e.series.sortTitle, e.series.firstAirDate, e.files.sumOf { it.file.durationMs ?: 0 }, e.lastPlayed, e.files.sumOf { it.file.sizeBytes })
            }
        }

        val byTitle = compareBy<Sortable, String>(NaturalOrder) { it.sortTitle }
        val comparator: Comparator<Sortable> = when (q.sort.field) {
            SortField.TITLE -> byTitle
            SortField.ADDED -> compareBy<Sortable> { it.item.addedAt }.then(byTitle)
            SortField.RELEASE -> compareBy<Sortable> { it.release ?: "" }.then(byTitle)
            SortField.RATING -> compareBy<Sortable> { it.item.rating ?: 0f }.then(byTitle)
            SortField.DURATION -> compareBy<Sortable> { it.duration }.then(byTitle)
            SortField.LAST_PLAYED -> compareBy<Sortable> { it.lastPlayed }.then(byTitle)
            SortField.FILE_SIZE -> compareBy<Sortable> { it.size }.then(byTitle)
        }
        val sorted = rows.sortedWith(if (q.sort.order == SortOrder.ASC) comparator else comparator.reversed())
        val items = sorted.map { it.item }
        return q.limit?.let { items.take(it) } ?: items
    }

    fun filterOptions(section: LibrarySection): FilterOptions {
        val ids = if (section == LibrarySection.MOVIES) movies.values.flatMap { it.genreIds } else series.values.flatMap { it.genreIds }
        val years = if (section == LibrarySection.MOVIES) movies.values.mapNotNull { it.movie.year } else series.values.mapNotNull { it.year }
        val certs = if (section == LibrarySection.MOVIES) movies.values.mapNotNull { it.movie.certification } else series.values.mapNotNull { it.series.certification }
        val files = if (section == LibrarySection.MOVIES) movies.values.flatMap { it.files } else series.values.flatMap { it.files }
        val languages = files.flatMap { it.audioLanguages }.mapNotNull(::normaliseLanguage).distinct()
            .map { LanguageOption(it, languageName(it)) }.sortedBy { it.name }
        return FilterOptions(
            genres = ids.distinct().mapNotNull { id -> genres[id]?.let { Genre(id, null, it) } }.sortedBy { it.name },
            decades = years.map { it / 10 * 10 }.distinct().sortedDescending(),
            certifications = certs.filter { it.isNotBlank() }.distinct().sorted(),
            folders = folders.filter { folder -> files.any { it.file.folderId == folder.id } },
            audioLanguages = languages
        )
    }

    // endregion

    // region home rows

    fun continueWatching(limit: Int = 20): List<HomeCard> =
        fileIndex.values.filter { it.inProgress && it.file.isAvailable }
            .sortedByDescending { it.lastWatched }
            .mapNotNull { file ->
                file.file.movieId?.let { id -> movies[id]?.let { return@mapNotNull HomeCard(item(it), null, file.file.id, progress = file.progress) } }
                val episodeId = file.file.episodeId ?: return@mapNotNull null
                val seriesId = episodeToSeries[episodeId] ?: return@mapNotNull null
                val s = series[seriesId] ?: return@mapNotNull null
                val ep = s.episodes.firstOrNull { it.episode.id == episodeId } ?: return@mapNotNull null
                HomeCard(item(s), episodeLabel(ep.episode), file.file.id, episodeId, file.progress)
            }
            .distinctBy { it.item.key }
            .take(limit)

    fun nextUp(limit: Int = 20): List<HomeCard> {
        val rows = series.values.flatMap { s ->
            s.episodes.flatMap { ep ->
                ep.files.map { f ->
                    EpisodeFileState(
                        seriesId = s.series.id, episodeId = ep.episode.id, seasonNumber = ep.episode.seasonNumber,
                        episodeNumber = ep.episode.episodeNumber, mediaFileId = f.file.id, isAvailable = f.file.isAvailable,
                        completed = f.completed, positionMs = f.watch?.positionMs ?: 0, durationMs = f.watch?.durationMs ?: (f.file.durationMs ?: 0),
                        lastWatchedAt = f.lastWatched, width = f.file.width, height = f.file.height, size = f.file.sizeBytes
                    )
                }
            }
        }
        return NextUpResolver.resolve(rows).take(limit).mapNotNull { next ->
            val s = series[next.seriesId] ?: return@mapNotNull null
            val ep = s.episodes.firstOrNull { it.episode.id == next.episodeId } ?: return@mapNotNull null
            HomeCard(item(s), episodeLabel(ep.episode), next.mediaFileId, next.episodeId, 0f)
        }
    }

    fun recentlyAdded(limit: Int = 20): List<HomeCard> =
        (movies.values.map { item(it) } + series.values.map { s ->
            item(s).copy(addedAt = s.files.maxOfOrNull { it.file.addedAt } ?: s.series.addedAt)
        }).sortedByDescending { it.addedAt }.take(limit).map { HomeCard(it) }

    fun unwatched(limit: Int = 20): List<HomeCard> =
        movies.values.filter { it.status == WatchStatus.UNWATCHED && it.available }.sortedByDescending { it.movie.addedAt }
            .take(limit).map { HomeCard(item(it)) }

    fun favorites(): List<HomeCard> {
        val fav = favoritesId ?: return emptyList()
        return members.filter { it.listId == fav }.sortedByDescending { it.addedAt }.mapNotNull { m ->
            m.movieId?.let { id -> movies[id]?.let { HomeCard(item(it)) } } ?: m.seriesId?.let { id -> series[id]?.let { HomeCard(item(it)) } }
        }
    }

    // endregion

    // region search

    data class SearchResults(val items: List<LibraryItem>, val people: List<LibraryStore.PersonCredits>, val episodes: List<Pair<SeriesEntry, EpisodeEntry>>) {
        val isEmpty get() = items.isEmpty() && people.isEmpty() && episodes.isEmpty()
    }

    fun search(text: String): SearchResults {
        val tokens = TextFold.tokens(text)
        if (tokens.isEmpty()) return SearchResults(emptyList(), emptyList(), emptyList())
        fun score(vararg fields: String?): Int? = fields.filterNotNull().mapNotNull { TextFold.score(TextFold.fold(it), tokens) }.maxOrNull()

        val items = (movies.values.mapNotNull { e -> score(e.movie.title, e.movie.originalTitle)?.let { it to item(e) } } +
            series.values.mapNotNull { e -> score(e.series.title, e.series.originalTitle)?.let { it to item(e) } })
            .sortedWith(compareByDescending<Pair<Int, LibraryItem>> { it.first }.thenBy { it.second.title })
            .map { it.second }
        val people = people.mapNotNull { p -> score(p.name)?.let { it to p } }.sortedByDescending { it.first }.map { it.second }.take(30)
        val episodes = if (text.length < 3) emptyList() else series.values.flatMap { s ->
            s.owned.mapNotNull { ep -> score(ep.episode.title)?.let { Triple(it, s, ep) } }
        }.sortedByDescending { it.first }.take(30).map { it.second to it.third }
        return SearchResults(items, people, episodes)
    }

    // endregion

    companion object {
        val EMPTY = LibrarySnapshot(emptyMap(), emptyMap(), emptyList(), emptyMap(), emptyMap(), emptyList(), emptyList(), emptyList(), emptyMap(), emptyMap())

        fun episodeLabel(e: Episode): String = "S%02dE%02d".format(e.seasonNumber, e.episodeNumber) + (e.title?.let { " · $it" } ?: "")

        fun build(store: LibraryStore): LibrarySnapshot {
            val watch = store.watchStates()
            val languages = store.audioLanguagesByFile()
            val files = store.allFiles().map { FileEntry(it, watch[it.id], languages[it.id].orEmpty()) }
            val byMovie = files.filter { it.file.movieId != null }.groupBy { it.file.movieId!! }
            val byEpisode = files.filter { it.file.episodeId != null }.groupBy { it.file.episodeId!! }
            val movieGenres = store.movieGenres()
            val seriesGenres = store.seriesGenres()

            val movies = store.movies().filter { it.id in byMovie }.associate { m ->
                m.id to MovieEntry(m, byMovie[m.id].orEmpty().sortedWith(versionOrder), movieGenres[m.id].orEmpty())
            }
            val episodes = store.allEpisodes().groupBy { it.seriesId }
            val seasons = store.allSeasons().groupBy { it.seriesId }
            val series = store.series().associate { s ->
                val eps = episodes[s.id].orEmpty().sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
                    .map { EpisodeEntry(it, byEpisode[it.id].orEmpty().sortedWith(versionOrder)) }
                s.id to SeriesEntry(s, seasons[s.id].orEmpty().sortedBy { it.seasonNumber }, eps, seriesGenres[s.id].orEmpty())
            }.filterValues { it.owned.isNotEmpty() }

            return LibrarySnapshot(
                movies = movies,
                series = series,
                folders = store.folders(),
                genres = store.genres(),
                collections = store.collections(),
                lists = store.lists(),
                members = store.listMembers(),
                people = store.people(),
                fileIndex = files.associateBy { it.file.id },
                episodeToSeries = episodes.values.flatten().associate { it.id to it.seriesId }
            )
        }

        /** Available first, then the sharpest. */
        private val versionOrder = compareByDescending<FileEntry> { it.file.isAvailable }
            .thenByDescending { it.file.height ?: 0 }.thenByDescending { it.file.sizeBytes }

        fun normaliseLanguage(code: String): String? {
            val c = code.trim().lowercase()
            if (c.isEmpty() || c == "und" || c == "unknown") return null
            if (c.length == 2) return c
            return Locale.getISOLanguages().firstOrNull { iso ->
                runCatching { Locale(iso).isO3Language == c }.getOrDefault(false)
            } ?: LANG_ALIASES[c] ?: c.take(3)
        }

        private val LANG_ALIASES = mapOf("fre" to "fr", "ger" to "de", "dut" to "nl", "chi" to "zh", "cze" to "cs", "gre" to "el", "rum" to "ro", "per" to "fa", "slo" to "sk", "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "geo" to "ka", "ice" to "is", "mac" to "mk", "may" to "ms", "wel" to "cy")

        fun languageName(code: String?): String {
            if (code.isNullOrBlank()) return "Inconnue"
            val normalised = normaliseLanguage(code) ?: return "Inconnue"
            val name = Locale(normalised).getDisplayLanguage(Locale.FRENCH)
            return if (name.isNullOrBlank() || name == normalised) code else name.replaceFirstChar { it.titlecase(Locale.FRENCH) }
        }
    }
}

data class SeriesTarget(val episode: EpisodeEntry, val kind: com.lecteur.core.model.SeriesPlayKind)

/** What the "Lire" button of a series plays: the episode in progress, the next one, the first one, or a rewatch. */
fun seriesPlayTarget(entry: SeriesEntry): SeriesTarget? {
    val rows = entry.owned.flatMap { ep ->
        ep.files.map { f ->
            EpisodeFileState(
                seriesId = entry.series.id, episodeId = ep.episode.id, seasonNumber = ep.episode.seasonNumber,
                episodeNumber = ep.episode.episodeNumber, mediaFileId = f.file.id, isAvailable = f.file.isAvailable,
                completed = f.completed, positionMs = f.watch?.positionMs ?: 0, durationMs = f.watch?.durationMs ?: (f.file.durationMs ?: 0),
                lastWatchedAt = f.lastWatched, width = f.file.width, height = f.file.height, size = f.file.sizeBytes
            )
        }
    }
    val (summary, kind) = NextUpResolver.playTarget(NextUpResolver.summarize(rows)) ?: return null
    val ep = entry.episodes.firstOrNull { it.episode.id == summary.episodeId } ?: return null
    return SeriesTarget(ep, kind)
}
