package com.lecteur.desktop.data

import com.lecteur.core.common.match.TitleSimilarity
import com.lecteur.core.model.AudioCodec
import com.lecteur.core.model.Episode
import com.lecteur.core.model.HdrType
import com.lecteur.core.model.LibraryFolder
import com.lecteur.core.model.MatchState
import com.lecteur.core.model.MediaCategory
import com.lecteur.core.model.MediaFile
import com.lecteur.core.model.Movie
import com.lecteur.core.model.Season
import com.lecteur.core.model.Series
import com.lecteur.core.model.VideoCodec
import com.lecteur.core.model.WatchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart

data class AudioTrackRow(val index: Int, val language: String?, val codec: AudioCodec, val channels: Int, val title: String?, val isDefault: Boolean)

data class SubtitleTrackRow(val index: Int, val language: String?, val codec: String?, val externalPath: String?, val title: String?, val isForced: Boolean)

/** What the scanner learnt about a file from libVLC (or from its name when libVLC could not read it). */
data class TechInfo(
    val durationMs: Long?,
    val container: String?,
    val videoCodec: VideoCodec,
    val width: Int?,
    val height: Int?,
    val hdr: HdrType,
    val audio: List<AudioTrackRow>,
    val subtitles: List<SubtitleTrackRow>
)

data class CreditRow(val personId: Long, val name: String, val profile: String?, val role: String?, val order: Int, val isDirector: Boolean)

/**
 * Every read and write of the library. Writers call [changed]; the in-memory [snapshot] (what the screens show) is rebuilt
 * shortly after, so a scan of hundreds of files refreshes the screens a few times rather than hundreds.
 */
@OptIn(FlowPreview::class)
class LibraryStore(val db: Db) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val _snapshot = MutableStateFlow(LibrarySnapshot.EMPTY)
    val snapshot: StateFlow<LibrarySnapshot> = _snapshot.asStateFlow()

    init {
        changes.onStart { emit(Unit) }
            .debounce(250)
            .onEach { _snapshot.value = runCatching { LibrarySnapshot.build(this) }.getOrElse { _snapshot.value } }
            .launchIn(scope)
    }

    fun changed() {
        changes.tryEmit(Unit)
    }

    /** Rebuilds at once (after a user action whose result must show without delay). */
    fun refreshNow() {
        _snapshot.value = LibrarySnapshot.build(this)
    }

    // region folders

    fun folders(): List<LibraryFolder> = db.read {
        query("SELECT * FROM folders ORDER BY path") { folder(it) }
    }

    fun folder(id: Long): LibraryFolder? = db.read { one("SELECT * FROM folders WHERE id = ?", id) { folder(it) } }

    fun addFolder(path: String, category: MediaCategory): Long = db.write {
        insert("INSERT INTO folders(path, category, enabled) VALUES (?, ?, 1)", path, category)
    }.also { changed() }

    fun updateFolder(id: Long, category: MediaCategory, enabled: Boolean) {
        db.write { exec("UPDATE folders SET category = ?, enabled = ? WHERE id = ?", category, enabled, id) }
        changed()
    }

    fun removeFolder(id: Long) {
        db.write {
            exec("DELETE FROM media_files WHERE folder_id = ?", id)
            exec("DELETE FROM folders WHERE id = ?", id)
            removeOrphansIn(this)
        }
        changed()
    }

    fun setLastScanned(id: Long, at: Long) = db.write { exec("UPDATE folders SET last_scanned = ? WHERE id = ?", at, id) }

    private fun folder(r: Db.Row) = LibraryFolder(
        id = r.long("id"),
        uri = r.str("path")!!,
        displayPath = r.str("path")!!,
        category = MediaCategory.fromString(r.str("category")!!),
        enabled = r.bool("enabled"),
        lastScannedAt = r.longOrNull("last_scanned")
    )

    // endregion

    // region media files

    fun filesOfFolder(folderId: Long): List<MediaFile> = db.read {
        query("SELECT * FROM media_files WHERE folder_id = ?", folderId) { mediaFile(it) }
    }

    fun allFiles(): List<MediaFile> = db.read { query("SELECT * FROM media_files") { mediaFile(it) } }

    fun mediaFile(id: Long): MediaFile? = db.read { one("SELECT * FROM media_files WHERE id = ?", id) { mediaFile(it) } }

    fun mediaFileByPath(path: String): MediaFile? = db.read { one("SELECT * FROM media_files WHERE path = ?", path) { mediaFile(it) } }

    fun audioTracks(fileId: Long): List<AudioTrackRow> = db.read {
        query("SELECT * FROM audio_tracks WHERE media_file_id = ? ORDER BY idx", fileId) {
            AudioTrackRow(it.int("idx"), it.str("language"), runCatching { AudioCodec.valueOf(it.str("codec")!!) }.getOrDefault(AudioCodec.UNKNOWN), it.int("channels"), it.str("title"), it.bool("is_default"))
        }
    }

    fun subtitleTracks(fileId: Long): List<SubtitleTrackRow> = db.read {
        query("SELECT * FROM subtitle_tracks WHERE media_file_id = ? ORDER BY idx", fileId) {
            SubtitleTrackRow(it.int("idx"), it.str("language"), it.str("codec"), it.str("external_path"), it.str("title"), it.bool("is_forced"))
        }
    }

    /** All audio languages per file, for the filters. */
    fun audioLanguagesByFile(): Map<Long, Set<String>> = db.read {
        query("SELECT media_file_id, language FROM audio_tracks WHERE language IS NOT NULL") { it.long("media_file_id") to it.str("language")!! }
    }.groupBy({ it.first }, { it.second.lowercase() }).mapValues { it.value.toSet() }

    fun insertFile(file: MediaFile, info: TechInfo?): Long = db.write {
        val id = insert(
            """INSERT INTO media_files(folder_id, path, file_name, size, fingerprint, last_modified, added_at, available)
               VALUES (?, ?, ?, ?, ?, ?, ?, 1)""",
            file.folderId, file.uri, file.fileName, file.sizeBytes, file.fingerprint, file.lastModified, file.addedAt
        )
        if (info != null) writeTech(this, id, info)
        id
    }

    fun updateFile(id: Long, path: String, fileName: String, size: Long, lastModified: Long, fingerprint: String, info: TechInfo?) = db.write {
        exec(
            "UPDATE media_files SET path = ?, file_name = ?, size = ?, last_modified = ?, fingerprint = ?, available = 1 WHERE id = ?",
            path, fileName, size, lastModified, fingerprint, id
        )
        if (info != null) writeTech(this, id, info)
    }

    private fun writeTech(s: Db.Session, id: Long, info: TechInfo) = with(s) {
        exec(
            "UPDATE media_files SET duration_ms = ?, container = ?, video_codec = ?, width = ?, height = ?, hdr = ? WHERE id = ?",
            info.durationMs, info.container, info.videoCodec, info.width, info.height, info.hdr, id
        )
        exec("DELETE FROM audio_tracks WHERE media_file_id = ?", id)
        exec("DELETE FROM subtitle_tracks WHERE media_file_id = ?", id)
        info.audio.forEach {
            exec("INSERT INTO audio_tracks(media_file_id, idx, language, codec, channels, title, is_default) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, it.index, it.language, it.codec, it.channels, it.title, it.isDefault)
        }
        info.subtitles.forEach {
            exec("INSERT INTO subtitle_tracks(media_file_id, idx, language, codec, external_path, title, is_forced) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, it.index, it.language, it.codec, it.externalPath, it.title, it.isForced)
        }
    }

    fun setAvailability(ids: List<Long>, available: Boolean) {
        if (ids.isEmpty()) return
        db.write { ids.forEach { exec("UPDATE media_files SET available = ? WHERE id = ?", available, it) } }
    }

    fun setFolderAvailability(folderId: Long, available: Boolean) =
        db.write { exec("UPDATE media_files SET available = ? WHERE folder_id = ?", available, folderId) }

    fun deleteFiles(ids: List<Long>) {
        if (ids.isEmpty()) return
        db.write {
            ids.forEach { exec("DELETE FROM media_files WHERE id = ?", it) }
            removeOrphansIn(this)
        }
    }

    fun setLinks(fileId: Long, movieId: Long?, episodeId: Long?) =
        db.write { exec("UPDATE media_files SET movie_id = ?, episode_id = ? WHERE id = ?", movieId, episodeId, fileId) }

    fun removeOrphans() = db.write { removeOrphansIn(this) }

    private fun removeOrphansIn(s: Db.Session) = with(s) {
        exec("DELETE FROM movies WHERE id NOT IN (SELECT movie_id FROM media_files WHERE movie_id IS NOT NULL)")
        exec("DELETE FROM episodes WHERE id NOT IN (SELECT episode_id FROM media_files WHERE episode_id IS NOT NULL) AND series_id IN (SELECT id FROM series WHERE tmdb_id IS NULL)")
        exec("DELETE FROM series WHERE id NOT IN (SELECT e.series_id FROM episodes e JOIN media_files f ON f.episode_id = e.id)")
    }

    private fun mediaFile(r: Db.Row) = MediaFile(
        id = r.long("id"),
        folderId = r.long("folder_id"),
        uri = r.str("path")!!,
        displayPath = r.str("path")!!,
        fileName = r.str("file_name")!!,
        sizeBytes = r.long("size"),
        fingerprint = r.str("fingerprint")!!,
        lastModified = r.long("last_modified"),
        durationMs = r.longOrNull("duration_ms"),
        container = r.str("container"),
        videoCodec = runCatching { VideoCodec.valueOf(r.str("video_codec")!!) }.getOrDefault(VideoCodec.UNKNOWN),
        width = r.intOrNull("width"),
        height = r.intOrNull("height"),
        hdrType = runCatching { HdrType.valueOf(r.str("hdr")!!) }.getOrDefault(HdrType.NONE),
        movieId = r.longOrNull("movie_id"),
        episodeId = r.longOrNull("episode_id"),
        addedAt = r.long("added_at"),
        isAvailable = r.bool("available")
    )

    // endregion

    // region movies & series

    fun movies(): List<Movie> = db.read { query("SELECT * FROM movies") { movie(it) } }
    fun movie(id: Long): Movie? = db.read { one("SELECT * FROM movies WHERE id = ?", id) { movie(it) } }
    fun movieByTmdb(tmdbId: Long): Movie? = db.read { one("SELECT * FROM movies WHERE tmdb_id = ?", tmdbId) { movie(it) } }

    fun series(): List<Series> = db.read { query("SELECT * FROM series") { series(it) } }
    fun series(id: Long): Series? = db.read { one("SELECT * FROM series WHERE id = ?", id) { series(it) } }
    fun seriesByTmdb(tmdbId: Long): Series? = db.read { one("SELECT * FROM series WHERE tmdb_id = ?", tmdbId) { series(it) } }

    fun seasons(seriesId: Long): List<Season> = db.read {
        query("SELECT * FROM seasons WHERE series_id = ? ORDER BY number", seriesId) { season(it) }
    }

    fun allSeasons(): List<Season> = db.read { query("SELECT * FROM seasons") { season(it) } }

    fun episodes(seriesId: Long): List<Episode> = db.read {
        query("SELECT * FROM episodes WHERE series_id = ? ORDER BY season_number, episode_number", seriesId) { episode(it) }
    }

    fun allEpisodes(): List<Episode> = db.read { query("SELECT * FROM episodes") { episode(it) } }

    fun episode(id: Long): Episode? = db.read { one("SELECT * FROM episodes WHERE id = ?", id) { episode(it) } }

    fun insertMovie(m: Movie): Long = db.write {
        insert(
            """INSERT INTO movies(tmdb_id, imdb_id, title, original_title, sort_title, year, overview, runtime, rating, certification,
               poster, backdrop, added_at, match_state, match_locked) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            m.tmdbId, m.imdbId, m.title, m.originalTitle, sortKey(m.title), m.year, m.overview, m.runtimeMinutes, m.rating,
            m.certification, m.posterPath, m.backdropPath, m.addedAt, m.matchState, m.matchLocked
        )
    }

    fun insertSeries(s: Series): Long = db.write {
        insert(
            """INSERT INTO series(tmdb_id, imdb_id, title, original_title, sort_title, first_air_date, overview, rating, certification,
               poster, backdrop, added_at, match_state, match_locked) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            s.tmdbId, s.imdbId, s.title, s.originalTitle, sortKey(s.title), s.firstAirDate, s.overview, s.rating, s.certification,
            s.posterPath, s.backdropPath, s.addedAt, s.matchState, s.matchLocked
        )
    }

    fun seasonId(seriesId: Long, number: Int): Long = db.write {
        long("SELECT id FROM seasons WHERE series_id = ? AND number = ?", seriesId, number)
            ?: insert("INSERT INTO seasons(series_id, number) VALUES (?, ?)", seriesId, number)
    }

    fun episodeId(seriesId: Long, season: Int, number: Int, absolute: Int?, airDate: String?): Long {
        val seasonId = seasonId(seriesId, season)
        return db.write {
            long("SELECT id FROM episodes WHERE series_id = ? AND season_number = ? AND episode_number = ?", seriesId, season, number)
                ?: insert(
                    "INSERT INTO episodes(series_id, season_id, season_number, episode_number, absolute_number, air_date) VALUES (?, ?, ?, ?, ?, ?)",
                    seriesId, seasonId, season, number, absolute, airDate
                )
        }
    }

    fun seriesForAlias(alias: String): Long? = db.read { long("SELECT series_id FROM series_aliases WHERE alias = ?", alias) }

    fun putAlias(alias: String, seriesId: Long, replace: Boolean) = db.write {
        exec(if (replace) "INSERT OR REPLACE INTO series_aliases(alias, series_id) VALUES (?, ?)" else "INSERT OR IGNORE INTO series_aliases(alias, series_id) VALUES (?, ?)", alias, seriesId)
    }

    fun moviesToIdentify(retryBefore: Long): List<Movie> = db.read {
        query("SELECT * FROM movies WHERE match_state = 'UNIDENTIFIED' AND match_locked = 0 AND (identify_tried_at IS NULL OR identify_tried_at < ?)", retryBefore) { movie(it) }
    }

    fun seriesToIdentify(retryBefore: Long): List<Series> = db.read {
        query("SELECT * FROM series WHERE match_state = 'UNIDENTIFIED' AND match_locked = 0 AND (identify_tried_at IS NULL OR identify_tried_at < ?)", retryBefore) { series(it) }
    }

    fun markTried(kind: String, id: Long, at: Long) = db.write {
        exec("UPDATE ${if (kind == "movie") "movies" else "series"} SET identify_tried_at = ? WHERE id = ?", at, id)
    }

    fun setMatchState(kind: String, id: Long, state: MatchState) = db.write {
        exec("UPDATE ${if (kind == "movie") "movies" else "series"} SET match_state = ? WHERE id = ?", state, id)
    }.also { changed() }

    fun credits(movieId: Long? = null, seriesId: Long? = null): List<CreditRow> = db.read {
        query(
            """SELECT c.*, p.name, p.profile FROM credits c JOIN people p ON p.id = c.person_id
               WHERE ${if (movieId != null) "c.movie_id = ?" else "c.series_id = ?"} ORDER BY c.is_director DESC, c.ord""",
            movieId ?: seriesId
        ) { CreditRow(it.long("person_id"), it.str("name")!!, it.str("profile"), it.str("role"), it.int("ord"), it.bool("is_director")) }
    }

    data class PersonCredits(val personId: Long, val name: String, val profile: String?, val movieIds: Set<Long>, val seriesIds: Set<Long>)

    fun people(): List<PersonCredits> = db.read {
        query("SELECT c.person_id, p.name, p.profile, c.movie_id, c.series_id FROM credits c JOIN people p ON p.id = c.person_id") {
            arrayOf<Any?>(it.long("person_id"), it.str("name"), it.str("profile"), it.longOrNull("movie_id"), it.longOrNull("series_id"))
        }
    }.groupBy { it[0] as Long }.map { (id, rows) ->
        PersonCredits(id, rows[0][1] as String, rows[0][2] as String?, rows.mapNotNull { it[3] as Long? }.toSet(), rows.mapNotNull { it[4] as Long? }.toSet())
    }

    fun genres(): Map<Long, String> = db.read { query("SELECT id, name FROM genres") { it.long("id") to it.str("name")!! } }.toMap()

    fun movieGenres(): Map<Long, Set<Long>> = db.read { query("SELECT movie_id, genre_id FROM movie_genres") { it.long("movie_id") to it.long("genre_id") } }
        .groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }

    fun seriesGenres(): Map<Long, Set<Long>> = db.read { query("SELECT series_id, genre_id FROM series_genres") { it.long("series_id") to it.long("genre_id") } }
        .groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }

    fun collections(): Map<Long, String> = db.read { query("SELECT id, name FROM collections") { it.long("id") to it.str("name")!! } }.toMap()

    private fun movie(r: Db.Row) = Movie(
        id = r.long("id"), tmdbId = r.longOrNull("tmdb_id"), imdbId = r.str("imdb_id"), title = r.str("title")!!,
        originalTitle = r.str("original_title"), sortTitle = r.str("sort_title")!!, year = r.intOrNull("year"),
        releaseDate = r.str("release_date"), overview = r.str("overview"), runtimeMinutes = r.intOrNull("runtime"),
        rating = r.floatOrNull("rating"), certification = r.str("certification"), posterPath = r.str("poster"),
        backdropPath = r.str("backdrop"), logoPath = r.str("logo"), trailerKey = r.str("trailer"),
        collectionId = r.longOrNull("collection_id"), addedAt = r.long("added_at"),
        matchState = MatchState.valueOf(r.str("match_state")!!), matchLocked = r.bool("match_locked")
    )

    private fun series(r: Db.Row) = Series(
        id = r.long("id"), tmdbId = r.longOrNull("tmdb_id"), imdbId = r.str("imdb_id"), title = r.str("title")!!,
        originalTitle = r.str("original_title"), sortTitle = r.str("sort_title")!!, firstAirDate = r.str("first_air_date"),
        status = r.str("status"), overview = r.str("overview"), rating = r.floatOrNull("rating"),
        certification = r.str("certification"), posterPath = r.str("poster"), backdropPath = r.str("backdrop"),
        logoPath = r.str("logo"), addedAt = r.long("added_at"),
        matchState = MatchState.valueOf(r.str("match_state")!!), matchLocked = r.bool("match_locked")
    )

    private fun season(r: Db.Row) = Season(
        id = r.long("id"), seriesId = r.long("series_id"), seasonNumber = r.int("number"), name = r.str("name"),
        overview = r.str("overview"), posterPath = r.str("poster"), airDate = r.str("air_date")
    )

    private fun episode(r: Db.Row) = Episode(
        id = r.long("id"), seriesId = r.long("series_id"), seasonId = r.long("season_id"), seasonNumber = r.int("season_number"),
        episodeNumber = r.int("episode_number"), absoluteNumber = r.intOrNull("absolute_number"), title = r.str("title"),
        overview = r.str("overview"), stillPath = r.str("still"), airDate = r.str("air_date"), runtimeMinutes = r.intOrNull("runtime")
    )

    fun seriesTrailer(id: Long): String? = db.read { one("SELECT trailer FROM series WHERE id = ?", id) { it.str("trailer") } }

    // endregion

    // region watch state

    fun watchStates(): Map<Long, WatchState> = db.read { query("SELECT * FROM watch_state") { watch(it) } }.associateBy { it.mediaFileId }

    fun watchState(fileId: Long): WatchState? = db.read { one("SELECT * FROM watch_state WHERE media_file_id = ?", fileId) { watch(it) } }

    fun saveWatch(w: WatchState) {
        db.write {
            exec(
                """INSERT INTO watch_state(media_file_id, position_ms, duration_ms, completed, play_count, last_watched, audio_index, subtitle_index, audio_delay, subtitle_delay)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(media_file_id) DO UPDATE SET position_ms = excluded.position_ms, duration_ms = excluded.duration_ms,
                   completed = excluded.completed, play_count = excluded.play_count, last_watched = excluded.last_watched,
                   audio_index = excluded.audio_index, subtitle_index = excluded.subtitle_index,
                   audio_delay = excluded.audio_delay, subtitle_delay = excluded.subtitle_delay""",
                w.mediaFileId, w.positionMs, w.durationMs, w.isCompleted, w.playCount, w.lastWatchedAt,
                w.selectedAudioTrackIndex, w.selectedSubtitleTrackIndex, w.audioDelayMs, w.subtitleDelayMs
            )
        }
        changed()
    }

    /** Watched / not watched for a set of files (a movie, a season, a whole series). */
    fun setWatched(fileIds: Collection<Long>, watched: Boolean, now: Long = System.currentTimeMillis()) {
        db.write {
            fileIds.forEach { id ->
                if (watched) {
                    exec(
                        """INSERT INTO watch_state(media_file_id, position_ms, duration_ms, completed, play_count, last_watched)
                           VALUES (?, 0, COALESCE((SELECT duration_ms FROM media_files WHERE id = ?), 0), 1, 1, ?)
                           ON CONFLICT(media_file_id) DO UPDATE SET completed = 1, position_ms = 0, play_count = MAX(play_count, 1), last_watched = excluded.last_watched""",
                        id, id, now
                    )
                } else {
                    exec("UPDATE watch_state SET completed = 0, position_ms = 0 WHERE media_file_id = ?", id)
                }
            }
        }
        changed()
    }

    private fun watch(r: Db.Row) = WatchState(
        mediaFileId = r.long("media_file_id"), positionMs = r.long("position_ms"), durationMs = r.long("duration_ms"),
        isCompleted = r.bool("completed"), playCount = r.int("play_count"), lastWatchedAt = r.long("last_watched"),
        selectedAudioTrackIndex = r.intOrNull("audio_index"), selectedSubtitleTrackIndex = r.intOrNull("subtitle_index"),
        audioDelayMs = r.long("audio_delay"), subtitleDelayMs = r.long("subtitle_delay")
    )

    // endregion

    // region lists

    data class ListRow(val id: Long, val name: String, val isFavorites: Boolean, val createdAt: Long)
    data class ListMember(val listId: Long, val movieId: Long?, val seriesId: Long?, val addedAt: Long)

    fun lists(): List<ListRow> = db.read {
        query("SELECT * FROM user_lists ORDER BY is_favorites DESC, name COLLATE NOCASE") {
            ListRow(it.long("id"), it.str("name")!!, it.bool("is_favorites"), it.long("created_at"))
        }
    }

    fun listMembers(): List<ListMember> = db.read {
        query("SELECT * FROM user_list_items") { ListMember(it.long("list_id"), it.longOrNull("movie_id"), it.longOrNull("series_id"), it.long("added_at")) }
    }

    fun favoritesId(): Long = db.read { long("SELECT id FROM user_lists WHERE is_favorites = 1 LIMIT 1") }
        ?: db.write { insert("INSERT INTO user_lists(name, is_favorites, created_at) VALUES ('Favoris', 1, 0)") }

    fun createList(name: String): Long = db.write {
        insert("INSERT INTO user_lists(name, is_favorites, created_at) VALUES (?, 0, ?)", name.trim(), System.currentTimeMillis())
    }.also { changed() }

    fun renameList(id: Long, name: String) {
        db.write { exec("UPDATE user_lists SET name = ? WHERE id = ? AND is_favorites = 0", name.trim(), id) }
        changed()
    }

    fun deleteList(id: Long) {
        db.write { exec("DELETE FROM user_lists WHERE id = ? AND is_favorites = 0", id) }
        changed()
    }

    fun setMember(listId: Long, movieId: Long?, seriesId: Long?, member: Boolean) {
        db.write {
            exec("DELETE FROM user_list_items WHERE list_id = ? AND movie_id IS ? AND series_id IS ?", listId, movieId, seriesId)
            if (member) exec("INSERT INTO user_list_items(list_id, movie_id, series_id, added_at) VALUES (?, ?, ?, ?)", listId, movieId, seriesId, System.currentTimeMillis())
        }
        refreshNow()
    }

    // endregion

    // region series preferences

    fun seriesPrefs(seriesId: Long): Pair<String?, String?> = db.read {
        one("SELECT * FROM series_prefs WHERE series_id = ?", seriesId) { it.str("audio_language") to it.str("subtitle_language") }
    } ?: (null to null)

    fun setSeriesPrefs(seriesId: Long, audio: String?, subtitle: String?) = db.write {
        exec("INSERT OR REPLACE INTO series_prefs(series_id, audio_language, subtitle_language) VALUES (?, ?, ?)", seriesId, audio, subtitle)
    }

    // endregion

    companion object {
        fun sortKey(title: String): String = TitleSimilarity.comparisonKey(title).ifEmpty { title.lowercase() }
    }
}
