package com.lecteur.core.model

/** The two shelves of the library. Their rows live in different tables, so they are queried separately. */
enum class LibrarySection { MOVIES, SERIES }

enum class SortField { TITLE, ADDED, RELEASE, RATING, DURATION, LAST_PLAYED, FILE_SIZE }

enum class SortOrder {
    ASC, DESC;

    fun flipped() = if (this == ASC) DESC else ASC
}

/** What a field sorts by when the user has not chosen an order yet: titles A to Z, everything else best/latest first. */
fun SortField.defaultOrder(): SortOrder = if (this == SortField.TITLE) SortOrder.ASC else SortOrder.DESC

data class LibrarySort(val field: SortField = SortField.TITLE, val order: SortOrder = SortOrder.ASC)

enum class WatchFilter { ALL, UNWATCHED, IN_PROGRESS, WATCHED }

enum class WatchStatus { UNWATCHED, IN_PROGRESS, WATCHED }

/**
 * Picture size tiers. A widescreen 1080p film is 1920x800 once its black bars are cropped, so the tier looks at the
 * width as well as the height: a film is never ranked below the tier of its real resolution.
 */
enum class ResolutionTier(val minWidth: Int, val minHeight: Int, val label: String) {
    ANY(0, 0, "Toutes"),
    HD(1200, 700, "720p et plus"),
    FULL_HD(1800, 1000, "1080p et plus"),
    UHD(3000, 2000, "4K");

    companion object {
        /** The tier a picture of this size belongs to, ANY when the size is unknown. */
        fun of(width: Int?, height: Int?): ResolutionTier {
            val w = width ?: 0
            val h = height ?: 0
            return entries.reversed().firstOrNull { it != ANY && (w >= it.minWidth || h >= it.minHeight) } ?: ANY
        }

        /** Short badge text: "4K", "1080p", "720p", "SD" or null when the size is unknown. */
        fun badge(width: Int?, height: Int?): String? {
            if ((width ?: 0) <= 0 && (height ?: 0) <= 0) return null
            return when (of(width, height)) {
                UHD -> "4K"
                FULL_HD -> "1080p"
                HD -> "720p"
                ANY -> "SD"
            }
        }
    }
}

enum class HdrFilter { ANY, ANY_HDR, DOLBY_VISION }

data class LibraryFilters(
    val genreId: Long? = null,
    /** Inclusive year range; a decade is 1990..1999. */
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val watch: WatchFilter = WatchFilter.ALL,
    val resolution: ResolutionTier = ResolutionTier.ANY,
    val hdr: HdrFilter = HdrFilter.ANY,
    val certification: String? = null,
    val folderId: Long? = null,
    val audioLanguage: String? = null,
    /** Titles an actor or director appears in. */
    val personId: Long? = null,
    /** Movies of a TMDB saga (no effect on series). */
    val collectionId: Long? = null,
    /** Titles of a user list (favourites are one of them). */
    val userListId: Long? = null
) {
    /** How many of the filters the user can toggle are on: drives the badge on the filter button. */
    val activeCount: Int
        get() = listOf(
            genreId != null, yearFrom != null || yearTo != null, watch != WatchFilter.ALL, resolution != ResolutionTier.ANY,
            hdr != HdrFilter.ANY, certification != null, folderId != null, audioLanguage != null
        ).count { it }

    /** Filters coming from a "browse by" entry (actor, saga, list): not offered in the filter sheet, kept when it clears. */
    val hasScope: Boolean get() = personId != null || collectionId != null || userListId != null

    val isEmpty: Boolean get() = this == LibraryFilters()

    /** Clears what the filter sheet controls and keeps the scope the screen was opened with. */
    fun withoutSheetFilters() = LibraryFilters(personId = personId, collectionId = collectionId, userListId = userListId)
}

data class LibraryQuery(
    val section: LibrarySection,
    val sort: LibrarySort = LibrarySort(),
    val filters: LibraryFilters = LibraryFilters(),
    /** Set for the short rows of the home screen; null for the paged library. */
    val limit: Int? = null
)

/** One poster of a grid or row. */
data class LibraryItem(
    val kind: MediaKind,
    val id: Long,
    val title: String,
    val year: Int?,
    val posterPath: String?,
    val rating: Float?,
    val watch: WatchStatus,
    /** Progress of the film being watched, 0 when there is none; series show [watchedCount] of [unitCount] instead. */
    val progress: Float,
    /** Episodes with a file (1 for a film). */
    val unitCount: Int,
    val watchedCount: Int,
    /** False when every file is on a drive that is not plugged in. */
    val isAvailable: Boolean,
    val addedAt: Long,
    val matchState: MatchState = MatchState.IDENTIFIED
) {
    val key: String get() = "${kind.name}-$id"
}

/** Facets offered by the filter sheet, built from what the library really contains. */
data class FilterOptions(
    val genres: List<Genre> = emptyList(),
    val decades: List<Int> = emptyList(),
    val certifications: List<String> = emptyList(),
    val folders: List<LibraryFolder> = emptyList(),
    val audioLanguages: List<LanguageOption> = emptyList()
)

/** An audio language offered by the filter: [code] is the normalised 2-letter code, [name] what the user reads. */
data class LanguageOption(val code: String, val name: String)

/**
 * Every spelling a language takes on audio tracks: files carry "fre", "fra" or "fr" for French depending on the tool that
 * wrote them, and a filter on "fr" must find them all.
 */
object LanguageVariants {
    private val threeLetter = mapOf(
        "fr" to listOf("fre", "fra"), "en" to listOf("eng"), "de" to listOf("ger", "deu"), "es" to listOf("spa"),
        "it" to listOf("ita"), "pt" to listOf("por"), "ja" to listOf("jpn"), "ru" to listOf("rus"), "ko" to listOf("kor"),
        "zh" to listOf("chi", "zho"), "ar" to listOf("ara"), "nl" to listOf("dut", "nld"), "pl" to listOf("pol"),
        "tr" to listOf("tur"), "sv" to listOf("swe"), "da" to listOf("dan"), "no" to listOf("nor"), "fi" to listOf("fin"),
        "cs" to listOf("cze", "ces"), "el" to listOf("gre", "ell"), "he" to listOf("heb"), "hi" to listOf("hin"),
        "hu" to listOf("hun"), "ro" to listOf("rum", "ron"), "th" to listOf("tha"), "uk" to listOf("ukr"), "vi" to listOf("vie")
    )

    /** Lowercase spellings of [code], the code itself included. */
    fun of(code: String): List<String> {
        val normalised = code.trim().lowercase()
        return listOf(normalised) + threeLetter[normalised].orEmpty()
    }
}

/** What a long-press, a tap on "Reprendre" or the "Lire" button hands to the player. */
data class QueueEntry(val uri: String, val title: String, val mediaFileId: Long? = null)

data class PlayPlan(
    val entries: List<QueueEntry>,
    val startIndex: Int = 0,
    /** Shown in the queue sheet ("Saison 2", the folder name...). */
    val label: String? = null,
    val shuffle: Boolean = false
) {
    init {
        require(entries.isNotEmpty()) { "A play plan needs at least one entry" }
        require(startIndex in entries.indices) { "startIndex $startIndex outside ${entries.indices}" }
    }
}

/** A personal list as shown on the lists screen. */
data class UserListSummary(val id: Long, val name: String, val itemCount: Int, val isFavorites: Boolean)

/** One line of the "add to a list" picker: a list, and whether the title is already in it. */
data class ListChoice(val id: Long, val name: String, val isMember: Boolean)

/** What the "add to a list" sheet shows while it is open. */
data class ListPickerState(val title: String, val choices: List<ListChoice>, val error: String? = null)
