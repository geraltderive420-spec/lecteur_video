package com.lecteur.desktop.data

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Types

/**
 * The desktop library: one SQLite file in the user's profile. A single connection guarded by a lock is plenty for one
 * user (the queries are small and WAL keeps reads cheap); every access goes through [read] or [write].
 */
class Db(file: File) {
    private val connection: Connection
    private val lock = Any()

    init {
        file.parentFile?.mkdirs()
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        connection.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=NORMAL")
            st.execute("PRAGMA foreign_keys=ON")
        }
        migrate()
    }

    fun <T> read(block: Session.() -> T): T = synchronized(lock) { Session(connection).block() }

    /** Runs [block] in one transaction: all of it lands, or none of it. */
    fun <T> write(block: Session.() -> T): T = synchronized(lock) {
        val auto = connection.autoCommit
        connection.autoCommit = false
        try {
            Session(connection).block().also { connection.commit() }
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = auto
        }
    }

    private fun migrate() {
        val version = connection.createStatement().use { st -> st.executeQuery("PRAGMA user_version").use { if (it.next()) it.getInt(1) else 0 } }
        if (version < 1) {
            connection.createStatement().use { st -> SCHEMA_V1.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach(st::execute) }
            connection.createStatement().use { it.execute("PRAGMA user_version = 1") }
        }
    }

    class Session(private val c: Connection) {
        fun exec(sql: String, vararg args: Any?): Int = prepare(sql, args).use { it.executeUpdate() }

        fun insert(sql: String, vararg args: Any?): Long =
            c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { st ->
                bind(st, args)
                st.executeUpdate()
                st.generatedKeys.use { if (it.next()) it.getLong(1) else -1 }
            }

        fun <T> query(sql: String, vararg args: Any?, map: (Row) -> T): List<T> = prepare(sql, args).use { st ->
            st.executeQuery().use { rs ->
                val row = Row(rs)
                val out = ArrayList<T>()
                while (rs.next()) out += map(row)
                out
            }
        }

        fun <T> one(sql: String, vararg args: Any?, map: (Row) -> T): T? = query(sql, *args, map = map).firstOrNull()

        fun long(sql: String, vararg args: Any?): Long? = one(sql, *args) { it.longOrNull(1) }

        private fun prepare(sql: String, args: Array<out Any?>): PreparedStatement = c.prepareStatement(sql).also { bind(it, args) }

        private fun bind(st: PreparedStatement, args: Array<out Any?>) {
            args.forEachIndexed { i, v ->
                val index = i + 1
                when (v) {
                    null -> st.setNull(index, Types.NULL)
                    is Long -> st.setLong(index, v)
                    is Int -> st.setInt(index, v)
                    is Float -> st.setDouble(index, v.toDouble())
                    is Double -> st.setDouble(index, v)
                    is Boolean -> st.setInt(index, if (v) 1 else 0)
                    is Enum<*> -> st.setString(index, v.name)
                    else -> st.setString(index, v.toString())
                }
            }
        }
    }

    /** Column access by name or position, null-aware. */
    class Row(private val rs: ResultSet) {
        fun str(name: String): String? = rs.getString(name)
        fun long(name: String): Long = rs.getLong(name)
        fun longOrNull(name: String): Long? = rs.getLong(name).takeUnless { rs.wasNull() }
        fun longOrNull(index: Int): Long? = rs.getLong(index).takeUnless { rs.wasNull() }
        fun int(name: String): Int = rs.getInt(name)
        fun intOrNull(name: String): Int? = rs.getInt(name).takeUnless { rs.wasNull() }
        fun floatOrNull(name: String): Float? = rs.getDouble(name).takeUnless { rs.wasNull() }?.toFloat()
        fun bool(name: String): Boolean = rs.getInt(name) != 0
    }

    private companion object {
        val SCHEMA_V1 = """
            CREATE TABLE folders (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                path TEXT NOT NULL,
                category TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                last_scanned INTEGER
            );
            CREATE TABLE collections (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                tmdb_id INTEGER UNIQUE,
                name TEXT NOT NULL,
                overview TEXT, poster TEXT, backdrop TEXT
            );
            CREATE TABLE movies (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                tmdb_id INTEGER, imdb_id TEXT,
                title TEXT NOT NULL, original_title TEXT, sort_title TEXT NOT NULL,
                year INTEGER, release_date TEXT, overview TEXT, runtime INTEGER, rating REAL, certification TEXT,
                poster TEXT, backdrop TEXT, logo TEXT, trailer TEXT,
                collection_id INTEGER REFERENCES collections(id) ON DELETE SET NULL,
                added_at INTEGER NOT NULL,
                match_state TEXT NOT NULL DEFAULT 'UNIDENTIFIED',
                match_locked INTEGER NOT NULL DEFAULT 0,
                identify_tried_at INTEGER
            );
            CREATE INDEX movies_tmdb ON movies(tmdb_id);
            CREATE TABLE series (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                tmdb_id INTEGER, imdb_id TEXT,
                title TEXT NOT NULL, original_title TEXT, sort_title TEXT NOT NULL,
                first_air_date TEXT, status TEXT, overview TEXT, rating REAL, certification TEXT,
                poster TEXT, backdrop TEXT, logo TEXT, trailer TEXT,
                added_at INTEGER NOT NULL,
                match_state TEXT NOT NULL DEFAULT 'UNIDENTIFIED',
                match_locked INTEGER NOT NULL DEFAULT 0,
                identify_tried_at INTEGER
            );
            CREATE INDEX series_tmdb ON series(tmdb_id);
            CREATE TABLE series_aliases (
                alias TEXT PRIMARY KEY,
                series_id INTEGER NOT NULL REFERENCES series(id) ON DELETE CASCADE
            );
            CREATE TABLE seasons (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                series_id INTEGER NOT NULL REFERENCES series(id) ON DELETE CASCADE,
                number INTEGER NOT NULL,
                name TEXT, overview TEXT, poster TEXT, air_date TEXT,
                UNIQUE(series_id, number)
            );
            CREATE TABLE episodes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                series_id INTEGER NOT NULL REFERENCES series(id) ON DELETE CASCADE,
                season_id INTEGER NOT NULL REFERENCES seasons(id) ON DELETE CASCADE,
                season_number INTEGER NOT NULL,
                episode_number INTEGER NOT NULL,
                absolute_number INTEGER,
                title TEXT, overview TEXT, still TEXT, air_date TEXT, runtime INTEGER,
                UNIQUE(series_id, season_number, episode_number)
            );
            CREATE TABLE media_files (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                folder_id INTEGER NOT NULL REFERENCES folders(id) ON DELETE CASCADE,
                path TEXT NOT NULL,
                file_name TEXT NOT NULL,
                size INTEGER NOT NULL,
                fingerprint TEXT NOT NULL,
                last_modified INTEGER NOT NULL,
                duration_ms INTEGER,
                container TEXT,
                video_codec TEXT NOT NULL DEFAULT 'UNKNOWN',
                width INTEGER, height INTEGER,
                hdr TEXT NOT NULL DEFAULT 'NONE',
                movie_id INTEGER REFERENCES movies(id) ON DELETE SET NULL,
                episode_id INTEGER REFERENCES episodes(id) ON DELETE SET NULL,
                added_at INTEGER NOT NULL,
                available INTEGER NOT NULL DEFAULT 1
            );
            CREATE INDEX media_files_folder ON media_files(folder_id);
            CREATE INDEX media_files_movie ON media_files(movie_id);
            CREATE INDEX media_files_episode ON media_files(episode_id);
            CREATE INDEX media_files_fp ON media_files(fingerprint);
            CREATE TABLE audio_tracks (
                media_file_id INTEGER NOT NULL REFERENCES media_files(id) ON DELETE CASCADE,
                idx INTEGER NOT NULL,
                language TEXT, codec TEXT NOT NULL, channels INTEGER NOT NULL, title TEXT,
                is_default INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX audio_tracks_file ON audio_tracks(media_file_id);
            CREATE TABLE subtitle_tracks (
                media_file_id INTEGER NOT NULL REFERENCES media_files(id) ON DELETE CASCADE,
                idx INTEGER NOT NULL,
                language TEXT, codec TEXT, external_path TEXT, title TEXT,
                is_forced INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX subtitle_tracks_file ON subtitle_tracks(media_file_id);
            CREATE TABLE watch_state (
                media_file_id INTEGER PRIMARY KEY REFERENCES media_files(id) ON DELETE CASCADE,
                position_ms INTEGER NOT NULL DEFAULT 0,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                completed INTEGER NOT NULL DEFAULT 0,
                play_count INTEGER NOT NULL DEFAULT 0,
                last_watched INTEGER NOT NULL,
                audio_index INTEGER, subtitle_index INTEGER,
                audio_delay INTEGER NOT NULL DEFAULT 0,
                subtitle_delay INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE genres (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                tmdb_id INTEGER UNIQUE,
                name TEXT NOT NULL
            );
            CREATE TABLE movie_genres (
                movie_id INTEGER NOT NULL REFERENCES movies(id) ON DELETE CASCADE,
                genre_id INTEGER NOT NULL REFERENCES genres(id) ON DELETE CASCADE,
                PRIMARY KEY(movie_id, genre_id)
            );
            CREATE TABLE series_genres (
                series_id INTEGER NOT NULL REFERENCES series(id) ON DELETE CASCADE,
                genre_id INTEGER NOT NULL REFERENCES genres(id) ON DELETE CASCADE,
                PRIMARY KEY(series_id, genre_id)
            );
            CREATE TABLE people (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                tmdb_id INTEGER UNIQUE,
                name TEXT NOT NULL,
                profile TEXT
            );
            CREATE TABLE credits (
                person_id INTEGER NOT NULL REFERENCES people(id) ON DELETE CASCADE,
                movie_id INTEGER REFERENCES movies(id) ON DELETE CASCADE,
                series_id INTEGER REFERENCES series(id) ON DELETE CASCADE,
                role TEXT,
                ord INTEGER NOT NULL DEFAULT 0,
                is_director INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX credits_movie ON credits(movie_id);
            CREATE INDEX credits_series ON credits(series_id);
            CREATE INDEX credits_person ON credits(person_id);
            CREATE TABLE user_lists (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                is_favorites INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            );
            CREATE TABLE user_list_items (
                list_id INTEGER NOT NULL REFERENCES user_lists(id) ON DELETE CASCADE,
                movie_id INTEGER REFERENCES movies(id) ON DELETE CASCADE,
                series_id INTEGER REFERENCES series(id) ON DELETE CASCADE,
                added_at INTEGER NOT NULL
            );
            CREATE INDEX user_list_items_list ON user_list_items(list_id);
            CREATE TABLE series_prefs (
                series_id INTEGER PRIMARY KEY REFERENCES series(id) ON DELETE CASCADE,
                audio_language TEXT,
                subtitle_language TEXT
            );
            INSERT INTO user_lists(name, is_favorites, created_at) VALUES ('Favoris', 1, 0)
        """.trimIndent()
    }
}
